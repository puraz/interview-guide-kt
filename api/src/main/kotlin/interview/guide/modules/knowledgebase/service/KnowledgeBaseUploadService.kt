package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.file.FileHashService
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.infrastructure.file.FileValidationService
import interview.guide.infrastructure.file.KnowledgeBaseUploadLimiter
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Service
class KnowledgeBaseUploadService(
    private val parseService: KnowledgeBaseParseService, // 知识库解析服务
    private val persistenceService: KnowledgeBasePersistenceService, // 知识库持久化服务
    private val storageService: FileStorageService, // 文件存储服务
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val fileValidationService: FileValidationService, // 文件校验服务
    private val fileHashService: FileHashService, // 文件哈希服务
    private val vectorizeStreamProducer: VectorizeStreamProducer, // 向量化任务生产者
    private val uploadLimiter: KnowledgeBaseUploadLimiter // 上传并发限制器
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseUploadService::class.java)

    companion object {
        private const val MAX_FILE_SIZE: Long = 50 * 1024 * 1024 // 50MB
    }

    /**
     * 上传知识库文件
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    fun uploadKnowledgeBase(file: MultipartFile, name: String?, category: String?): Map<String, Any?> {
        return uploadLimiter.execute { doUploadKnowledgeBase(file, name, category) }
    }

    private fun doUploadKnowledgeBase(file: MultipartFile, name: String?, category: String?): Map<String, Any?> {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库")

        val fileName = file.originalFilename
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.size, category)

        // 2. 验证文件类型
        val contentType = parseService.detectContentType(file)
        validateContentType(contentType, fileName)

        // 3. 检查知识库是否已存在（去重）
        val fileHash = fileHashService.calculateHash(file)
        val existingKb = knowledgeBaseRepository.findByFileHash(fileHash)
        if (existingKb.isPresent) {
            log.info("检测到重复知识库: hash={}", fileHash)
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash)
        }

        // 4. 保存文件到RustFS
        val fileKey = storageService.uploadKnowledgeBase(file)
        val fileUrl = storageService.getFileUrl(fileKey)
        log.info("知识库已存储到RustFS: {}", fileKey)

        // 5. 保存知识库元数据到数据库（状态为 PENDING）；失败时补偿删除孤儿对象（按 fileKey，不删实体——此时还没有实体）
        val savedKb: KnowledgeBaseEntity
        try {
            savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash)
        } catch (dbError: Exception) {
            compensateOrphanObject(fileKey, dbError)
            throw dbError
        }

        // 6. 发送向量化任务到 Redis Stream（异步处理，正文由消费者从 RustFS 下载解析）。
        //    投递失败时文件与实体已保存（是手动重试与自动恢复的依据），只返回真实 FAILED 状态
        val enqueueAccepted = vectorizeStreamProducer.sendVectorizeTask(requireNotNull(savedKb.id))

        log.info("知识库上传完成: {}, kbId={}, enqueueAccepted={}", fileName, savedKb.id, enqueueAccepted)

        // 7. 返回结果：状态与数据库真实状态一致，投递失败不伪装成 PENDING
        return mapOf(
            "knowledgeBase" to mapOf(
                "id" to savedKb.id,
                "name" to savedKb.name,
                "category" to (savedKb.category ?: ""),
                "fileSize" to savedKb.fileSize,
                "vectorStatus" to if (enqueueAccepted) VectorStatus.PENDING.name else VectorStatus.FAILED.name
            ),
            "storage" to mapOf(
                "fileKey" to fileKey,
                "fileUrl" to fileUrl
            ),
            "enqueueAccepted" to enqueueAccepted,
            "message" to if (enqueueAccepted) "" else "文件已保存，但任务投递失败，可在管理页重试",
            "duplicate" to false
        )
    }

    /**
     * S3 已成功、数据库保存失败的补偿：按 fileKey 删除孤儿对象。
     * 补偿失败只记录，不覆盖原始业务异常。
     */
    private fun compensateOrphanObject(fileKey: String, originalError: Exception) {
        try {
            storageService.deleteKnowledgeBase(fileKey)
            log.warn("数据库保存失败已补偿删除孤儿对象: fileKey={}", fileKey)
        } catch (cleanupError: Exception) {
            log.error(
                "补偿删除孤儿对象失败，需人工清理: fileKey={}, error={}",
                fileKey, cleanupError.message, cleanupError
            )
        }
    }

    /**
     * 验证文件类型
     */
    private fun validateContentType(contentType: String?, fileName: String?) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            { fileValidationService.isKnowledgeBaseMimeType(it) },
            { fileValidationService.isMarkdownExtension(it) },
            "不支持的文件类型: $contentType，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        )
    }

    /**
     * 重新向量化知识库（手动重试）
     * 只投递知识库 ID，正文由消费者从 RustFS 下载解析
     *
     * @param kbId 知识库ID
     */
    fun revectorize(kbId: Long) {
        val kb = knowledgeBaseRepository.findById(kbId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "知识库不存在")

        if (kb.storageKey.isNullOrBlank() || kb.originalFilename.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "知识库缺少存储信息，无法重新向量化")
        }

        log.info("开始重新向量化知识库: kbId={}", kbId)

        // 1. 更新状态为 PENDING 并清零自动恢复计数（手动重试重新获得完整恢复额度）
        persistenceService.updateVectorStatusToPending(kbId)
        knowledgeBaseRepository.resetVectorRecoveryCount(kbId)

        // 2. 发送向量化任务到 Stream；投递失败必须让调用方感知，不能只写日志
        if (!vectorizeStreamProducer.sendVectorizeTask(kbId)) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "任务投递失败，请稍后重试")
        }

        log.info("重新向量化任务已发送: kbId={}", kbId)
    }
}

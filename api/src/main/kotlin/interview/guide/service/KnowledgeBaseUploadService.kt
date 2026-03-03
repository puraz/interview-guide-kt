package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.KnowledgeBaseEntity
import interview.guide.entity.VectorStatus
import interview.guide.infrastructure.file.FileHashService
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.infrastructure.file.FileValidationService
import interview.guide.listener.VectorizeStreamProducer
import interview.guide.repository.KnowledgeBaseRepository
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
    private val storageService: FileStorageService, // 存储服务
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val fileValidationService: FileValidationService, // 文件校验服务
    private val fileHashService: FileHashService, // 文件哈希服务
    private val vectorizeStreamProducer: VectorizeStreamProducer // 向量化任务生产者
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseUploadService::class.java)

    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB
    }

    /**
     * 上传知识库文件
     *
     * @param file 知识库文件 // 上传文件
     * @param name 知识库名称 // 可选名称
     * @param category 分类 // 可选分类
     * @return 上传结果 // 上传与存储信息
     */
    fun uploadKnowledgeBase(file: MultipartFile, name: String?, category: String?): Map<String, Any> {
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库")

        val fileName = file.originalFilename
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.size, category)

        val contentType = parseService.detectContentType(file)
        validateContentType(contentType, fileName)

        val fileHash = fileHashService.calculateHash(file)
        val existingKb = knowledgeBaseRepository.findByFileHash(fileHash)
        if (existingKb != null) {
            log.info("检测到重复知识库: hash={}", fileHash)
            return persistenceService.handleDuplicateKnowledgeBase(existingKb, fileHash)
        }

        val content = parseService.parseContent(file)
        if (content.isBlank()) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确")
        }

        val fileKey = storageService.uploadKnowledgeBase(file)
        val fileUrl = storageService.getFileUrl(fileKey)
        log.info("知识库已存储到RustFS: {}", fileKey)

        val savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash)

        vectorizeStreamProducer.sendVectorizeTask(savedKb.id!!, content)

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.id)

        return mapOf(
            "knowledgeBase" to mapOf(
                "id" to savedKb.id,
                "name" to savedKb.name,
                "category" to (savedKb.category ?: ""),
                "fileSize" to savedKb.fileSize,
                "contentLength" to content.length,
                "vectorStatus" to VectorStatus.PENDING.name
            ),
            "storage" to mapOf(
                "fileKey" to fileKey,
                "fileUrl" to fileUrl
            ),
            "duplicate" to false
        )
    }

    /**
     * 验证文件类型
     */
    private fun validateContentType(contentType: String?, fileName: String?) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: ${contentType ?: "unknown"}，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        )
    }

    /**
     * 重新向量化知识库（手动重试）
     */
    fun revectorize(kbId: Long) {
        val kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "知识库不存在") }

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.name)

        val content = parseService.downloadAndParseContent(kb.storageKey, kb.originalFilename)
        if (content.isBlank()) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容")
        }

        persistenceService.updateVectorStatusToPending(kbId)
        vectorizeStreamProducer.sendVectorizeTask(kbId, content)

        log.info("重新向量化任务已发送: kbId={}", kbId)
    }
}

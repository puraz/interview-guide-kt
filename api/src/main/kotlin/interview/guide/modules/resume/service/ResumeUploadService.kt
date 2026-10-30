package interview.guide.modules.resume.service

import interview.guide.common.config.AppConfigProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.common.transaction.TransactionalExecutor
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.infrastructure.file.FileValidationService
import interview.guide.modules.resume.listener.AnalyzeStreamProducer
import interview.guide.modules.resume.model.ResumeEntity
import interview.guide.modules.resume.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Service
class ResumeUploadService(
    private val parseService: ResumeParseService, // 简历解析服务
    private val storageService: FileStorageService, // 文件存储服务
    private val persistenceService: ResumePersistenceService, // 简历持久化服务
    private val appConfig: AppConfigProperties, // 简历配置
    private val fileValidationService: FileValidationService, // 文件校验服务
    private val analyzeStreamProducer: AnalyzeStreamProducer, // 简历分析任务生产者
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val transactionalExecutor: TransactionalExecutor // 小范围事务执行器
) {

    companion object {
        private val log = LoggerFactory.getLogger(ResumeUploadService::class.java)

        private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    }

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    fun uploadAndAnalyze(file: MultipartFile): Map<String, Any?> {
        val startTime = System.currentTimeMillis()

        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历")

        val fileName = file.originalFilename
        val fileSize = file.size
        log.info("收到简历上传请求: {}, 大小: {} bytes ({}), 上传开始处理",
            fileName, fileSize, formatFileSize(fileSize))

        // 2. 验证文件类型
        val contentType = parseService.detectContentType(file)
        validateContentType(contentType)

        // 3. 检查简历是否已存在（去重）
        val existingResume = persistenceService.findExistingResume(file)
        if (existingResume != null) {
            log.info("简历上传处理完成（重复）: {} - 耗时: {}ms",
                fileName, System.currentTimeMillis() - startTime)
            return handleDuplicateResume(existingResume)
        }

        // 4. 解析简历文本
        val parseStart = System.currentTimeMillis()
        val resumeText = parseService.parseResume(file)
        if (resumeText.isBlank()) {
            throw BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF")
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
            fileName, System.currentTimeMillis() - parseStart, resumeText.length)

        // 5. 保存简历到RustFS
        val storageStart = System.currentTimeMillis()
        val fileKey = storageService.uploadResume(file)
        val fileUrl = storageService.getFileUrl(fileKey)
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
            fileKey, System.currentTimeMillis() - storageStart)

        // 6. 保存简历到数据库（状态为 PENDING）；失败时按 fileKey 补偿删除孤儿对象（简历是先解析再上传，补偿只包在 S3 成功之后）
        val savedResume: ResumeEntity
        try {
            savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl)
        } catch (dbError: Exception) {
            try {
                storageService.deleteResume(fileKey)
                log.warn("数据库保存失败已补偿删除孤儿对象: fileKey={}", fileKey)
            } catch (cleanupError: Exception) {
                log.error("补偿删除孤儿对象失败，需人工清理: fileKey={}, error={}",
                    fileKey, cleanupError.message, cleanupError)
            }
            throw dbError
        }

        // 7. 发送分析任务到 Redis Stream；投递失败时文件与实体保留（手动重试依据），返回真实状态
        val resumeId = savedResume.id
        val enqueueAccepted = resumeId != null && analyzeStreamProducer.sendAnalyzeTask(resumeId)

        val totalTime = System.currentTimeMillis() - startTime
        log.info("简历上传处理完成: {}, resumeId={} - 总耗时: {}ms (解析+存储+入库)",
            fileName, savedResume.id, totalTime)

        // 8. 返回结果：状态与数据库真实状态一致，投递失败不伪装成 PENDING
        return mapOf(
            "resume" to mapOf(
                "id" to savedResume.id,
                "filename" to savedResume.originalFilename,
                "analyzeStatus" to (if (enqueueAccepted) AsyncTaskStatus.PENDING.name else AsyncTaskStatus.FAILED.name)
            ),
            "storage" to mapOf(
                "fileKey" to fileKey,
                "fileUrl" to fileUrl,
                "resumeId" to savedResume.id
            ),
            "enqueueAccepted" to enqueueAccepted,
            "message" to (if (enqueueAccepted) "" else "简历已保存，但分析任务投递失败，请稍后重试"),
            "duplicate" to false
        )
    }

    /**
     * 格式化文件大小为可读字符串
     *
     * @param bytes 字节数 // 原始大小
     * @return 可读大小 // B/KB/MB
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }

    /**
     * 验证文件类型
     *
     * @param contentType 检测到的 MIME 类型 // 可能为 null
     */
    private fun validateContentType(contentType: String?) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.allowedTypes,
            "不支持的文件类型: $contentType"
        )
    }

    /**
     * 处理重复简历
     *
     * @param resume 已存在的简历 // 去重命中
     * @return 上传结果 // 返回历史分析或当前状态
     */
    private fun handleDuplicateResume(resume: ResumeEntity): Map<String, Any?> {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.id)

        // 获取历史分析结果
        val analysisOpt = resume.id?.let { persistenceService.getLatestAnalysisAsVo(it) }

        // 已有分析结果，直接返回
        // 没有分析结果（可能之前分析失败），返回当前状态
        if (analysisOpt != null) {
            return mapOf(
                "analysis" to analysisOpt,
                "storage" to mapOf(
                    "fileKey" to (resume.storageKey ?: ""),
                    "fileUrl" to (resume.storageUrl ?: ""),
                    "resumeId" to resume.id
                ),
                "enqueueAccepted" to true,
                "message" to "检测到重复文件，已返回现有分析结果",
                "duplicate" to true
            )
        }
        return mapOf(
            "resume" to mapOf(
                "id" to resume.id,
                "filename" to resume.originalFilename,
                "analyzeStatus" to (resume.analyzeStatus?.name ?: AsyncTaskStatus.PENDING.name)
            ),
            "storage" to mapOf(
                "fileKey" to (resume.storageKey ?: ""),
                "fileUrl" to (resume.storageUrl ?: ""),
                "resumeId" to resume.id
            ),
            "enqueueAccepted" to true,
            "message" to "检测到重复文件，已返回现有简历",
            "duplicate" to true
        )
    }

    /**
     * 重新分析简历（手动重试）
     * 只投递简历 ID，正文以数据库实体为事实来源；
     * 历史正文为空时由消费者从 RustFS 恢复并回填
     *
     * @param resumeId 简历ID
     */
    fun reanalyze(resumeId: Long) {
        resumeRepository.findById(resumeId).orElseThrow {
            BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在")
        }

        log.info("开始重新分析简历: resumeId={}", resumeId)

        transactionalExecutor.run {
            val resume = resumeRepository.findById(resumeId).orElseThrow {
                BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在")
            }
            resume.analyzeStatus = AsyncTaskStatus.PENDING
            resume.analyzeError = null
            resume.analyzeAttemptId = null
            resume.analyzeUpdatedAt = LocalDateTime.now()
            resumeRepository.save(resume)
        }
        // 手动重试清零自动恢复计数
        resumeRepository.resetAnalyzeRecoveryCount(resumeId)

        // 事务提交后再发送分析任务到 Stream；投递失败必须让调用方感知
        if (!analyzeStreamProducer.sendAnalyzeTask(resumeId)) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "任务投递失败，请稍后重试")
        }

        log.info("重新分析任务已发送: resumeId={}", resumeId)
    }
}

package interview.guide.service

import interview.guide.common.config.AppConfigProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.entity.ResumeEntity
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.infrastructure.file.FileValidationService
import interview.guide.listener.AnalyzeStreamProducer
import interview.guide.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

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
    private val resumeRepository: ResumeRepository // 简历仓库
) {

    private val log = LoggerFactory.getLogger(ResumeUploadService::class.java)

    companion object {
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    }

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件 // 上传文件
     * @return 上传结果 // 任务入队结果
     */
    fun uploadAndAnalyze(file: MultipartFile): Map<String, Any> {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历")

        val fileName = file.originalFilename
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.size)

        // 2. 验证文件类型
        val contentType = parseService.detectContentType(file)
        validateContentType(contentType)

        // 3. 检查简历是否已存在（去重）
        val existingResume = persistenceService.findExistingResume(file)
        if (existingResume != null) {
            return handleDuplicateResume(existingResume)
        }

        // 4. 解析简历文本
        val resumeText = parseService.parseResume(file)
        if (resumeText.isBlank()) {
            throw BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF")
        }

        // 5. 保存简历到 RustFS
        val fileKey = storageService.uploadResume(file)
        val fileUrl = storageService.getFileUrl(fileKey)
        log.info("简历已存储到RustFS: {}", fileKey)

        // 6. 保存简历到数据库（状态为 PENDING）
        val savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl)

        // 7. 发送分析任务到 Redis Stream（异步处理）
        analyzeStreamProducer.sendAnalyzeTask(savedResume.id!!, resumeText)

        log.info("简历上传完成，分析任务已入队: {}, resumeId={}", fileName, savedResume.id)

        // 8. 返回结果（状态为 PENDING）
        return mapOf(
            "resume" to mapOf(
                "id" to savedResume.id,
                "filename" to savedResume.originalFilename,
                "analyzeStatus" to AsyncTaskStatus.PENDING.name
            ),
            "storage" to mapOf(
                "fileKey" to fileKey,
                "fileUrl" to fileUrl,
                "resumeId" to savedResume.id
            ),
            "duplicate" to false
        )
    }

    /**
     * 验证文件类型
     */
    private fun validateContentType(contentType: String?) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.allowedTypes,
            "不支持的文件类型: $contentType" // 对齐Java提示，直接展示检测到的类型（可能为 null）
        )
    }

    /**
     * 处理重复简历
     */
    private fun handleDuplicateResume(resume: ResumeEntity): Map<String, Any> {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.id)

        val analysis = resume.id?.let { persistenceService.getLatestAnalysisAsVo(it) }

        return if (analysis != null) {
            mapOf(
                "analysis" to analysis,
                "storage" to mapOf(
                    "fileKey" to (resume.storageKey ?: ""),
                    "fileUrl" to (resume.storageUrl ?: ""),
                    "resumeId" to resume.id
                ),
                "duplicate" to true
            )
        } else {
            val analyzeStatus = resume.analyzeStatus
            mapOf(
                "resume" to mapOf(
                    "id" to resume.id,
                    "filename" to resume.originalFilename,
                    "analyzeStatus" to analyzeStatus.name // 返回当前分析状态
                ),
                "storage" to mapOf(
                    "fileKey" to (resume.storageKey ?: ""),
                    "fileUrl" to (resume.storageUrl ?: ""),
                    "resumeId" to resume.id
                ),
                "duplicate" to true
            )
        }
    }

    /**
     * 重新分析简历（手动重试）
     *
     * @param resumeId 简历ID // 简历主键
     */
    @Transactional
    fun reanalyze(resumeId: Long) {
        val resume = resumeRepository.findById(resumeId).orElseThrow {
            BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在")
        }

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, resume.originalFilename)

        var resumeText = resume.resumeText
        if (resumeText.isNullOrBlank()) {
            resumeText = parseService.downloadAndParseContent(resume.storageKey, resume.originalFilename)
            if (resumeText.isBlank()) {
                throw BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容")
            }
            resume.resumeText = resumeText
        }

        // 更新状态为 PENDING
        resume.analyzeStatus = AsyncTaskStatus.PENDING
        resume.analyzeError = null
        resumeRepository.save(resume)

        // 发送分析任务到 Stream
        analyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText)

        log.info("重新分析任务已发送: resumeId={}", resumeId)
    }
}

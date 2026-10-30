package interview.guide.modules.resume.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.file.FileHashService
import interview.guide.infrastructure.mapper.ResumeMapper
import interview.guide.modules.interview.model.ResumeAnalysisVo
import interview.guide.modules.resume.model.ResumeAnalysisEntity
import interview.guide.modules.resume.model.ResumeEntity
import interview.guide.modules.resume.repository.ResumeAnalysisRepository
import interview.guide.modules.resume.repository.ResumeRepository
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Service
class ResumePersistenceService(
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val analysisRepository: ResumeAnalysisRepository, // 简历评测仓库
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val resumeMapper: ResumeMapper, // 简历对象映射
    private val fileHashService: FileHashService // 文件哈希服务
) {

    private val log = LoggerFactory.getLogger(ResumePersistenceService::class.java)

    /**
     * 检查简历是否已存在（基于文件内容hash）
     *
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    fun findExistingResume(file: MultipartFile): ResumeEntity? {
        return try {
            val fileHash = fileHashService.calculateHash(file)
            val existing = resumeRepository.findByFileHash(fileHash)

            if (existing != null) {
                log.info("检测到重复简历: hash={}", fileHash)
                existing.incrementAccessCount()
                resumeRepository.save(existing)
            }

            existing
        } catch (e: Exception) {
            log.error("检查简历重复时出错: {}", e.message)
            null
        }
    }

    /**
     * 保存新简历
     *
     * @param file        上传文件 // 简历文件
     * @param resumeText  解析后的正文 // 简历文本
     * @param storageKey  存储Key // RustFS 路径
     * @param storageUrl  存储URL // RustFS 地址
     * @return 保存后的简历 // 含主键的实体
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveResume(
        file: MultipartFile,
        resumeText: String,
        storageKey: String,
        storageUrl: String
    ): ResumeEntity {
        try {
            val fileHash = fileHashService.calculateHash(file)

            val resume = ResumeEntity()
            resume.fileHash = fileHash
            resume.originalFilename = file.originalFilename
            resume.fileSize = file.size
            resume.contentType = file.contentType
            resume.storageKey = storageKey
            resume.storageUrl = storageUrl
            resume.resumeText = resumeText
            // 进展时间初始化：NULL 会被 stale 扫描的阈值比较排除
            resume.analyzeUpdatedAt = LocalDateTime.now()

            val saved = resumeRepository.save(resume)
            log.info("简历已保存: id={}, hash={}", saved.id, fileHash)

            return saved
        } catch (e: Exception) {
            log.error("保存简历失败: {}", e.message, e)
            throw BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败")
        }
    }

    /**
     * 回填简历正文（独立短事务，供 Stream 消费者从 RustFS 恢复文本后使用）
     *
     * @param resumeId   简历ID // 主键
     * @param resumeText 简历正文 // 恢复出的文本
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateResumeText(resumeId: Long, resumeText: String) {
        val resume = resumeRepository.findById(resumeId).orElseThrow {
            BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在")
        }
        resume.resumeText = resumeText
        resumeRepository.save(resume)
    }

    /**
     * 保存简历评测结果
     *
     * @param resume   简历实体 // 关联简历
     * @param analysis 评测结果 // AI 分析结果
     * @return 保存后的评测记录 // 含主键的实体
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveAnalysis(resume: ResumeEntity, analysis: ResumeAnalysisVo): ResumeAnalysisEntity {
        return saveAnalysisInternal(resume, analysis)
    }

    /**
     * 保存评测结果的内部实现
     *
     * @param resume   简历实体 // 关联简历
     * @param analysis 评测结果 // AI 分析结果
     * @return 保存后的评测记录 // 含主键的实体
     */
    private fun saveAnalysisInternal(
        resume: ResumeEntity,
        analysis: ResumeAnalysisVo
    ): ResumeAnalysisEntity {
        try {
            // 使用 ResumeMapper 映射基础字段
            val entity = resumeMapper.toAnalysisEntity(analysis)
            entity.resume = resume

            // JSON 字段需要手动序列化
            entity.strengthsJson = objectMapper.writeValueAsString(analysis.strengths ?: emptyList<String>())
            entity.suggestionsJson = objectMapper.writeValueAsString(
                analysis.suggestions ?: emptyList<ResumeAnalysisVo.SuggestionVo>()
            )

            val saved = analysisRepository.save(entity)
            log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
                saved.id, resume.id, analysis.overallScore)

            return saved
        } catch (e: JsonProcessingException) {
            log.error("序列化评测结果失败: {}", e.message, e)
            throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败")
        }
    }

    /**
     * 锁定简历行并校验当前执行代次后保存分析，防止超时回收后的旧消费者落库。
     *
     * @param resumeId  简历ID // 主键
     * @param attemptId 执行代次 // 当前消费者持有的 attemptId
     * @param analysis  评测结果 // AI 分析结果
     * @return 保存后的评测记录 // 含主键的实体
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveAnalysisIfOwned(
        resumeId: Long,
        attemptId: String?,
        analysis: ResumeAnalysisVo
    ): ResumeAnalysisEntity {
        val resume = resumeRepository.findByIdForUpdate(resumeId)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在")
        if (resume.analyzeStatus != interview.guide.common.model.AsyncTaskStatus.PROCESSING
            || resume.analyzeAttemptId != attemptId
        ) {
            throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析任务执行权已失效")
        }
        resume.analyzeUpdatedAt = LocalDateTime.now()
        resumeRepository.save(resume)
        return saveAnalysisInternal(resume, analysis)
    }

    /**
     * 获取简历的最新评测结果
     *
     * @param resumeId 简历ID // 主键
     * @return 最新评测记录 // 不存在时为 null
     */
    fun getLatestAnalysis(resumeId: Long): ResumeAnalysisEntity? {
        return analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId)
    }

    /**
     * 获取简历的最新评测结果（返回VO）
     *
     * @param resumeId 简历ID // 主键
     * @return 最新评测结果 // 不存在时为 null
     */
    fun getLatestAnalysisAsVo(resumeId: Long): ResumeAnalysisVo? {
        return getLatestAnalysis(resumeId)?.let { entityToVo(it) }
    }

    /**
     * 获取所有简历列表
     *
     * @return 简历列表 // 全部实体
     */
    fun findAllResumes(): List<ResumeEntity> {
        return resumeRepository.findAll()
    }

    /**
     * 获取简历的所有评测记录
     *
     * @param resumeId 简历ID // 主键
     * @return 评测记录 // 按评测时间倒序
     */
    fun findAnalysesByResumeId(resumeId: Long): List<ResumeAnalysisEntity> {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId)
    }

    /**
     * 将实体转换为VO
     *
     * @param entity 分析实体 // 数据库记录
     * @return 分析结果 // VO 对象
     */
    fun entityToVo(entity: ResumeAnalysisEntity): ResumeAnalysisVo {
        try {
            val strengths: List<String> = objectMapper.readValue(
                entity.strengthsJson ?: "[]",
                object : TypeReference<List<String>>() {}
            )

            val suggestions: List<ResumeAnalysisVo.SuggestionVo> = objectMapper.readValue(
                entity.suggestionsJson ?: "[]",
                object : TypeReference<List<ResumeAnalysisVo.SuggestionVo>>() {}
            )

            return ResumeAnalysisVo(
                entity.overallScore,
                resumeMapper.toScoreDetail(entity), // 使用 ResumeMapper 映射
                entity.summary,
                strengths,
                suggestions,
                entity.resume?.resumeText
            )
        } catch (e: JsonProcessingException) {
            log.error("反序列化评测结果失败: {}", e.message)
            throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败")
        }
    }

    /**
     * 根据ID获取简历
     *
     * @param id 简历ID // 主键
     * @return 简历实体 // 不存在时为 null
     */
    fun findById(id: Long): ResumeEntity? {
        return resumeRepository.findById(id).orElse(null)
    }

    /**
     * 删除简历及其所有关联数据
     * 包括：简历分析记录、面试会话（会自动删除面试答案）
     *
     * @param id 简历ID // 主键
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteResume(id: Long) {
        val resume = resumeRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        // 1. 删除所有简历分析记录
        val analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id)
        if (analyses.isNotEmpty()) {
            analysisRepository.deleteAll(analyses)
            log.info("已删除 {} 条简历分析记录", analyses.size)
        }

        // 2. 删除简历实体（面试会话会在服务层删除）
        resumeRepository.delete(resume)
        log.info("简历已删除: id={}, filename={}", id, resume.originalFilename)
    }
}

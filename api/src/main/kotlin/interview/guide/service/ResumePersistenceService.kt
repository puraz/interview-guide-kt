package interview.guide.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.ResumeAnalysisEntity
import interview.guide.entity.ResumeEntity
import interview.guide.infrastructure.file.FileHashService
import interview.guide.repository.ResumeAnalysisRepository
import interview.guide.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Service
class ResumePersistenceService(
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val analysisRepository: ResumeAnalysisRepository, // 简历评测仓库
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val fileHashService: FileHashService // 文件哈希服务
) {

    private val log = LoggerFactory.getLogger(ResumePersistenceService::class.java)

    /**
     * 检查简历是否已存在（基于文件内容hash）
     *
     * @param file 上传的文件 // 简历文件
     * @return 已存在简历 // 可能为空
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
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveResume(file: MultipartFile, resumeText: String, storageKey: String, storageUrl: String): ResumeEntity {
        try {
            val fileHash = fileHashService.calculateHash(file)
            val resume = ResumeEntity().apply {
                this.fileHash = fileHash
                this.originalFilename = file.originalFilename
                this.fileSize = file.size
                this.contentType = file.contentType
                this.storageKey = storageKey
                this.storageUrl = storageUrl
                this.resumeText = resumeText
            }
            val saved = resumeRepository.save(resume)
            log.info("简历已保存: id={}, hash={}", saved.id, fileHash)
            return saved
        } catch (e: Exception) {
            log.error("保存简历失败: {}", e.message, e)
            throw BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败")
        }
    }

    /**
     * 保存简历评测结果
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveAnalysis(resume: ResumeEntity, analysis: ResumeAnalysisVo): ResumeAnalysisEntity {
        try {
            val entity = ResumeAnalysisEntity().apply {
                this.resume = resume
                this.overallScore = analysis.overallScore
                this.contentScore = analysis.scoreDetail?.contentScore
                this.structureScore = analysis.scoreDetail?.structureScore
                this.skillMatchScore = analysis.scoreDetail?.skillMatchScore
                this.expressionScore = analysis.scoreDetail?.expressionScore
                this.projectScore = analysis.scoreDetail?.projectScore
                this.summary = analysis.summary
                this.strengthsJson = objectMapper.writeValueAsString(analysis.strengths ?: emptyList<String>())
                this.suggestionsJson = objectMapper.writeValueAsString(analysis.suggestions ?: emptyList<ResumeAnalysisVo.SuggestionVo>())
            }

            val saved = analysisRepository.save(entity)
            log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}", saved.id, resume.id, analysis.overallScore)
            return saved
        } catch (e: Exception) {
            log.error("序列化评测结果失败: {}", e.message, e)
            throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败")
        }
    }

    /**
     * 获取简历的最新评测结果
     */
    fun getLatestAnalysis(resumeId: Long): ResumeAnalysisEntity? {
        return analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId)
    }

    /**
     * 获取简历的最新评测结果（返回 VO）
     */
    fun getLatestAnalysisAsVo(resumeId: Long): ResumeAnalysisVo? {
        return getLatestAnalysis(resumeId)?.let { entityToVo(it) }
    }

    /**
     * 获取所有简历列表
     */
    fun findAllResumes(): List<ResumeEntity> {
        return resumeRepository.findAll()
    }

    /**
     * 获取简历的所有评测记录
     */
    fun findAnalysesByResumeId(resumeId: Long): List<ResumeAnalysisEntity> {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId)
    }

    /**
     * 将实体转换为 VO
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

            val scoreDetail = ResumeAnalysisVo.ScoreDetailVo(
                entity.contentScore ?: 0,
                entity.structureScore ?: 0,
                entity.skillMatchScore ?: 0,
                entity.expressionScore ?: 0,
                entity.projectScore ?: 0
            )

            return ResumeAnalysisVo(
                entity.overallScore ?: 0,
                scoreDetail,
                entity.summary,
                strengths,
                suggestions,
                entity.resume?.resumeText
            )
        } catch (e: Exception) {
            log.error("反序列化评测结果失败: {}", e.message)
            throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败")
        }
    }

    /**
     * 根据ID获取简历
     */
    fun findById(id: Long): ResumeEntity? {
        return resumeRepository.findById(id).orElse(null)
    }

    /**
     * 删除简历及其所有关联数据
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteResume(id: Long) {
        val resume = resumeRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        val analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id)
        if (analyses.isNotEmpty()) {
            analysisRepository.deleteAll(analyses)
            log.info("已删除 {} 条简历分析记录", analyses.size)
        }

        resumeRepository.delete(resume)
        log.info("简历已删除: id={}, filename={}", id, resume.originalFilename)
    }
}

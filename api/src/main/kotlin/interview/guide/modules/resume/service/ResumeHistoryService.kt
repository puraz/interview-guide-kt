package interview.guide.modules.resume.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.export.PdfExportService
import interview.guide.infrastructure.mapper.InterviewMapper
import interview.guide.infrastructure.mapper.ResumeMapper
import interview.guide.modules.interview.model.InterviewHistoryItemVo
import interview.guide.modules.interview.model.ResumeAnalysisVo
import interview.guide.modules.interview.service.InterviewPersistenceService
import interview.guide.modules.resume.model.ResumeAnalysisEntity
import interview.guide.modules.resume.model.ResumeDetailVo
import interview.guide.modules.resume.model.ResumeListItemVo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 简历历史服务
 * 简历历史和导出简历分析报告
 */
@Service
class ResumeHistoryService(
    private val resumePersistenceService: ResumePersistenceService, // 简历持久化服务
    private val interviewPersistenceService: InterviewPersistenceService, // 面试持久化服务
    private val pdfExportService: PdfExportService, // PDF 导出服务
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val resumeMapper: ResumeMapper, // 简历对象映射
    private val interviewMapper: InterviewMapper // 面试对象映射
) {

    private val log = LoggerFactory.getLogger(ResumeHistoryService::class.java)

    /**
     * 获取所有简历列表
     *
     * @return 简历列表 // 含最新评分与面试次数
     */
    fun getAllResumes(): List<ResumeListItemVo> {
        val resumes = resumePersistenceService.findAllResumes()

        return resumes.map { resume ->
            // 获取最新分析结果的分数
            var latestScore: Int? = null
            var lastAnalyzedAt: LocalDateTime? = null
            val analysisOpt = resume.id?.let { resumePersistenceService.getLatestAnalysis(it) }
            if (analysisOpt != null) {
                latestScore = analysisOpt.overallScore
                lastAnalyzedAt = analysisOpt.analyzedAt
            }

            // 获取面试次数
            val interviewCount = resume.id?.let { interviewPersistenceService.findByResumeId(it).size } ?: 0

            // 使用 ResumeMapper 映射
            ResumeListItemVo(
                resume.id,
                resume.originalFilename,
                resume.fileSize,
                resume.uploadedAt,
                resume.accessCount,
                latestScore,
                lastAnalyzedAt,
                interviewCount,
                resume.analyzeStatus,
                resume.analyzeError
            )
        }
    }

    /**
     * 获取简历详情（包含分析历史）
     *
     * @param id 简历ID // 主键
     * @return 简历详情 // 含分析历史与面试历史
     */
    fun getResumeDetail(id: Long): ResumeDetailVo {
        val resume = resumePersistenceService.findById(id)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        // 获取所有分析记录，使用 ResumeMapper 批量转换
        val analyses = resumePersistenceService.findAnalysesByResumeId(id)
        val analysisHistory = resumeMapper.toAnalysisHistoryVoList(
            analyses,
            this::extractStrengths,
            this::extractSuggestions
        )

        // 使用 InterviewMapper 转换面试历史
        val interviewHistory: List<InterviewHistoryItemVo> = interviewMapper.toInterviewHistoryList(
            interviewPersistenceService.findByResumeId(id)
        )

        return ResumeDetailVo(
            resume.id,
            resume.originalFilename,
            resume.fileSize,
            resume.contentType,
            resume.storageUrl,
            resume.uploadedAt,
            resume.accessCount,
            resume.resumeText,
            resume.analyzeStatus,
            resume.analyzeError,
            analysisHistory,
            interviewHistory
        )
    }

    /**
     * 从 JSON 提取 strengths
     *
     * @param entity 分析实体 // 数据库记录
     * @return 优点列表 // 解析失败返回空列表
     */
    private fun extractStrengths(entity: ResumeAnalysisEntity): List<String> {
        try {
            val strengthsJson = entity.strengthsJson
            if (strengthsJson != null) {
                return objectMapper.readValue(strengthsJson, object : TypeReference<List<String>>() {})
            }
        } catch (e: JsonProcessingException) {
            log.error("解析 strengths JSON 失败", e)
        }
        return emptyList()
    }

    /**
     * 从 JSON 提取 suggestions
     *
     * @param entity 分析实体 // 数据库记录
     * @return 建议列表 // 解析失败返回空列表
     */
    private fun extractSuggestions(entity: ResumeAnalysisEntity): List<Any> {
        try {
            val suggestionsJson = entity.suggestionsJson
            if (suggestionsJson != null) {
                return objectMapper.readValue(suggestionsJson, object : TypeReference<List<Any>>() {})
            }
        } catch (e: JsonProcessingException) {
            log.error("解析 suggestions JSON 失败", e)
        }
        return emptyList()
    }

    /**
     * 导出简历分析报告为PDF
     *
     * @param resumeId 简历ID // 主键
     * @return 导出结果 // PDF 字节数组与文件名
     */
    fun exportAnalysisPdf(resumeId: Long): ExportResult {
        val resume = resumePersistenceService.findById(resumeId)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        val analysisOpt = resumePersistenceService.getLatestAnalysisAsVo(resumeId)
            ?: throw BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND)

        try {
            val pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysisOpt)
            val filename = "简历分析报告_" + resume.originalFilename + ".pdf"

            return ExportResult(pdfBytes, filename)
        } catch (e: Exception) {
            log.error("导出PDF失败: resumeId={}", resumeId, e)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: ${e.message}")
        }
    }

    /**
     * PDF导出结果
     */
    data class ExportResult(
        val pdfBytes: ByteArray, // PDF 字节数组
        val filename: String // 下载文件名
    )
}

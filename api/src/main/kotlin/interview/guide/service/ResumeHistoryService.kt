package interview.guide.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.ResumeAnalysisEntity
import interview.guide.infrastructure.export.PdfExportService
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
    private val objectMapper: ObjectMapper // JSON 序列化
) {

    private val log = LoggerFactory.getLogger(ResumeHistoryService::class.java)

    /**
     * 获取所有简历列表
     *
     * @return 简历列表 // 简历概要信息
     */
    fun getAllResumes(): List<ResumeListItemVo> {
        val resumes = resumePersistenceService.findAllResumes()

        return resumes.map { resume ->
            val analysis = resume.id?.let { resumePersistenceService.getLatestAnalysis(it) }
            val latestScore = analysis?.overallScore
            val lastAnalyzedAt = analysis?.analyzedAt
            val interviewCount = resume.id?.let { interviewPersistenceService.findByResumeId(it).size } ?: 0

            ResumeListItemVo(
                id = resume.id ?: 0L,
                filename = resume.originalFilename,
                fileSize = resume.fileSize,
                uploadedAt = resume.uploadedAt,
                accessCount = resume.accessCount,
                latestScore = latestScore,
                lastAnalyzedAt = lastAnalyzedAt,
                interviewCount = interviewCount
            )
        }
    }

    /**
     * 获取简历详情（包含分析历史）
     *
     * @param id 简历ID // 简历主键
     * @return 简历详情 // 简历信息与历史记录
     */
    fun getResumeDetail(id: Long): ResumeDetailVo {
        val resume = resumePersistenceService.findById(id)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        val analyses = resumePersistenceService.findAnalysesByResumeId(id)
        val analysisHistory = analyses.map { entity ->
            ResumeDetailVo.AnalysisHistoryVo(
                id = entity.id,
                overallScore = entity.overallScore,
                contentScore = entity.contentScore,
                structureScore = entity.structureScore,
                skillMatchScore = entity.skillMatchScore,
                expressionScore = entity.expressionScore,
                projectScore = entity.projectScore,
                summary = entity.summary,
                analyzedAt = entity.analyzedAt,
                strengths = extractStrengths(entity),
                suggestions = extractSuggestions(entity)
            )
        }

        val interviewHistory = resume.id?.let { buildInterviewHistory(it) } ?: emptyList()

        return ResumeDetailVo(
            id = resume.id ?: 0L,
            filename = resume.originalFilename,
            fileSize = resume.fileSize,
            contentType = resume.contentType,
            storageUrl = resume.storageUrl,
            uploadedAt = resume.uploadedAt,
            accessCount = resume.accessCount,
            resumeText = resume.resumeText,
            analyzeStatus = resume.analyzeStatus,
            analyzeError = resume.analyzeError,
            analyses = analysisHistory,
            interviews = interviewHistory
        )
    }

    /**
     * 导出简历分析报告为PDF
     *
     * @param resumeId 简历ID // 简历主键
     * @return 导出结果 // PDF 字节数组与文件名
     */
    fun exportAnalysisPdf(resumeId: Long): ExportResult {
        val resume = resumePersistenceService.findById(resumeId)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)
        val analysis = resumePersistenceService.getLatestAnalysisAsVo(resumeId)
            ?: throw BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND)

        try {
            val pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysis)
            val filename = "简历分析报告_${resume.originalFilename}.pdf"
            return ExportResult(pdfBytes, filename)
        } catch (e: Exception) {
            log.error("导出PDF失败: resumeId={}", resumeId, e)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: ${e.message}")
        }
    }

    /**
     * 构建面试历史列表
     */
    private fun buildInterviewHistory(resumeId: Long): List<Any> {
        val sessions = interviewPersistenceService.findByResumeId(resumeId)
        return sessions.map { session ->
            val map = linkedMapOf<String, Any?>(
                "id" to session.id,
                "sessionId" to session.sessionId,
                "totalQuestions" to session.totalQuestions,
                "status" to session.status?.name,
                "evaluateStatus" to session.evaluateStatus?.name,
                "evaluateError" to session.evaluateError,
                "overallScore" to session.overallScore,
                "createdAt" to session.createdAt,
                "completedAt" to session.completedAt
            )
            map as Any
        }
    }

    /**
     * 从 JSON 提取 strengths
     */
    private fun extractStrengths(entity: ResumeAnalysisEntity): List<String> {
        return try {
            if (entity.strengthsJson != null) {
                objectMapper.readValue(entity.strengthsJson, object : TypeReference<List<String>>() {})
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.error("解析 strengths JSON 失败", e)
            emptyList()
        }
    }

    /**
     * 从 JSON 提取 suggestions
     */
    private fun extractSuggestions(entity: ResumeAnalysisEntity): List<Any> {
        return try {
            if (entity.suggestionsJson != null) {
                objectMapper.readValue(entity.suggestionsJson, object : TypeReference<List<Any>>() {})
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.error("解析 suggestions JSON 失败", e)
            emptyList()
        }
    }

    /**
     * PDF导出结果
     */
    data class ExportResult(
        val pdfBytes: ByteArray, // PDF 字节数组
        val filename: String // 文件名
    )
}

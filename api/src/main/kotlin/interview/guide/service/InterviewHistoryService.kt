package interview.guide.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.InterviewAnswerEntity
import interview.guide.infrastructure.export.PdfExportService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Service
class InterviewHistoryService(
    private val interviewPersistenceService: InterviewPersistenceService, // 面试持久化服务
    private val pdfExportService: PdfExportService, // PDF 导出服务
    private val objectMapper: ObjectMapper // JSON 序列化
) {

    private val log = LoggerFactory.getLogger(InterviewHistoryService::class.java)

    /**
     * 获取面试会话详情
     *
     * @param sessionId 会话ID // 面试会话ID
     * @return 面试详情 // 会话详情数据
     */
    fun getInterviewDetail(sessionId: String): InterviewDetailVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)

        val questions = parseAnyList(session.questionsJson)
        val strengths = parseStringList(session.strengthsJson)
        val improvements = parseStringList(session.improvementsJson)
        val referenceAnswers = parseAnyList(session.referenceAnswersJson)

        val allQuestions = parseQuestionList(session.questionsJson)
        val answerList = buildAnswerDetailList(allQuestions, session.answers ?: emptyList())

        return InterviewDetailVo(
            id = session.id,
            sessionId = session.sessionId,
            totalQuestions = session.totalQuestions,
            status = session.status?.name,
            evaluateStatus = session.evaluateStatus?.name,
            evaluateError = session.evaluateError,
            overallScore = session.overallScore,
            overallFeedback = session.overallFeedback,
            createdAt = session.createdAt,
            completedAt = session.completedAt,
            questions = questions,
            strengths = strengths,
            improvements = improvements,
            referenceAnswers = referenceAnswers,
            answers = answerList
        )
    }

    /**
     * 构建答案详情列表（包含所有题目）
     */
    private fun buildAnswerDetailList(
        allQuestions: List<InterviewQuestionVo>?,
        answers: List<InterviewAnswerEntity>
    ): List<InterviewDetailVo.AnswerDetailVo> {
        if (allQuestions.isNullOrEmpty()) {
            return answers.map { answer ->
                InterviewDetailVo.AnswerDetailVo(
                    questionIndex = answer.questionIndex,
                    question = answer.question,
                    category = answer.category,
                    userAnswer = answer.userAnswer,
                    score = answer.score,
                    feedback = answer.feedback,
                    referenceAnswer = answer.referenceAnswer,
                    keyPoints = extractKeyPoints(answer),
                    answeredAt = answer.answeredAt
                )
            }
        }

        val answerMap = answers.associateBy { it.questionIndex }

        return allQuestions.map { question ->
            val answer = answerMap[question.questionIndex]
            if (answer != null) {
                InterviewDetailVo.AnswerDetailVo(
                    questionIndex = answer.questionIndex,
                    question = answer.question,
                    category = answer.category,
                    userAnswer = answer.userAnswer,
                    score = answer.score,
                    feedback = answer.feedback,
                    referenceAnswer = answer.referenceAnswer,
                    keyPoints = extractKeyPoints(answer),
                    answeredAt = answer.answeredAt
                )
            } else {
                InterviewDetailVo.AnswerDetailVo(
                    questionIndex = question.questionIndex,
                    question = question.question,
                    category = question.category,
                    userAnswer = null,
                    score = question.score ?: 0,
                    feedback = question.feedback,
                    referenceAnswer = null,
                    keyPoints = null,
                    answeredAt = null
                )
            }
        }
    }

    /**
     * 从 JSON 提取 keyPoints
     */
    private fun extractKeyPoints(answer: InterviewAnswerEntity): List<String>? {
        return parseStringList(answer.keyPointsJson)
    }

    private fun parseAnyList(json: String?): List<Any>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return try {
            objectMapper.readValue(json, object : TypeReference<List<Any>>() {})
        } catch (e: Exception) {
            log.error("解析 JSON 失败", e)
            null
        }
    }

    private fun parseStringList(json: String?): List<String>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            log.error("解析 JSON 失败", e)
            null
        }
    }

    private fun parseQuestionList(json: String?): List<InterviewQuestionVo>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return try {
            objectMapper.readValue(json, object : TypeReference<List<InterviewQuestionVo>>() {})
        } catch (e: Exception) {
            log.error("解析 JSON 失败", e)
            null
        }
    }

    /**
     * 导出面试报告为PDF
     */
    fun exportInterviewPdf(sessionId: String): ByteArray {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
        return try {
            pdfExportService.exportInterviewReport(session)
        } catch (e: Exception) {
            log.error("导出PDF失败: sessionId={}", sessionId, e)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: ${e.message}")
        }
    }
}

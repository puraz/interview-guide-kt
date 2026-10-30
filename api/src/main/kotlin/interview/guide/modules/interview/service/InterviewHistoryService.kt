package interview.guide.modules.interview.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.export.PdfExportService
import interview.guide.infrastructure.mapper.InterviewMapper
import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewDetailVo
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewSessionEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Service
class InterviewHistoryService(
    private val interviewPersistenceService: InterviewPersistenceService, // 面试持久化服务
    private val pdfExportService: PdfExportService, // PDF 导出服务
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val interviewMapper: InterviewMapper // 面试对象映射器
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewHistoryService::class.java)
    }

    /**
     * 获取面试会话详情
     */
    @Transactional(readOnly = true)
    fun getInterviewDetail(sessionId: String): InterviewDetailVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)

        // 解析JSON字段
        val questions = parseJson(session.questionsJson, object : TypeReference<List<Any>>() {})
        val strengths = parseJson(session.strengthsJson, object : TypeReference<List<String>>() {})
        val improvements = parseJson(session.improvementsJson, object : TypeReference<List<String>>() {})
        val referenceAnswers = parseJson(session.referenceAnswersJson, object : TypeReference<List<Any>>() {})

        // 解析所有题目（用于构建完整的答案列表）
        val allQuestions = parseJson(
            session.questionsJson,
            object : TypeReference<List<InterviewQuestionVo>>() {}
        )

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        val answerList = buildAnswerDetailList(
            allQuestions,
            session.answers
        )

        // 组装最终 DTO
        return interviewMapper.toDetailDTO(
            session,
            questions,
            strengths,
            improvements,
            referenceAnswers,
            answerList
        )
    }

    /**
     * 构建答案详情列表（包含所有题目）
     * 对于用户已回答的题目使用答案数据，对于未回答的题目构建空答案
     */
    private fun buildAnswerDetailList(
        allQuestions: List<InterviewQuestionVo>?,
        answers: List<InterviewAnswerEntity>
    ): List<InterviewDetailVo.AnswerDetailVo> {
        if (allQuestions.isNullOrEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers) { answer -> extractKeyPoints(answer) }
        }

        // 将答案按 questionIndex 索引
        val answerMap = HashMap<Int?, InterviewAnswerEntity>()
        for (a in answers) {
            // 如果有重复，取第一个
            answerMap.putIfAbsent(a.questionIndex, a)
        }

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.map { question ->
            val answer = answerMap[question.questionIndex]
            if (answer != null) {
                // 用户已回答，使用答案数据
                interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer))
            } else {
                // 用户未回答，构建空答案
                InterviewDetailVo.AnswerDetailVo(
                    questionIndex = question.questionIndex,
                    question = question.question,
                    category = question.category,
                    userAnswer = null, // userAnswer
                    score = question.score ?: 0, // score
                    feedback = question.feedback, // feedback
                    referenceAnswer = null, // referenceAnswer
                    keyPoints = null, // keyPoints
                    answeredAt = null // answeredAt
                )
            }
        }
    }

    /**
     * 从 JSON 提取 keyPoints
     */
    private fun extractKeyPoints(answer: InterviewAnswerEntity): List<String>? {
        return parseJson(answer.keyPointsJson, object : TypeReference<List<String>>() {})
    }

    /**
     * 通用 JSON 解析方法
     */
    private fun <T> parseJson(json: String?, typeRef: TypeReference<T>): T? {
        if (json == null) {
            return null
        }
        return try {
            objectMapper.readValue(json, typeRef)
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

        try {
            return pdfExportService.exportInterviewReport(session)
        } catch (e: Exception) {
            log.error("导出PDF失败: sessionId={}", sessionId, e)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: ${e.message}")
        }
    }
}

package com.example.business.service

import com.example.business.entity.InterviewAnswerEntity
import com.example.business.entity.InterviewSessionEntity
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.fasterxml.jackson.core.type.TypeReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 面试历史服务
 *
 * 提供面试详情查询与报告导出能力。
 */
@Service
class InterviewHistoryService(
    private val interviewPersistenceService: InterviewPersistenceService,
    private val pdfExportService: PdfExportService
) {
    private val logger = LoggerFactory.getLogger(InterviewHistoryService::class.java)

    /**
     * 获取面试会话详情
     *
     * @param sessionId 会话ID
     * @return 面试详情
     */
    fun getInterviewDetail(sessionId: String): InterviewDetailVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")

        val questions = parseJson(session.questionsJson, object : TypeReference<List<InterviewSessionService.InterviewQuestionVo>>() {})
        val strengths = parseJson(session.strengthsJson, object : TypeReference<List<String>>() {})
        val improvements = parseJson(session.improvementsJson, object : TypeReference<List<String>>() {})
        val referenceAnswers = parseJson(
            session.referenceAnswersJson,
            object : TypeReference<List<AnswerEvaluationService.InterviewReportVo.ReferenceAnswerVo>>() {}
        )

        val answers = interviewPersistenceService.findAnswersBySessionId(sessionId)
        val answerDetails = buildAnswerDetailList(questions, answers)

        return InterviewDetailVo(
            id = session.id, // 会话主键
            sessionId = session.sessionId, // 会话ID
            totalQuestions = session.totalQuestions, // 题目总数
            status = session.status?.name, // 会话状态
            evaluateStatus = session.evaluateStatus?.name, // 评估状态
            evaluateError = session.evaluateError, // 评估错误
            overallScore = session.overallScore, // 总分
            overallFeedback = session.overallFeedback, // 总体评价
            createdAt = session.createdAt, // 创建时间
            completedAt = session.completedAt, // 完成时间
            questions = questions, // 问题列表
            strengths = strengths, // 优势列表
            improvements = improvements, // 改进建议
            referenceAnswers = referenceAnswers, // 参考答案
            answers = answerDetails // 答案详情
        )
    }

    /**
     * 导出面试报告PDF
     *
     * @param sessionId 会话ID
     * @return PDF字节
     */
    fun exportInterviewPdf(sessionId: String): ByteArray {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        return pdfExportService.exportInterviewReport(session, interviewPersistenceService.findAnswersBySessionId(sessionId))
    }

    private fun buildAnswerDetailList(
        questions: List<InterviewSessionService.InterviewQuestionVo>?,
        answers: List<InterviewAnswerEntity>
    ): List<AnswerDetailVo> {
        if (questions.isNullOrEmpty()) {
            return answers.map { answer ->
                AnswerDetailVo(
                    questionIndex = answer.questionIndex, // 问题索引
                    question = answer.question, // 问题内容
                    category = answer.category, // 问题类别
                    userAnswer = answer.userAnswer, // 用户回答
                    score = answer.score, // 得分
                    feedback = answer.feedback, // 反馈
                    referenceAnswer = answer.referenceAnswer, // 参考答案
                    keyPoints = parseJson(answer.keyPointsJson, object : TypeReference<List<String>>() {}),
                    answeredAt = answer.answeredAt // 回答时间
                )
            }
        }

        val answerMap = answers.associateBy { it.questionIndex }
        return questions.map { question ->
            val answer = answerMap[question.questionIndex]
            if (answer != null) {
                AnswerDetailVo(
                    questionIndex = answer.questionIndex, // 问题索引
                    question = answer.question, // 问题内容
                    category = answer.category, // 问题类别
                    userAnswer = answer.userAnswer, // 用户回答
                    score = answer.score, // 得分
                    feedback = answer.feedback, // 反馈
                    referenceAnswer = answer.referenceAnswer, // 参考答案
                    keyPoints = parseJson(answer.keyPointsJson, object : TypeReference<List<String>>() {}),
                    answeredAt = answer.answeredAt // 回答时间
                )
            } else {
                AnswerDetailVo(
                    questionIndex = question.questionIndex, // 问题索引
                    question = question.question, // 问题内容
                    category = question.category, // 问题类别
                    userAnswer = null, // 用户回答
                    score = question.score ?: 0, // 得分
                    feedback = question.feedback, // 反馈
                    referenceAnswer = null, // 参考答案
                    keyPoints = null, // 关键点
                    answeredAt = null // 回答时间
                )
            }
        }
    }

    private fun <T> parseJson(json: String?, typeReference: TypeReference<T>): T? {
        if (json.isNullOrBlank()) {
            return null
        }
        return try {
            JSONUtil.parseObject(json, typeReference)
        } catch (ex: Exception) {
            logger.error("解析JSON失败", ex)
            null
        }
    }

    /**
     * 面试详情返回对象
     */
    data class InterviewDetailVo(
        val id: Long, // 会话主键
        val sessionId: String, // 会话ID
        val totalQuestions: Int?, // 题目总数
        val status: String?, // 会话状态
        val evaluateStatus: String?, // 评估状态
        val evaluateError: String?, // 评估错误
        val overallScore: Int?, // 总分
        val overallFeedback: String?, // 总体评价
        val createdAt: LocalDateTime, // 创建时间
        val completedAt: LocalDateTime?, // 完成时间
        val questions: List<InterviewSessionService.InterviewQuestionVo>?, // 问题列表
        val strengths: List<String>?, // 优势列表
        val improvements: List<String>?, // 改进建议
        val referenceAnswers: List<AnswerEvaluationService.InterviewReportVo.ReferenceAnswerVo>?, // 参考答案
        val answers: List<AnswerDetailVo> // 答案详情
    )

    /**
     * 答案详情返回对象
     */
    data class AnswerDetailVo(
        val questionIndex: Int?, // 问题索引
        val question: String?, // 问题内容
        val category: String?, // 问题类别
        val userAnswer: String?, // 用户回答
        val score: Int?, // 得分
        val feedback: String?, // 反馈
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String>?, // 关键点
        val answeredAt: LocalDateTime? // 回答时间
    )
}

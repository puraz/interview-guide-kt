package interview.guide.infrastructure.mapper

import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewDetailVo
import interview.guide.modules.interview.model.InterviewHistoryItemVo
import interview.guide.modules.interview.model.InterviewReportVo
import interview.guide.modules.interview.model.InterviewSessionEntity
import org.springframework.stereotype.Component

/**
 * 面试相关的对象映射器
 * 原 Java 版本使用 MapStruct 自动生成，Kotlin 版本改为手写映射 */
@Component
class InterviewMapper {

    // ========== QuestionEvaluation 映射 ==========

    /**
     * 将面试答案实体转换为问题评估详情
     */
    fun toQuestionEvaluation(entity: InterviewAnswerEntity): InterviewReportVo.QuestionEvaluationVo {
        return InterviewReportVo.QuestionEvaluationVo(
            questionIndex = nullIndexToZero(entity.questionIndex),
            question = entity.question ?: "",
            category = entity.category,
            userAnswer = entity.userAnswer,
            score = nullScoreToZero(entity.score),
            feedback = entity.feedback
        )
    }

    /**
     * 批量转换面试答案实体
     */
    fun toQuestionEvaluations(entities: List<InterviewAnswerEntity>): List<InterviewReportVo.QuestionEvaluationVo> {
        return entities.map { toQuestionEvaluation(it) }
    }

    // ========== AnswerDetailDTO 映射 ==========

    /**
     * InterviewAnswerEntity 转换为 AnswerDetailDTO
     * 注意：keyPoints 需要从 JSON 解析后传入
     */
    fun toAnswerDetailDTO(
        entity: InterviewAnswerEntity,
        keyPoints: List<String>?
    ): InterviewDetailVo.AnswerDetailVo {
        return InterviewDetailVo.AnswerDetailVo(
            questionIndex = entity.questionIndex,
            question = entity.question,
            category = entity.category,
            userAnswer = entity.userAnswer,
            score = entity.score,
            feedback = entity.feedback,
            referenceAnswer = entity.referenceAnswer,
            keyPoints = keyPoints,
            answeredAt = entity.answeredAt
        )
    }

    /**
     * 批量转换（需要在 Service 层处理 JSON）
     */
    fun toAnswerDetailDTOList(
        entities: List<InterviewAnswerEntity>,
        keyPointsExtractor: (InterviewAnswerEntity) -> List<String>?
    ): List<InterviewDetailVo.AnswerDetailVo> {
        return entities.map { toAnswerDetailDTO(it, keyPointsExtractor(it)) }
    }

    // ========== InterviewDetailDTO 映射 ==========

    /**
     * InterviewSessionEntity 转换为 InterviewDetailDTO
     * 注意：questions, strengths, improvements, referenceAnswers, answers 需要在 Service 层处理
     */
    fun toDetailDTO(
        session: InterviewSessionEntity,
        questions: List<Any>?,
        strengths: List<String>?,
        improvements: List<String>?,
        referenceAnswers: List<Any>?,
        answers: List<InterviewDetailVo.AnswerDetailVo>
    ): InterviewDetailVo {
        return InterviewDetailVo(
            id = session.id,
            sessionId = session.sessionId,
            totalQuestions = session.totalQuestions,
            status = session.status?.name,
            evaluateStatus = session.evaluateStatus?.name,
            evaluateError = session.evaluateError,
            overallScore = session.overallScore,
            sourceType = session.sourceType,
            knowledgeBaseId = session.knowledgeBaseId,
            overallFeedback = session.overallFeedback,
            createdAt = session.createdAt,
            completedAt = session.completedAt,
            questions = questions,
            strengths = strengths,
            improvements = improvements,
            referenceAnswers = referenceAnswers,
            answers = answers
        )
    }

    // ========== InterviewSessionEntity 更新映射 ==========

    /**
     * 从 InterviewReportDTO 更新 InterviewSessionEntity
     * 注意：JSON 字段需要在 Service 层单独设置
     */
    fun updateSessionFromReport(report: InterviewReportVo, session: InterviewSessionEntity) {
        session.overallScore = report.overallScore
        session.overallFeedback = report.overallFeedback
    }

    // ========== 面试历史列表项映射 ==========

    /**
     * InterviewSessionEntity 转换为面试历史列表项 DTO
     */
    fun toInterviewHistoryItem(session: InterviewSessionEntity): InterviewHistoryItemVo {
        return InterviewHistoryItemVo(
            id = session.id,
            sessionId = session.sessionId,
            totalQuestions = session.totalQuestions,
            status = session.status?.name,
            evaluateStatus = session.evaluateStatus?.name,
            evaluateError = session.evaluateError,
            overallScore = session.overallScore,
            createdAt = session.createdAt,
            completedAt = session.completedAt
        )
    }

    /**
     * 批量转换面试历史
     */
    fun toInterviewHistoryList(sessions: List<InterviewSessionEntity>): List<InterviewHistoryItemVo> {
        return sessions.map { toInterviewHistoryItem(it) }
    }

    // ========== 工具方法 ==========

    /**
     * 空索引转 0
     */
    fun nullIndexToZero(value: Int?): Int {
        return value ?: 0
    }

    /**
     * 空分数转 0
     */
    fun nullScoreToZero(value: Int?): Int {
        return value ?: 0
    }
}

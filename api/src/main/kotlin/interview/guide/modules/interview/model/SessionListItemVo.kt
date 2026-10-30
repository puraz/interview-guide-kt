package interview.guide.modules.interview.model

import interview.guide.common.model.AsyncTaskStatus
import java.time.LocalDateTime

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）
 */
data class SessionListItemVo(
    val sessionId: String? = null, // 会话ID
    val skillId: String? = null, // 面试主题
    val difficulty: String? = null, // 难度级别
    val resumeId: Long? = null, // 简历ID
    val totalQuestions: Int = 0, // 总题数
    val status: InterviewSessionEntity.SessionStatus? = null, // 会话状态
    val evaluateStatus: AsyncTaskStatus? = null, // 评估状态
    val evaluateError: String? = null, // 评估错误信息
    val overallScore: Int? = null, // 总分
    val sourceType: String? = null, // 会话来源
    val knowledgeBaseId: Long? = null, // 知识库面试来源知识库ID
    val interviewCategory: String? = null, // 知识库面试方向
    val createdAt: LocalDateTime? = null, // 创建时间
    val completedAt: LocalDateTime? = null // 完成时间
) {

    companion object {

        /**
         * 由会话实体构建列表项
         */
        fun from(e: InterviewSessionEntity): SessionListItemVo {
            return SessionListItemVo(
                sessionId = e.sessionId,
                skillId = e.skillId,
                difficulty = e.difficulty,
                resumeId = e.resumeId,
                totalQuestions = e.totalQuestions ?: 0,
                status = e.status,
                evaluateStatus = e.evaluateStatus,
                evaluateError = e.evaluateError,
                overallScore = e.overallScore,
                sourceType = e.sourceType,
                knowledgeBaseId = e.knowledgeBaseId,
                interviewCategory = e.interviewCategory,
                createdAt = e.createdAt,
                completedAt = e.completedAt
            )
        }
    }
}

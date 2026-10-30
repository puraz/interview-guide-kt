package interview.guide.modules.interview.model

import java.time.LocalDateTime

/**
 * 面试历史列表项 DTO（用于简历详情页展示关联面试记录）
 */
data class InterviewHistoryItemVo(
    val id: Long? = null, // 记录ID
    val sessionId: String? = null, // 会话ID
    val totalQuestions: Int? = null, // 总题数
    val status: String? = null, // 会话状态
    val evaluateStatus: String? = null, // 评估状态
    val evaluateError: String? = null, // 评估错误信息
    val overallScore: Int? = null, // 总分
    val createdAt: LocalDateTime? = null, // 创建时间
    val completedAt: LocalDateTime? = null // 完成时间
)

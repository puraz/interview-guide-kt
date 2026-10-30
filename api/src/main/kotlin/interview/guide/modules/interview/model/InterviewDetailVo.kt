package interview.guide.modules.interview.model

import java.time.LocalDateTime

/**
 * 面试详情DTO
 */
data class InterviewDetailVo(
    val id: Long? = null, // 记录ID
    val sessionId: String? = null, // 会话ID
    val totalQuestions: Int? = null, // 总题数
    val status: String? = null, // 会话状态
    val evaluateStatus: String? = null, // 评估状态
    val evaluateError: String? = null, // 评估错误信息
    val overallScore: Int? = null, // 总分
    val sourceType: String? = null, // 会话来源
    val knowledgeBaseId: Long? = null, // 知识库面试来源知识库ID
    val overallFeedback: String? = null, // 总体评价
    val createdAt: LocalDateTime? = null, // 创建时间
    val completedAt: LocalDateTime? = null, // 完成时间
    val questions: List<Any>? = null, // 题目列表
    val strengths: List<String>? = null, // 优势
    val improvements: List<String>? = null, // 改进建议
    val referenceAnswers: List<Any>? = null, // 参考答案
    val answers: List<AnswerDetailVo> = emptyList() // 答案详情
) {

    /**
     * 答案详情DTO
     */
    data class AnswerDetailVo(
        val questionIndex: Int? = null, // 题目索引
        val question: String? = null, // 问题内容
        val category: String? = null, // 问题类别
        val userAnswer: String? = null, // 用户回答
        val score: Int? = null, // 得分
        val feedback: String? = null, // 反馈
        val referenceAnswer: String? = null, // 参考答案
        val keyPoints: List<String>? = null, // 要点列表
        val answeredAt: LocalDateTime? = null // 回答时间
    )
}

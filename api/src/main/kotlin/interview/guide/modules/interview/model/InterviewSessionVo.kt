package interview.guide.modules.interview.model

/**
 * 面试会话DTO
 */
data class InterviewSessionVo(
    val sessionId: String = "", // 会话ID
    val resumeText: String = "", // 简历文本
    val totalQuestions: Int = 0, // 总题数
    val currentQuestionIndex: Int = 0, // 当前题索引
    val questions: List<InterviewQuestionVo> = emptyList(), // 问题列表
    val status: SessionStatus = SessionStatus.CREATED, // 会话状态
    val knowledgeBaseId: Long? = null, // 知识库面试来源知识库ID
    val interviewCategory: String? = null // 知识库面试方向
) {

    /**
     * 会话状态
     */
    enum class SessionStatus {
        CREATED, // 会话已创建
        IN_PROGRESS, // 面试进行中
        COMPLETED, // 面试已完成
        EVALUATED // 已生成评估报告
    }
}

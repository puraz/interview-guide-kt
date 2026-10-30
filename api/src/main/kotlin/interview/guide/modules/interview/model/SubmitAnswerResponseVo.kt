package interview.guide.modules.interview.model

/**
 * 提交答案响应
 */
data class SubmitAnswerResponseVo(
    val hasNextQuestion: Boolean = false, // 是否还有下一题
    val nextQuestion: InterviewQuestionVo? = null, // 下一题内容
    val currentIndex: Int = 0, // 当前问题索引
    val totalQuestions: Int = 0 // 总题数
)

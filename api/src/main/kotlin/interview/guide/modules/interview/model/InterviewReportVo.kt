package interview.guide.modules.interview.model

/**
 * 面试评估报告
 */
data class InterviewReportVo(
    val sessionId: String = "", // 会话ID
    val totalQuestions: Int = 0, // 题目总数
    val overallScore: Int = 0, // 总分 (0-100)
    val categoryScores: List<CategoryScoreVo> = emptyList(), // 各类别得分
    val questionDetails: List<QuestionEvaluationVo> = emptyList(), // 每题详情
    val overallFeedback: String = "", // 总体评价
    val strengths: List<String> = emptyList(), // 优势
    val improvements: List<String> = emptyList(), // 改进建议
    val referenceAnswers: List<ReferenceAnswerVo> = emptyList() // 参考答案
) {

    /**
     * 类别得分
     */
    data class CategoryScoreVo(
        val category: String? = null, // 类别
        val score: Int = 0, // 平均分
        val questionCount: Int = 0 // 题数
    )

    /**
     * 问题评估详情
     */
    data class QuestionEvaluationVo(
        val questionIndex: Int = 0, // 题目索引
        val question: String = "", // 问题内容
        val category: String? = null, // 问题类别
        val userAnswer: String? = null, // 用户回答
        val score: Int = 0, // 得分
        val feedback: String? = null // 反馈
    )

    /**
     * 参考答案
     */
    data class ReferenceAnswerVo(
        val questionIndex: Int = 0, // 题目索引
        val question: String = "", // 问题内容
        val referenceAnswer: String? = null, // 参考答案
        val keyPoints: List<String>? = null // 要点列表
    )
}

package interview.guide.modules.voiceinterview.dto

/**
 * Voice evaluation detail DTO — aligned with text-based interview InterviewDetailDTO.
 * This allows the frontend to reuse InterviewDetailPanel for rendering.
 * 语音面试评估详情（字段与文字面试评估详情对齐，便于前端复用渲染组件）
 */
data class VoiceEvaluationDetailVo(
    var sessionId: Long? = null, // 会话ID
    var totalQuestions: Int = 0, // 总题数
    var overallScore: Int? = null, // 总体评分
    var overallFeedback: String? = null, // 总体评价
    var strengths: List<String>? = null, // 优势
    var improvements: List<String>? = null, // 改进建议
    var answers: List<AnswerDetail>? = null // 逐题评估明细
) {
    /**
     * 单题评估明细
     */
    data class AnswerDetail(
        var questionIndex: Int = 0, // 题目索引（0-based）
        var question: String? = null, // 题目
        var category: String? = null, // 题目分类
        var userAnswer: String? = null, // 用户回答
        var score: Int = 0, // 单题得分
        var feedback: String? = null, // 单题反馈
        var referenceAnswer: String? = null, // 参考答案
        var keyPoints: List<String>? = null // 关键点
    )
}

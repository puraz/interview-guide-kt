package interview.guide.modules.knowledgebase.model

/**
 * 更新知识库题目请求
 */
data class UpdateKnowledgeBaseQuestionRequest(
    val difficulty: String?, // 难度
    val type: String?, // 题目类型
    val category: String?, // 面试方向
    val question: String?, // 题干
    val topicSummary: String?, // 知识点摘要
    val referenceAnswer: String?, // 参考答案
    val keyPoints: List<String>?, // 要点列表
    val scoringRubric: String?, // 评分标准
    val followUps: List<KnowledgeBaseQuestionFollowUpDTO>?, // 追问题
    val sourceContext: String?, // 来源上下文
    val status: KnowledgeBaseQuestionStatus? // 题目状态
)

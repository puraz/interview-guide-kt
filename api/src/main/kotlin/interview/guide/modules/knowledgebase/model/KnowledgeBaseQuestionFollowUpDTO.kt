package interview.guide.modules.knowledgebase.model

/**
 * 知识库题目追问题
 */
data class KnowledgeBaseQuestionFollowUpDTO(
    val question: String? = null, // 追问题干
    val referenceAnswer: String? = null, // 参考答案
    val keyPoints: List<String>? = null, // 要点列表
    val scoringRubric: String? = null // 评分标准
) {
    /**
     * 仅题干构造追问
     */
    constructor(question: String?) : this(question, null, emptyList(), null)
}

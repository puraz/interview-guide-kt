package interview.guide.modules.knowledgebase.model

/**
 * 知识库查询请求
 */
data class QueryRequest(
    @field:jakarta.validation.constraints.NotEmpty(message = "至少选择一个知识库")
    val knowledgeBaseIds: List<Long>, // 支持多个知识库

    @field:jakarta.validation.constraints.NotBlank(message = "问题不能为空")
    val question: String // 用户问题
) {
    /**
     * 兼容单知识库查询（向后兼容）
     */
    constructor(knowledgeBaseId: Long, question: String) : this(listOf(knowledgeBaseId), question)
}

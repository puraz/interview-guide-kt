package interview.guide.modules.knowledgebase.model

/**
 * 知识库查询响应
 */
data class QueryResponse(
    val answer: String?, // AI 回答
    val knowledgeBaseId: Long?, // 主知识库ID
    val knowledgeBaseName: String? // 知识库名称（多个用顿号分隔）
)

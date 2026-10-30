package interview.guide.modules.knowledgebase.model

/**
 * 知识库统计信息VO
 */
data class KnowledgeBaseStatsVo(
    val totalCount: Long, // 知识库总数
    val totalQuestionCount: Long, // 总提问次数
    val totalAccessCount: Long, // 总访问次数
    val completedCount: Long, // 已完成向量化数量
    val processingCount: Long // 处理中数量
)

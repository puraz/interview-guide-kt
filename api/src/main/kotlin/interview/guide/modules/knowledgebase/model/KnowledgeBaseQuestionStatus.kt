package interview.guide.modules.knowledgebase.model

/**
 * 知识库题目状态
 */
enum class KnowledgeBaseQuestionStatus {
    DRAFT, // 草稿
    ACTIVE, // 已启用
    ARCHIVED, // 已归档
    STALE // 已过期
}

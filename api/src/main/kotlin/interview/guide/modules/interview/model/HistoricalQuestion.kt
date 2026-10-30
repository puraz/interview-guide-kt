package interview.guide.modules.interview.model

/**
 * 历史面试题目摘要（用于出题去重）
 *
 * @param question      原始题面
 * @param type          Skill category key，如 "MYSQL"、"JAVA"
 * @param topicSummary  知识点摘要，如 "Redis RDB/AOF 持久化对比"；为 null 时由调用方截取 question 前若干字兜底
 */
data class HistoricalQuestion(
    val question: String = "", // 原始题面
    val type: String? = null, // Skill category key，如 "MYSQL"、"JAVA"
    val topicSummary: String? = null // 知识点摘要
)

package interview.guide.modules.knowledgebase.model

/**
 * 知识库题目生成参数快照。
 */
data class QuestionGenerationConfig(
    val difficulty: String?, // 难度
    val questionCount: Int, // 题目数量
    val followUpCount: Int, // 每题追问数量
    val categoryLimit: Int, // 方向数量上限
    val llmProvider: String? // LLM 提供商
)

package interview.guide.modules.knowledgebase.model

/**
 * 知识库面试在指定方向、难度和主问题数下的可用容量。
 */
data class KnowledgeBaseInterviewCapacityVo(
    val knowledgeBaseId: Long?, // 知识库ID
    val category: String?, // 面试方向
    val difficulty: String?, // 难度
    val mainQuestionCount: Int, // 主问题数量
    val categories: List<CategoryOption>, // 各方向可用题目数
    val followUpOptions: List<FollowUpOption> // 各追问数量的可选性
) {
    /**
     * 方向选项
     */
    data class CategoryOption(
        val category: String?, // 方向名称
        val availableQuestionCount: Int // 可用题目数
    )

    /**
     * 追问数量选项
     */
    data class FollowUpOption(
        val followUpCount: Int, // 追问数量
        val availableQuestionCount: Int, // 可用题目数
        val selectable: Boolean // 是否可选
    )
}

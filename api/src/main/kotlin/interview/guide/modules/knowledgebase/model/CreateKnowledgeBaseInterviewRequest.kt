package interview.guide.modules.knowledgebase.model

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * 创建知识库面试请求
 */
data class CreateKnowledgeBaseInterviewRequest(
    @field:NotNull(message = "知识库不能为空")
    val knowledgeBaseId: Long?, // 知识库ID

    // 面试方向，可空。为空表示覆盖知识库内所有方向的已启用题目。
    val category: String?,

    val difficulty: String?, // 难度

    @field:Min(value = 1, message = "主问题数量最少1题")
    @field:Max(value = 20, message = "主问题数量最多20题")
    val mainQuestionCount: Int, // 主问题数量

    @field:Min(value = 0, message = "追问数量不能小于0")
    @field:Max(value = 5, message = "每题追问最多5个")
    val followUpCount: Int, // 每题追问数量

    val llmProvider: String? // LLM 提供商
)

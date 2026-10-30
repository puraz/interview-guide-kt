package interview.guide.modules.knowledgebase.model

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 异步生成知识库题目请求
 */
data class GenerateKnowledgeBaseQuestionsRequest(
    @field:Pattern(regexp = "junior|mid|senior", message = "题目难度不合法")
    val difficulty: String?, // 难度

    @field:Min(value = 1, message = "题目数量最少1题")
    @field:Max(value = 30, message = "题目数量最多30题")
    val questionCount: Int, // 题目数量

    @field:Min(value = 0, message = "追问数量不能小于0")
    @field:Max(value = 5, message = "每题追问最多5个")
    val followUpCount: Int?, // 每题追问数量（空使用默认值）

    @field:Min(value = 1, message = "方向数最少1个")
    @field:Max(value = 5, message = "方向数最多5个")
    @field:NotNull(message = "方向数量不能为空")
    val categoryLimit: Int?, // 方向数量上限（空使用默认值）

    @field:Size(max = 64, message = "模型提供商标识过长")
    val llmProvider: String? // LLM 提供商
)

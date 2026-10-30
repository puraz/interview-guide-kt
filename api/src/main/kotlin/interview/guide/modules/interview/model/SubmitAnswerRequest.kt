package interview.guide.modules.interview.model

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 提交答案请求
 */
data class SubmitAnswerRequest(
    @field:NotBlank(message = "会话ID不能为空")
    val sessionId: String = "", // 会话ID

    @field:NotNull(message = "问题索引不能为空")
    @field:Min(value = 0, message = "问题索引无效")
    val questionIndex: Int? = null, // 问题索引

    @field:NotBlank(message = "答案不能为空")
    val answer: String = "" // 用户答案
)

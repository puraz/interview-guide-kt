package interview.guide.modules.interviewschedule.model

import jakarta.validation.constraints.NotBlank

/**
 * 面试邀约解析请求
 */
data class ParseRequest(
    @field:NotBlank(message = "文本不能为空")
    var rawText: String = "", // 待解析文本

    var source: String? = null // 来源平台：feishu, tencent, zoom, other
)

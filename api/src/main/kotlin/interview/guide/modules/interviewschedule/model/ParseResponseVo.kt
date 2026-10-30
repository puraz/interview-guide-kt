package interview.guide.modules.interviewschedule.model

/**
 * 面试邀约解析结果
 */
data class ParseResponseVo(
    var success: Boolean? = null, // 是否解析成功
    var data: CreateInterviewRequest? = null, // 解析出的面试信息
    var confidence: Double? = null, // 置信度
    var parseMethod: String? = null, // 解析方式：rule, ai
    var log: String? = null // 解析日志
)

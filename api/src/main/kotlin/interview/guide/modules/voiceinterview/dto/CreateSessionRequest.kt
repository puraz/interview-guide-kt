package interview.guide.modules.voiceinterview.dto

/**
 * 创建语音面试会话请求
 * 所有字段均可省略：服务层会用模板默认值与通用默认值填充
 */
data class CreateSessionRequest(
    var roleType: String? = null, // 向后兼容，新请求可不传（服务层会用 skillId 填充）
    var skillId: String? = null, // 模板 ID，如 "java-backend", "bytedance-backend" 等
    var difficulty: String? = null, // "junior", "mid", "senior"
    var customJdText: String? = null, // 自定义 JD 文本
    var resumeId: Long? = null, // 关联简历ID

    var introEnabled: Boolean? = false, // 是否启用自我介绍阶段
    var techEnabled: Boolean? = true, // 是否启用技术阶段
    var projectEnabled: Boolean? = true, // 是否启用项目阶段
    var hrEnabled: Boolean? = true, // 是否启用 HR 阶段
    var plannedDuration: Int? = 30, // 计划时长（分钟）

    var llmProvider: String? = null // 指定 LLM Provider；为空则使用默认 Provider
)

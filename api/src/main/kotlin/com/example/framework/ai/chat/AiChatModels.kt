package com.example.framework.ai.chat

/**
 * Chat 消息角色。
 */
enum class AiChatRole {
    SYSTEM, // 系统提示词：人格/规则/边界等 // 一般放在 messages 第一条
    USER, // 用户输入：本次问题或指令 // 与业务侧输入直接对应
    ASSISTANT // 助手历史：用于多轮对话上下文 // 流式或多轮时会用到
}

/**
 * 通用 Chat 消息结构。
 */
data class AiChatMessage(
    val role: AiChatRole, // 消息角色：system/user/assistant // 用于映射到具体模型SDK的消息类型
    val content: String, // 消息内容：纯文本提示词 // 业务层应保证不为空
)

/**
 * 通用 Chat 请求结构。
 *
 * 注意：此对象只表达“业务想要什么”，不表达“具体怎么调用某家模型”。 // 业务与模型实现解耦的关键
 */
data class AiChatRequest(
    val messages: List<AiChatMessage>, // 对话上下文：system + 历史 + user // 顺序即上下文顺序
    val model: String? = null, // 指定模型名（可选），例如 deepseek-reasoner // 业务若不关心可留空走默认
    val temperature: Double? = null, // 温度（可选），越高越发散 // 业务不传则由适配器使用默认值
    val maxTokens: Int? = null, // 最大输出 token（可选）// 业务不传则由适配器或模型默认控制
)

/**
 * 通用 Chat 调用结果。
 */
data class AiChatResult(
    val success: Boolean, // 是否成功：success=true 且 content 非空时可视为有效结果 // 业务层据此做兜底
    val content: String?, // 模型输出内容（已执行 stripCodeFences）// 业务层可直接落库/解析JSON
    val token: Long?, // 本次消耗 token（若模型支持返回）// 用于监控成本或埋点
    val durationMillis: Long?, // 本次调用耗时（毫秒）// 用于性能监控与告警
    val provider: String?, // 实际使用的模型提供方，如 deepseek/openai/qwen // 便于排查路由
    val resolvedModel: String?, // 实际使用的模型名（最终生效值）// 便于排查配置与灰度
    val errorMessage: String?, // 失败原因描述（可用于用户提示或日志）// 为空表示无错误
    val errorBody: String? = null, // 下游错误体（如 HTTP body），可能较长 // 仅用于排查，不建议直接返回前端
)

/**
 * 流式切片结构：每次回调增量内容，最终会回调 finished=true 的结束片段。
 */
data class AiChatStreamChunk(
    val contentDelta: String?, // 本次增量内容（可能为空）// 前端可直接追加渲染
    val fullContent: String?, // 当前累计的完整内容（适用于落库）// finished=true 时应为最终值
    val finished: Boolean, // 是否结束：true 表示本次流已完成或异常终止 // 业务层据此收尾会话
    val token: Long? = null, // 本次流式总 token（若模型支持返回，仅 finished=true 时可靠）// 用于扣费与监控
)

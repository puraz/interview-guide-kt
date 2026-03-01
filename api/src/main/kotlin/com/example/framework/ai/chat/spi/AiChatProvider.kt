package com.example.framework.ai.chat.spi

import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatResult
import com.example.framework.ai.chat.AiChatStreamChunk

/**
 * 模型提供方适配器 SPI（仅框架内部使用，不建议业务层直接依赖）。
 *
 * 说明：
 * - 每个提供方（DeepSeek/Qwen/OpenAI）实现一个 AiChatProvider // 插件式扩展点
 * - AiChatRouter 根据配置选择对应 provider 来执行 // 支持无侵入式新增模型
 */
interface AiChatProvider {

    /**
     * 提供方标识，例如：deepseek/openai/qwen。
     */
    val providerName: String // 用于路由匹配与日志标记

    /**
     * 非流式对话调用。
     */
    fun chat(request: AiChatRequest): AiChatResult

    /**
     * 流式对话调用。
     *
     * 若某提供方暂不支持流式，可在实现中抛出业务异常或以非流式降级再按整段回调一次 finished=true。 // 由提供方自行决定策略
     */
    fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit)
}


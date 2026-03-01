package com.example.framework.ai.chat.client

import com.example.framework.ai.chat.AiChatClient
import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatResult
import com.example.framework.ai.chat.AiChatStreamChunk
import com.example.framework.ai.chat.adapter.deepseek.DeepSeekLangChain4jChatProvider
import org.springframework.stereotype.Service

/**
 * DeepSeek 专用 AI Chat 客户端（具名注入）。
 *
 * 说明：
 * - 用于需要固定 DeepSeek 模型的业务场景，避免受全局路由影响。
 */
@Service("deepseekAiChatClient")
class DeepSeekAiChatClient(
    private val provider: DeepSeekLangChain4jChatProvider, // DeepSeek LangChain4j Provider
) : AiChatClient {

    override fun chat(request: AiChatRequest): AiChatResult {
        // 直接委托给 DeepSeek Provider，确保模型来源固定
        return provider.chat(request)
    }

    override fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit) {
        // 流式同样走 DeepSeek Provider，避免路由漂移
        provider.streamChat(request, chunkConsumer)
    }
}

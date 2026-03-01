package com.example.framework.ai.chat.client

import com.example.framework.ai.chat.AiChatClient
import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatResult
import com.example.framework.ai.chat.AiChatStreamChunk
import com.example.framework.ai.chat.adapter.textgeneration.TextGenerationLangChain4jChatProvider
import org.springframework.stereotype.Service

/**
 * Cook 专用 AI Chat 客户端（仅供做饭相关业务注入）。//
 *
 * 设计原因：//
 * - 全局 AiChatClient（AiChatRouter）通过 ai.chat.provider 进行“全局路由”；但“做饭”场景需要固定使用特定模型网关与模型名//
 * - 因此这里提供一个具名 Bean（cookAiChatClient），让 Cook*Service 可以独立选择“文本生成”模型，不影响其他业务模块//
 */
@Service("cookAiChatClient")
class CookAiChatClient(
    private val provider: TextGenerationLangChain4jChatProvider, // 文本生成 provider：OpenAI 兼容网关 + doubao 等模型//
) : AiChatClient {

    override fun chat(request: AiChatRequest): AiChatResult {
        // 直接委托给文本生成 provider：保证 Cook 场景不受全局路由影响//
        return provider.chat(request)
    }

    override fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit) {
        // 直接委托给文本生成 provider：流式同样保持与 Cook 场景绑定//
        provider.streamChat(request, chunkConsumer)
    }
}


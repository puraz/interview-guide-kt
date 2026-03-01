package com.example.framework.ai.chat

import com.example.framework.ai.chat.spi.AiChatProvider
import com.example.framework.core.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

/**
 * AI Chat 路由器：业务层注入 AiChatClient 时默认拿到此实现（@Primary）。
 *
 * 设计要点：
 * - 通过配置 ai.chat.provider 选择具体 provider // 支持灰度/切换模型
 * - 通过 Spring 注入 List<AiChatProvider> 自动收集适配器 // 无侵入扩展
 */
@Primary
@Service
class AiChatRouter(
    private val routingProperties: AiChatRoutingProperties, // 路由配置：决定用哪个 provider // 运维可通过配置切换
    private val providers: List<AiChatProvider>, // 已注册的 provider 列表 // 新增 provider 只需新增实现类
) : AiChatClient {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun chat(request: AiChatRequest): AiChatResult {
        val provider = resolveProvider()
        return provider.chat(request)
    }

    override fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit) {
        val provider = resolveProvider()
        provider.streamChat(request, chunkConsumer)
    }

    private fun resolveProvider(): AiChatProvider {
        val expected = routingProperties.provider.trim().lowercase()
        val provider = providers.firstOrNull { it.providerName.trim().lowercase() == expected }
        if (provider == null) {
            logger.error(
                "AI Chat provider 未找到, expected={}, available={}",
                expected,
                providers.map { it.providerName }
            )
            throw BusinessException("AI 服务配置错误：未找到可用的模型提供方")
        }
        return provider
    }
}


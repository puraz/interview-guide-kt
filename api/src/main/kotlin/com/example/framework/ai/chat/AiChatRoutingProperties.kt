package com.example.framework.ai.chat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 大模型路由配置。
 */
@ConfigurationProperties(prefix = "ai.chat")
data class AiChatRoutingProperties(
    val provider: String = "deepseek", // 默认使用 deepseek，后续可无侵入切换为 openai/qwen // 业务层无需改代码
)


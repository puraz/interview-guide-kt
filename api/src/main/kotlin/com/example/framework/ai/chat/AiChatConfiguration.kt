package com.example.framework.ai.chat

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * AI Chat 相关配置装配入口。
 */
@Configuration
@EnableConfigurationProperties(AiChatRoutingProperties::class)
class AiChatConfiguration


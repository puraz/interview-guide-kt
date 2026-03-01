package com.example.framework.ai.chat.adapter.textgeneration

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 文本生成（OpenAI 兼容）LangChain4j 适配器配置装配入口。//
 *
 * 说明：//
 * - 该适配器用于“做饭/菜谱”相关场景的文本生成（如菜谱 JSON）//
 * - 下游网关使用 OpenAI 兼容协议（如 https://api.302ai.cn/v1/），因此可直接复用 LangChain4j 的 OpenAiChatModel//
 */
@Configuration
@EnableConfigurationProperties(TextGenerationLangChain4jProperties::class)
class TextGenerationLangChain4jConfiguration


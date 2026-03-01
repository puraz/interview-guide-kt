package com.example.framework.ai.chat.adapter.deepseek

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * DeepSeek LangChain4j 适配器配置装配入口。
 */
@Configuration
@EnableConfigurationProperties(DeepSeekLangChain4jProperties::class)
class DeepSeekLangChain4jConfiguration


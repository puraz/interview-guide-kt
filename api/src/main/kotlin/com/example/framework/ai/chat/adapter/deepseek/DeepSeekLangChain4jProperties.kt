package com.example.framework.ai.chat.adapter.deepseek

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * DeepSeek（OpenAI 兼容）在 LangChain4j 下的调用配置。
 */
@ConfigurationProperties(prefix = "ai.chat.deepseek")
data class DeepSeekLangChain4jProperties(
    val baseUrl: String = "https://api.deepseek.com", // DeepSeek OpenAI 兼容网关地址（不带 /chat/completions）// 便于私有化或代理
    val defaultModel: String = "deepseek-chat", // 默认模型名（业务不指定 model 时生效）// 便于统一切换
    val defaultTemperature: Double = 1.0, // 默认温度（业务不指定 temperature 时生效）// 与旧 WorkflowService 一致
    val defaultMaxTokens: Int? = null, // 默认最大输出 token（业务不指定 maxTokens 时生效）// 为空表示交给模型侧默认策略，兼容旧实现
    val timeout: Duration = Duration.ofSeconds(60 * 8), // HTTP 超时时间（覆盖连接/读写）// 避免长时间占用线程
)

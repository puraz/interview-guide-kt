package com.example.framework.ai.embedding

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Embedding 模型配置
 */
@ConfigurationProperties(prefix = "ai.embedding.openai")
data class OpenAiEmbeddingProperties(
    val baseUrl: String = "https://api.deepseek.com", // OpenAI 兼容接口地址
    val apiKey: String = "", // Embedding ApiKey
    val model: String = "text-embedding-3-small", // Embedding 模型名称
    val timeout: Duration = Duration.ofSeconds(120), // Embedding 超时时间
    val maxRetries: Int = 2, // Embedding 重试次数
    val dimensions: Int = 1536 // 向量维度
)

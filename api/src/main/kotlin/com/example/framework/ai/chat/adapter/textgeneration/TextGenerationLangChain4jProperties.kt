package com.example.framework.ai.chat.adapter.textgeneration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 文本生成（OpenAI 兼容）在 LangChain4j 下的调用配置。//
 *
 * 配置示例（建议通过环境变量注入 ApiKey，避免把密钥写入仓库）：//
 *
 * ai:
 *   chat:
 *     text-generation:
 *       base-url: https://api.302ai.cn/v1/ // OpenAI 兼容网关地址（以 /v1/ 结尾更直观）//
 *       api-key: ${TEXT_GENERATION_API_KEY:} // 文本生成 ApiKey（不要提交到 git）//
 *       default-model: doubao-1.5-pro-32k // 默认模型//
 *       default-temperature: 0.7 // 默认温度//
 *       timeout: 300s // 超时//
 */
@ConfigurationProperties(prefix = "ai.chat.text-generation")
data class TextGenerationLangChain4jProperties(
    val baseUrl: String = "https://api.302ai.cn/v1/", // OpenAI 兼容网关地址（不带 /chat/completions）// 支持代理/私有化
    val apiKey: String = "", // 文本生成 ApiKey（强烈建议通过环境变量注入）// 为空会导致调用失败
    val defaultModel: String = "doubao-1.5-pro-32k", // 默认模型名（业务不指定 model 时生效）// 便于统一切换
    val defaultTemperature: Double = 0.7, // 默认温度（业务不指定 temperature 时生效）// 与前端配置保持一致
    val defaultMaxTokens: Int? = null, // 默认最大输出 token（业务不指定 maxTokens 时生效）// 为空表示交给模型默认策略
    val timeout: Duration = Duration.ofMillis(300_000L), // HTTP 超时时间（覆盖连接/读写）// 与前端 timeout=300000ms 对齐
)


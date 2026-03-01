package com.example.framework.ai.image

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 图片生成配置（智谱 CogView OpenAPI）。//
 *
 * 对应前端配置字段：//
 * - imageGenerationBaseUrl -> baseUrl//
 * - imageGenerationApiKey  -> apiKey//
 * - imageGenerationModel   -> model//
 *
 * 注意：//
 * - apiKey 不要提交到 git，建议通过环境变量注入（IMAGE_GENERATION_API_KEY）//
 */
@ConfigurationProperties(prefix = "ai.image-generation")
data class AiImageGenerationProperties(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/images/generations", // 图片生成接口地址（完整 endpoint）//
    val apiKey: String = "", // 图片生成 ApiKey（建议通过环境变量注入）// 为空会导致调用失败
    val model: String = "cogview-3-flash", // 图片生成模型名（默认：cogview-3-flash）//
    val timeout: Duration = Duration.ofMillis(300_000L), // HTTP 超时时间（毫秒）// 与前端 300000ms 对齐
)


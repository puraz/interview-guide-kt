package com.example.framework.ai.deepseek

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * DeepSeek 接入配置
 */
@ConfigurationProperties(prefix = "deepseek")
data class DeepSeekProperties(
    /**
     * API Key 列表，支持并发时轮询使用
     */
    val apiKeys: List<String?> = emptyList()
)

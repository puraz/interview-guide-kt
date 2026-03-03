package com.example.framework.ai.embedding

import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Embedding 模型装配
 */
@Configuration
@EnableConfigurationProperties(OpenAiEmbeddingProperties::class)
class OpenAiEmbeddingConfiguration {

    /**
     * 构建 OpenAI 兼容 EmbeddingModel
     *
     * @param properties Embedding 配置
     * @return EmbeddingModel 实例
     */
    @Bean
    fun openAiEmbeddingModel(properties: OpenAiEmbeddingProperties): EmbeddingModel {
        return OpenAiEmbeddingModel.builder()
            .baseUrl(properties.baseUrl) // OpenAI 兼容入口
            .apiKey(properties.apiKey) // Embedding ApiKey
            .modelName(properties.model) // Embedding 模型名
            .timeout(properties.timeout) // 超时时间
            .maxRetries(properties.maxRetries) // 重试次数
            .build()
    }
}

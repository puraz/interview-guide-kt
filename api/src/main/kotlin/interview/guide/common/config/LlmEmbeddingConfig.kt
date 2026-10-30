package interview.guide.common.config

import interview.guide.common.ai.LlmProviderRegistry
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * EmbeddingModel 委托配置：所有向量化调用都转发给默认 Embedding Provider。
 */
@Configuration
class LlmEmbeddingConfig {

    private val log = LoggerFactory.getLogger(LlmEmbeddingConfig::class.java)

    /**
     * 注册 EmbeddingModel Bean，内部委托给 LlmProviderRegistry 的默认向量模型
     *
     * @param registry Provider 注册表 // 负责动态创建/缓存 EmbeddingModel
     * @return EmbeddingModel // 默认向量模型代理
     */
    @Bean
    fun embeddingModel(registry: LlmProviderRegistry): EmbeddingModel {
        log.info("EmbeddingModel bean initialized as registry delegate")
        return object : EmbeddingModel {
            override fun call(request: EmbeddingRequest): EmbeddingResponse {
                return registry.getDefaultEmbeddingModel().call(request)
            }

            override fun embed(document: Document): FloatArray {
                return registry.getDefaultEmbeddingModel().embed(document)
            }
        }
    }
}

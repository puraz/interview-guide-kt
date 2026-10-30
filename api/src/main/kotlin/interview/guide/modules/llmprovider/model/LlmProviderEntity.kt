package interview.guide.modules.llmprovider.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * LLM Provider 配置实体（表 llm_provider_config）
 */
@Entity
@Table(name = "llm_provider_config")
open class LlmProviderEntity {

    @Id
    @Column(length = 64)
    var id: String? = null // Provider 唯一标识

    @Column(name = "base_url", nullable = false, length = 512)
    var baseUrl: String? = null // 服务基础地址

    @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
    var apiKeyCiphertext: String? = null // API Key 密文（Base64）

    @Column(name = "api_key_nonce", nullable = false, length = 64)
    var apiKeyNonce: String? = null // 加密 nonce（Base64）

    @Column(nullable = false, length = 128)
    var model: String? = null // 对话模型名称

    @Column(name = "embedding_model", length = 128)
    var embeddingModel: String? = null // 向量模型名称

    @Column(name = "embedding_dimensions")
    var embeddingDimensions: Int? = null // 向量维度

    @Column(name = "supports_embedding", nullable = false)
    var supportsEmbedding: Boolean = false // 是否支持 Embedding

    var temperature: Double? = null // 采样温度

    @Column(nullable = false)
    var enabled: Boolean = false // 是否启用

    @Column(nullable = false)
    var builtin: Boolean = false // 是否为内置（种子）Provider

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null // 更新时间

    @PrePersist
    fun prePersist() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

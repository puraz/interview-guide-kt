package interview.guide.modules.llmprovider.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * LLM 全局设置实体（表 llm_global_setting，单行记录）
 */
@Entity
@Table(name = "llm_global_setting")
open class LlmGlobalSettingEntity {

    companion object {
        const val SINGLETON_ID: Long = 1L // 单例记录主键
    }

    @Id
    var id: Long? = null // 主键（固定为 SINGLETON_ID）

    @Column(name = "default_chat_provider_id", nullable = false, length = 64)
    var defaultChatProviderId: String? = null // 默认对话 Provider ID

    @Column(name = "default_embedding_provider_id", nullable = false, length = 64)
    var defaultEmbeddingProviderId: String? = null // 默认向量 Provider ID

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

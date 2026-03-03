package interview.guide.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * RAG 聊天会话实体
 * 一个会话可以关联多个知识库，包含多条消息
 */
@Entity
@Table(name = "rag_chat_sessions", indexes = [Index(name = "idx_rag_session_updated", columnList = "updatedAt")])
open class RagChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 会话ID

    @Column(nullable = false)
    var title: String? = null // 会话标题

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var status: SessionStatus = SessionStatus.ACTIVE // 会话状态

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumns = [JoinColumn(name = "session_id")],
        inverseJoinColumns = [JoinColumn(name = "knowledge_base_id")]
    )
    var knowledgeBases: MutableSet<KnowledgeBaseEntity> = hashSetOf() // 关联知识库

    @OneToMany(mappedBy = "session", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    var messages: MutableList<RagChatMessageEntity> = mutableListOf() // 消息列表

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    var updatedAt: LocalDateTime? = null // 更新时间

    var messageCount: Int? = 0 // 消息数量

    @Column(columnDefinition = "boolean default false")
    var isPinned: Boolean? = false // 是否置顶

    enum class SessionStatus {
        ACTIVE, // 活跃会话
        ARCHIVED // 已归档
    }

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    @PostLoad
    fun onLoad() {
        if (isPinned == null) {
            isPinned = false
        }
    }

    /**
     * 便捷方法：添加消息
     */
    fun addMessage(message: RagChatMessageEntity) {
        messages.add(message)
        message.session = this
        messageCount = messages.size
        updatedAt = LocalDateTime.now()
    }

    /**
     * 便捷方法：获取知识库ID列表
     */
    fun getKnowledgeBaseIds(): List<Long> {
        return knowledgeBases.mapNotNull { it.id }
    }
}

package interview.guide.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * RAG 聊天消息实体
 * 存储用户问题和 AI 回答
 */
@Entity
@Table(
    name = "rag_chat_messages",
    indexes = [
        Index(name = "idx_rag_message_session", columnList = "session_id"),
        Index(name = "idx_rag_message_order", columnList = "session_id,messageOrder")
    ]
)
open class RagChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 消息ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: RagChatSessionEntity? = null // 会话

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: MessageType? = null // 消息类型

    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String? = null // 消息内容

    @Column(nullable = false)
    var messageOrder: Int? = null // 消息顺序

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    var updatedAt: LocalDateTime? = null // 更新时间

    var completed: Boolean? = true // 是否完成

    enum class MessageType {
        USER, // 用户消息
        ASSISTANT // AI 回答
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

    /**
     * 获取类型字符串（小写，用于前端）
     */
    fun getTypeString(): String {
        return type?.name?.lowercase() ?: "unknown"
    }
}

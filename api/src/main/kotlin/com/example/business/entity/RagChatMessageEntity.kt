package com.example.business.entity

import com.example.framework.base.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * RAG聊天消息实体
 */
@Entity
@Table(name = "rag_chat_messages")
open class RagChatMessageEntity : BaseEntity() {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 关联的会话
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    open lateinit var session: RagChatSessionEntity

    /**
     * 消息类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    open lateinit var type: MessageType

    /**
     * 消息内容
     */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    open lateinit var content: String

    /**
     * 消息顺序(用于排序)
     */
    @Column(name = "message_order", nullable = false)
    open var messageOrder: Int = 0

    /**
     * 是否完成(流式响应时使用)
     */
    @Column(name = "completed")
    open var completed: Boolean? = null

    /**
     * 消息类型枚举
     */
    enum class MessageType {
        USER, // 用户消息
        ASSISTANT // AI回答
    }
}

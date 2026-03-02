package com.example.business.entity

import com.example.framework.base.entity.BaseEntity
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinColumn
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.Table

/**
 * RAG聊天消息实体
 */
@Entity
@Table(name = "rag_chat_messages")
interface RagChatMessageEntity : BaseEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 关联的会话
     */
    @ManyToOne
    @JoinColumn(name = "session_id")
    val session: RagChatSessionEntity

    /**
     * 消息类型
     */
    @Column(name = "type")
    val type: MessageType

    /**
     * 消息内容
     */
    @Column(name = "content", sqlType = "TEXT")
    val content: String

    /**
     * 消息顺序(用于排序)
     */
    @Column(name = "message_order")
    val messageOrder: Int

    /**
     * 是否完成(流式响应时使用)
     */
    @Column(name = "completed")
    val completed: Boolean?

    /**
     * 消息类型枚举
     */
    enum class MessageType {
        USER, // 用户消息
        ASSISTANT // AI回答
    }
}

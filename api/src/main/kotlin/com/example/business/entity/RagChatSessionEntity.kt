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
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table

/**
 * RAG聊天会话实体
 */
@Entity
@Table(name = "rag_chat_sessions")
open class RagChatSessionEntity : BaseEntity() {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 会话标题(可自动生成或用户自定义)
     */
    @Column(name = "title", nullable = false)
    open lateinit var title: String

    /**
     * 会话状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    open var status: SessionStatus? = null

    /**
     * 会话关联的知识库
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumns = [JoinColumn(name = "session_id")],
        inverseJoinColumns = [JoinColumn(name = "knowledge_base_id")]
    )
    open var knowledgeBases: MutableList<KnowledgeBaseEntity> = mutableListOf()

    /**
     * 会话消息列表
     */
    @OneToMany(mappedBy = "session", fetch = FetchType.LAZY)
    @OrderBy("messageOrder ASC")
    open var messages: MutableList<RagChatMessageEntity> = mutableListOf()

    /**
     * 消息数量(冗余字段，方便查询)
     */
    @Column(name = "message_count")
    open var messageCount: Int? = null

    /**
     * 是否置顶
     */
    @Column(name = "is_pinned")
    open var isPinned: Boolean? = null

    /**
     * 会话状态枚举
     */
    enum class SessionStatus {
        ACTIVE, // 活跃会话
        ARCHIVED // 已归档
    }
}

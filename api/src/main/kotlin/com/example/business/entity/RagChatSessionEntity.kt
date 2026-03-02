package com.example.business.entity

import com.example.framework.base.entity.BaseEntity
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinTable
import org.babyfish.jimmer.sql.ManyToMany
import org.babyfish.jimmer.sql.OneToMany
import org.babyfish.jimmer.sql.OrderedProp
import org.babyfish.jimmer.sql.Table

/**
 * RAG聊天会话实体
 */
@Entity
@Table(name = "rag_chat_sessions")
interface RagChatSessionEntity : BaseEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 会话标题(可自动生成或用户自定义)
     */
    @Column(name = "title")
    val title: String

    /**
     * 会话状态
     */
    @Column(name = "status")
    val status: SessionStatus?

    /**
     * 会话关联的知识库
     */
    @ManyToMany
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumnName = "session_id",
        inverseJoinColumnName = "knowledge_base_id"
    )
    val knowledgeBases: List<KnowledgeBaseEntity>

    /**
     * 会话消息列表
     */
    @OneToMany(
        mappedBy = "session",
        orderedProps = [OrderedProp("messageOrder")]
    )
    val messages: List<RagChatMessageEntity>

    /**
     * 消息数量(冗余字段，方便查询)
     */
    @Column(name = "message_count")
    val messageCount: Int?

    /**
     * 是否置顶
     */
    @Column(name = "is_pinned")
    val isPinned: Boolean?

    /**
     * 会话状态枚举
     */
    enum class SessionStatus {
        ACTIVE, // 活跃会话
        ARCHIVED // 已归档
    }
}

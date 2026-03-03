package interview.guide.repository

import interview.guide.entity.RagChatSessionEntity
import interview.guide.entity.RagChatSessionEntity.SessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * RAG聊天会话Repository
 */
@Repository
interface RagChatSessionRepository : JpaRepository<RagChatSessionEntity, Long> {

    /**
     * 按更新时间倒序获取所有活跃会话
     */
    fun findByStatusOrderByUpdatedAtDesc(status: SessionStatus): List<RagChatSessionEntity>

    /**
     * 获取所有会话（按更新时间倒序）
     */
    fun findAllByOrderByUpdatedAtDesc(): List<RagChatSessionEntity>

    /**
     * 获取所有会话（按置顶状态和更新时间排序）
     */
    @Query("SELECT s FROM RagChatSessionEntity s ORDER BY s.isPinned DESC, s.updatedAt DESC")
    fun findAllOrderByPinnedAndUpdatedAtDesc(): List<RagChatSessionEntity>

    /**
     * 根据知识库ID查找相关会话
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb WHERE kb.id IN :kbIds ORDER BY s.updatedAt DESC")
    fun findByKnowledgeBaseIds(@Param("kbIds") knowledgeBaseIds: List<Long>): List<RagChatSessionEntity>

    /**
     * 获取会话详情（带消息列表和知识库）
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    fun findByIdWithMessagesAndKnowledgeBases(@Param("id") id: Long): RagChatSessionEntity?

    /**
     * 获取会话（带知识库，不带消息）
     */
    @Query("SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    fun findByIdWithKnowledgeBases(@Param("id") id: Long): RagChatSessionEntity?
}

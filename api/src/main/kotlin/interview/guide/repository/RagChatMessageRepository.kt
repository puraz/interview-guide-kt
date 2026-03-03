package interview.guide.repository

import interview.guide.entity.RagChatMessageEntity
import interview.guide.entity.RagChatMessageEntity.MessageType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * RAG聊天消息Repository
 */
@Repository
interface RagChatMessageRepository : JpaRepository<RagChatMessageEntity, Long> {

    /**
     * 获取会话的所有消息（按顺序）
     */
    fun findBySessionIdOrderByMessageOrderAsc(sessionId: Long): List<RagChatMessageEntity>

    /**
     * 获取会话的最后一条消息
     */
    fun findTopBySessionIdOrderByMessageOrderDesc(sessionId: Long): RagChatMessageEntity?

    /**
     * 获取会话消息数量
     */
    @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    fun countBySessionId(@Param("sessionId") sessionId: Long): Int

    /**
     * 查找未完成的消息（流式响应中断时清理用）
     */
    fun findBySessionIdAndCompletedFalse(sessionId: Long): List<RagChatMessageEntity>

    /**
     * 删除会话的所有消息
     */
    fun deleteBySessionId(sessionId: Long)

    /**
     * 统计所有用户消息数（即总提问次数）
     */
    fun countByType(type: MessageType): Long
}

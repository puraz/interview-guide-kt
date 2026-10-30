package interview.guide.modules.knowledgebase.repository

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

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
    fun findTopBySessionIdOrderByMessageOrderDesc(sessionId: Long): Optional<RagChatMessageEntity>

    /**
     * 获取会话中最近 N 条已完成的消息（按 messageOrder 倒序取，结果需要反转为正序）
     */
    @Query(
        "SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.completed = true " +
            "ORDER BY m.messageOrder DESC"
    )
    fun findRecentCompletedBySessionId(
        @Param("sessionId") sessionId: Long,
        pageable: Pageable
    ): List<RagChatMessageEntity>

    /**
     * 统计会话消息数量
     *
     * 注意：HEAD 的 Java 源码中该声明被一段未闭合的 Javadoc 注释吞掉，实际未编译进接口；
     * 此处按原意保留为派生查询，但没有任何调用方，不影响运行时行为。
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

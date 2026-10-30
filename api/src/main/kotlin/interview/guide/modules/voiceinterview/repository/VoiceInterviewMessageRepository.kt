package interview.guide.modules.voiceinterview.repository

import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 语音面试消息Repository
 */
@Repository
interface VoiceInterviewMessageRepository : JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 根据会话ID查找所有消息，按序号升序排列
     */
    fun findBySessionIdOrderBySequenceNumAsc(sessionId: Long): List<VoiceInterviewMessageEntity>

    /**
     * 根据会话ID查找指定类型以外的消息，按序号升序排列
     */
    fun findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        sessionId: Long,
        messageType: String
    ): List<VoiceInterviewMessageEntity>

    /**
     * 倒序分页读取最近窗口的非 SUMMARY 消息（P1-06 有界加载）。
     */
    fun findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
        sessionId: Long,
        messageType: String,
        pageable: Pageable
    ): List<VoiceInterviewMessageEntity>

    /**
     * 按 sequenceNum 范围升序读取待摘要消息（排除 SUMMARY），Pageable 限制单次摘要批量。
     */
    fun findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        sessionId: Long,
        messageType: String,
        sequenceNumAfter: Int,
        sequenceNumBefore: Int,
        pageable: Pageable
    ): List<VoiceInterviewMessageEntity>

    /**
     * 定位排除 SUMMARY 后第 N 条消息（OFFSET/LIMIT 1），用于旧 SUMMARY 行覆盖轮数到边界的迁移。
     */
    fun findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        sessionId: Long,
        messageType: String,
        pageable: Pageable
    ): List<VoiceInterviewMessageEntity>

    /**
     * 查找最近一条已有 AI 文本但尚未关联用户回答的消息（用于把用户回答回填到上一个问题）
     */
    fun findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
        sessionId: Long
    ): VoiceInterviewMessageEntity?

    /**
     * 统计会话消息总数
     */
    fun countBySessionId(sessionId: Long): Long

    /**
     * 统计会话指定类型以外的消息数量（排除摘要行）
     */
    fun countBySessionIdAndMessageTypeNot(sessionId: Long, messageType: String): Long

    /**
     * 删除会话下所有消息
     */
    fun deleteBySessionId(sessionId: Long)

    /**
     * 查找会话下指定类型的第一条消息（SUMMARY 行定位）
     */
    fun findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
        sessionId: Long,
        messageType: String
    ): VoiceInterviewMessageEntity?
}

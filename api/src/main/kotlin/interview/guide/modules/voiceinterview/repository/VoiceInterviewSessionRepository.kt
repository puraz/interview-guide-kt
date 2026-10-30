package interview.guide.modules.voiceinterview.repository

import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 语音面试会话Repository
 */
@Repository
interface VoiceInterviewSessionRepository : JpaRepository<VoiceInterviewSessionEntity, Long> {

    /**
     * 根据会话ID查询并加悲观写锁（摘要边界写入使用）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM VoiceInterviewSessionEntity s WHERE s.id = :sessionId")
    fun findByIdForUpdate(@Param("sessionId") sessionId: Long): VoiceInterviewSessionEntity?

    /**
     * 根据用户ID查找所有会话，按开始时间倒序
     */
    fun findByUserIdOrderByStartTimeDesc(userId: String): List<VoiceInterviewSessionEntity>

    /**
     * 查找指定状态且结束时间早于给定时间的会话
     * Note: Queries the AsyncTaskStatus field, not InterviewPhase
     */
    fun findByStatusAndEndTimeBefore(
        status: AsyncTaskStatus,
        time: LocalDateTime
    ): VoiceInterviewSessionEntity?

    /**
     * Find all sessions for a user, ordered by update time
     */
    fun findByUserIdOrderByUpdatedAtDesc(userId: String): List<VoiceInterviewSessionEntity>

    /**
     * Find sessions by user and status, ordered by update time
     */
    fun findByUserIdAndStatusOrderByUpdatedAtDesc(
        userId: String,
        status: VoiceInterviewSessionStatus
    ): List<VoiceInterviewSessionEntity>

    /**
     * 查找指定状态且开始时间早于给定时间的会话（用于清理卡住的进行中会话）
     */
    fun findByStatusAndStartTimeBefore(
        status: VoiceInterviewSessionStatus,
        time: LocalDateTime
    ): List<VoiceInterviewSessionEntity>

    /**
     * 查找指定评估状态且更新时间早于给定时间的会话（用于重投/失败兜底）
     */
    fun findByEvaluateStatusAndUpdatedAtBefore(
        evaluateStatus: AsyncTaskStatus,
        time: LocalDateTime
    ): List<VoiceInterviewSessionEntity>
}

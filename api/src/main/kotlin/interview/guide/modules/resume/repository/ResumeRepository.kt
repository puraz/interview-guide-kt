package interview.guide.modules.resume.repository

import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.resume.model.ResumeEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 简历Repository
 */
@Repository
interface ResumeRepository : JpaRepository<ResumeEntity, Long> {

    /**
     * 加悲观写锁查询简历（用于条件落库，防止旧执行代次覆盖）
     *
     * @param id 简历ID // 主键
     * @return 简历实体 // 不存在时为 null
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ResumeEntity r WHERE r.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ResumeEntity?

    /**
     * 根据文件哈希查找简历（用于去重）
     *
     * @param fileHash 文件哈希 // SHA-256
     * @return 已存在的简历 // 不存在时为 null
     */
    fun findByFileHash(fileHash: String): ResumeEntity?

    /**
     * 检查文件哈希是否存在
     *
     * @param fileHash 文件哈希 // SHA-256
     * @return 是否存在 // 存在为 true
     */
    fun existsByFileHash(fileHash: String): Boolean

    // ========== P1-07 条件状态更新（终态不被覆盖，多实例安全） ==========

    /**
     * PENDING → PROCESSING 条件领取
     *
     * @param id        简历ID // 主键
     * @param attemptId 执行代次 // UUID
     * @param now       当前时间 // 进展时间
     * @return 更新行数 // 1 表示领取成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'PROCESSING', " +
            "r.analyzeAttemptId = :attemptId, r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PENDING'"
    )
    fun tryMarkAnalyzeProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * 心跳：仍为 PROCESSING 才推进进展时间
     *
     * @param id        简历ID // 主键
     * @param attemptId 执行代次 // UUID
     * @param now       当前时间 // 进展时间
     * @return 更新行数 // 1 表示心跳成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' " +
            "AND r.analyzeAttemptId = :attemptId"
    )
    fun heartbeatAnalyzeProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * PROCESSING → COMPLETED（非 PROCESSING 时 no-op）
     *
     * @param id        简历ID // 主键
     * @param attemptId 执行代次 // UUID
     * @param now       当前时间 // 进展时间
     * @return 更新行数 // 1 表示完成写入成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'COMPLETED', r.analyzeError = null, " +
            "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' " +
            "AND r.analyzeAttemptId = :attemptId"
    )
    fun completeAnalyzeIfProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * 当前执行代次 PROCESSING → FAILED
     *
     * @param id        简历ID // 主键
     * @param attemptId 执行代次 // UUID
     * @param error     错误信息 // 最大 500 字符
     * @param now       当前时间 // 进展时间
     * @return 更新行数 // 1 表示失败写入成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'FAILED', r.analyzeError = :error, " +
            "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' " +
            "AND r.analyzeAttemptId = :attemptId"
    )
    fun failAnalyzeIfProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String?,
        @Param("error") error: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * 尚未领取的 PENDING → FAILED，用于生产者或恢复补投失败
     *
     * @param id    简历ID // 主键
     * @param error 错误信息 // 最大 500 字符
     * @param now   当前时间 // 进展时间
     * @return 更新行数 // 1 表示失败写入成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'FAILED', r.analyzeError = :error, " +
            "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PENDING'"
    )
    fun failAnalyzeIfPending(
        @Param("id") id: Long,
        @Param("error") error: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * PROCESSING → PENDING（重试重置）
     *
     * @param id        简历ID // 主键
     * @param attemptId 执行代次 // UUID
     * @param now       当前时间 // 进展时间
     * @return 更新行数 // 1 表示重置成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'PENDING', r.analyzeAttemptId = null, " +
            "r.analyzeUpdatedAt = :now WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' " +
            "AND r.analyzeAttemptId = :attemptId"
    )
    fun resetAnalyzeToPending(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * 恢复补投前原子推进时间（PENDING 且早于阈值）
     *
     * @param id        简历ID // 主键
     * @param threshold 过期阈值 // 早于该时间视为卡住
     * @param now       当前时间 // 新的进展时间
     * @return 更新行数 // 1 表示推进成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeUpdatedAt = :now " +
            "WHERE r.id = :id AND r.analyzeStatus = 'PENDING' AND r.analyzeUpdatedAt < :threshold"
    )
    fun touchQueuedAnalyzeForRecovery(
        @Param("id") id: Long,
        @Param("threshold") threshold: LocalDateTime,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * PROCESSING 无进展超阈值 → PENDING
     *
     * @param id        简历ID // 主键
     * @param threshold 过期阈值 // 早于该时间视为卡住
     * @param now       当前时间 // 新的进展时间
     * @return 更新行数 // 1 表示重置成功
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE ResumeEntity r SET r.analyzeStatus = 'PENDING', r.analyzeAttemptId = null, " +
            "r.analyzeUpdatedAt = :now WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' " +
            "AND r.analyzeUpdatedAt < :threshold"
    )
    fun resetStaleAnalyzeProcessing(
        @Param("id") id: Long,
        @Param("threshold") threshold: LocalDateTime,
        @Param("now") now: LocalDateTime
    ): Int

    /**
     * 恢复计数 +1
     *
     * @param id 简历ID // 主键
     * @return 更新行数 // 1 表示自增成功
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeRecoveryCount = r.analyzeRecoveryCount + 1 WHERE r.id = :id")
    fun incrementAnalyzeRecoveryCount(@Param("id") id: Long): Int

    /**
     * 手动重试清零恢复计数
     *
     * @param id 简历ID // 主键
     * @return 更新行数 // 1 表示清零成功
     */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeRecoveryCount = 0 WHERE r.id = :id")
    fun resetAnalyzeRecoveryCount(@Param("id") id: Long): Int

    /**
     * 卡住任务扫描（带每轮上限）
     *
     * @param status    分析状态 // PENDING / PROCESSING
     * @param threshold 过期阈值 // 早于该时间视为卡住
     * @param pageable  分页参数 // 每轮上限
     * @return 卡住的任务 // 按进展时间升序
     */
    @Query(
        "SELECT r FROM ResumeEntity r WHERE r.analyzeStatus = :status AND r.analyzeUpdatedAt < :threshold " +
            "ORDER BY r.analyzeUpdatedAt ASC"
    )
    fun findStaleByAnalyzeStatus(
        @Param("status") status: AsyncTaskStatus,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable
    ): List<ResumeEntity>
}

package interview.guide.modules.knowledgebase.repository

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.QuestionGenStatus
import interview.guide.modules.knowledgebase.model.VectorStatus
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
import java.util.Optional

/**
 * 知识库Repository
 */
@Repository
interface KnowledgeBaseRepository : JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    fun findByFileHash(fileHash: String): Optional<KnowledgeBaseEntity>

    /**
     * 锁定知识库行，用于串行化同一知识库的题目生成状态迁移。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE k.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<KnowledgeBaseEntity>

    /**
     * 检查文件哈希是否存在
     */
    fun existsByFileHash(fileHash: String): Boolean

    /**
     * 按上传时间倒序查找所有知识库
     */
    fun findAllByOrderByUploadedAtDesc(): List<KnowledgeBaseEntity>

    /**
     * 获取所有不同的分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    fun findAllCategories(): List<String>

    /**
     * 根据分类查找知识库
     */
    fun findByCategoryOrderByUploadedAtDesc(category: String): List<KnowledgeBaseEntity>

    /**
     * 查找未分类的知识库
     */
    fun findByCategoryIsNullOrderByUploadedAtDesc(): List<KnowledgeBaseEntity>

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query(
        "SELECT k FROM KnowledgeBaseEntity k WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY k.uploadedAt DESC"
    )
    fun searchByKeyword(@Param("keyword") keyword: String): List<KnowledgeBaseEntity>

    /**
     * 按文件大小排序
     */
    fun findAllByOrderByFileSizeDesc(): List<KnowledgeBaseEntity>

    /**
     * 按访问次数排序
     */
    fun findAllByOrderByAccessCountDesc(): List<KnowledgeBaseEntity>

    /**
     * 按提问次数排序
     */
    fun findAllByOrderByQuestionCountDesc(): List<KnowledgeBaseEntity>

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     *
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    fun incrementQuestionCountBatch(@Param("ids") ids: List<Long>): Int

    // ==================== 统计查询 ====================

    /**
     * 统计总提问次数
     */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    fun sumQuestionCount(): Long

    /**
     * 统计总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    fun sumAccessCount(): Long

    /**
     * 按向量化状态统计数量
     */
    fun countByVectorStatus(vectorStatus: VectorStatus): Long

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    fun findByVectorStatusOrderByUploadedAtDesc(vectorStatus: VectorStatus): List<KnowledgeBaseEntity>

    /**
     * 查找超时未推进的题目生成任务
     */
    @Query(
        "SELECT k FROM KnowledgeBaseEntity k " +
            "WHERE k.questionGenStatus = :status " +
            "AND (k.questionGenUpdatedAt IS NULL OR k.questionGenUpdatedAt < :threshold)"
    )
    fun findStaleQuestionGenerationTasks(
        @Param("status") status: QuestionGenStatus,
        @Param("threshold") threshold: LocalDateTime
    ): List<KnowledgeBaseEntity>

    // ========== P1-07 条件状态更新（终态不被覆盖，多实例安全） ==========

    /** PENDING → PROCESSING 条件领取，返回是否领取成功 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PROCESSING', " +
            "kb.vectorAttemptId = :attemptId, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PENDING'"
    )
    fun tryMarkVectorProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("now") now: LocalDateTime
    ): Int

    /** 心跳：仍为 PROCESSING 才推进进展时间 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' " +
            "AND kb.vectorAttemptId = :attemptId"
    )
    fun heartbeatVectorProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("now") now: LocalDateTime
    ): Int

    /** PROCESSING → COMPLETED（非 PROCESSING 时 no-op） */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'COMPLETED', " +
            "kb.vectorError = null, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' " +
            "AND kb.vectorAttemptId = :attemptId"
    )
    fun completeVectorIfProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("now") now: LocalDateTime
    ): Int

    /** 当前执行代次 PROCESSING → FAILED。 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'FAILED', " +
            "kb.vectorError = :error, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' " +
            "AND kb.vectorAttemptId = :attemptId"
    )
    fun failVectorIfProcessing(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("error") error: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /** 尚未领取的 PENDING → FAILED，用于生产者或恢复补投失败。 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'FAILED', " +
            "kb.vectorError = :error, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PENDING'"
    )
    fun failVectorIfPending(
        @Param("id") id: Long,
        @Param("error") error: String?,
        @Param("now") now: LocalDateTime
    ): Int

    /** PROCESSING → PENDING（重试重置） */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PENDING', " +
            "kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' " +
            "AND kb.vectorAttemptId = :attemptId"
    )
    fun resetVectorToPending(
        @Param("id") id: Long,
        @Param("attemptId") attemptId: String,
        @Param("now") now: LocalDateTime
    ): Int

    /** 恢复补投前原子推进时间（PENDING 且早于阈值），保证同期只补投一次 */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PENDING' AND kb.vectorUpdatedAt < :threshold"
    )
    fun touchQueuedVectorForRecovery(
        @Param("id") id: Long,
        @Param("threshold") threshold: LocalDateTime,
        @Param("now") now: LocalDateTime
    ): Int

    /** PROCESSING 无进展超阈值 → PENDING（恢复前置条件重置） */
    @Transactional
    @Modifying
    @Query(
        "UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PENDING', " +
            "kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now " +
            "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' " +
            "AND kb.vectorUpdatedAt < :threshold"
    )
    fun resetStaleVectorProcessing(
        @Param("id") id: Long,
        @Param("threshold") threshold: LocalDateTime,
        @Param("now") now: LocalDateTime
    ): Int

    /** 恢复计数 +1，返回更新后计数用 findById 读取；此处只做自增 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorRecoveryCount = kb.vectorRecoveryCount + 1 WHERE kb.id = :id")
    fun incrementVectorRecoveryCount(@Param("id") id: Long): Int

    /** 手动重试清零恢复计数 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorRecoveryCount = 0 WHERE kb.id = :id")
    fun resetVectorRecoveryCount(@Param("id") id: Long): Int

    /** 卡住任务扫描（带每轮上限） */
    @Query(
        "SELECT kb FROM KnowledgeBaseEntity kb WHERE kb.vectorStatus = :status " +
            "AND kb.vectorUpdatedAt < :threshold ORDER BY kb.vectorUpdatedAt ASC"
    )
    fun findStaleByVectorStatus(
        @Param("status") status: VectorStatus,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable
    ): List<KnowledgeBaseEntity>
}

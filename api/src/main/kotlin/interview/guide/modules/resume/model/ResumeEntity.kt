package interview.guide.modules.resume.model

import interview.guide.common.model.AsyncTaskStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 简历实体
 * Resume Entity for deduplication and persistence
 */
@Entity
@Table(
    name = "resumes",
    indexes = [Index(name = "idx_resume_hash", columnList = "fileHash", unique = true)]
)
open class ResumeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, unique = true, length = 64)
    var fileHash: String? = null

    // 原始文件名
    @Column(nullable = false)
    var originalFilename: String? = null

    // 文件大小（字节）
    var fileSize: Long? = null

    // 文件类型
    var contentType: String? = null

    // RustFS存储的文件Key
    @Column(length = 500)
    var storageKey: String? = null

    // RustFS存储的文件URL
    @Column(length = 1000)
    var storageUrl: String? = null

    // 解析后的简历文本
    @Column(columnDefinition = "TEXT")
    var resumeText: String? = null

    // 上传时间
    @Column(nullable = false)
    var uploadedAt: LocalDateTime? = null

    // 最后访问时间
    var lastAccessedAt: LocalDateTime? = null

    // 访问次数
    var accessCount: Int? = 0

    // 分析状态（新上传时为 PENDING，异步分析完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var analyzeStatus: AsyncTaskStatus? = AsyncTaskStatus.PENDING

    /**
     * 最后一次有效进展时间（状态变化与心跳都推进），用于卡住任务恢复判定。
     */
    @Column(name = "analyze_updated_at")
    var analyzeUpdatedAt: LocalDateTime? = null

    /**
     * 恢复调度器自动补投次数（手动重试清零），与 Stream retryCount 独立。
     */
    @Column(name = "analyze_recovery_count", nullable = false)
    var analyzeRecoveryCount: Int = 0

    /**
     * 当前 PROCESSING 执行代次；条件心跳和终态写入必须同时匹配该值。
     */
    @Column(name = "analyze_attempt_id", length = 36)
    var analyzeAttemptId: String? = null

    // 分析错误信息（失败时记录）
    @Column(length = 500)
    var analyzeError: String? = null

    @PrePersist
    protected fun onCreate() {
        uploadedAt = LocalDateTime.now()
        lastAccessedAt = LocalDateTime.now()
        accessCount = 1
    }

    /**
     * 增加访问次数并刷新最后访问时间
     */
    fun incrementAccessCount() {
        this.accessCount = (this.accessCount ?: 0) + 1
        this.lastAccessedAt = LocalDateTime.now()
    }
}

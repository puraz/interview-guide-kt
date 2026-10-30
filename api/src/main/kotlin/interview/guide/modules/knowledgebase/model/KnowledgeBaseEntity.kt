package interview.guide.modules.knowledgebase.model

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
 * 知识库实体
 */
@Entity
@Table(
    name = "knowledge_bases",
    indexes = [
        Index(name = "idx_kb_hash", columnList = "fileHash", unique = true),
        Index(name = "idx_kb_category", columnList = "category")
    ]
)
open class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, unique = true, length = 64)
    var fileHash: String? = null

    // 知识库名称（用户自定义或从文件名提取）
    @Column(nullable = false)
    var name: String? = null

    // 分类/分组（如"Java面试"、"项目文档"等）
    @Column(length = 100)
    var category: String? = null

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

    // 上传时间
    @Column(nullable = false)
    var uploadedAt: LocalDateTime? = null

    // 最后访问时间
    var lastAccessedAt: LocalDateTime? = null

    // 访问次数
    var accessCount: Int? = 0

    // 问题数量（用户针对此知识库提问的次数）
    var questionCount: Int? = 0

    // 向量化状态（新上传时为 PENDING，异步处理完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var vectorStatus: VectorStatus = VectorStatus.PENDING

    // 向量化错误信息（失败时记录）
    @Column(length = 500)
    var vectorError: String? = null

    // 向量分块数量
    var chunkCount: Int? = 0

    /**
     * 本次向量化使用的 Chunk 策略快照（无密钥 JSON）；null 表示旧版默认 800。
     */
    @Column(name = "vector_config")
    var vectorConfig: String? = null

    /**
     * 最近一次向量化完成时间。
     */
    @Column(name = "vectorized_at")
    var vectorizedAt: LocalDateTime? = null

    /**
     * 最后一次有效进展时间（状态变化与心跳都推进），用于卡住任务恢复判定。
     */
    @Column(name = "vector_updated_at")
    var vectorUpdatedAt: LocalDateTime? = null

    /**
     * 恢复调度器自动补投次数（手动重试清零），与 Stream retryCount 独立。
     */
    @Column(name = "vector_recovery_count", nullable = false)
    var vectorRecoveryCount: Int = 0

    /**
     * 当前 PROCESSING 执行代次；条件心跳和终态写入必须同时匹配该值。
     */
    @Column(name = "vector_attempt_id", length = 36)
    var vectorAttemptId: String? = null

    // 问题生成状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var questionGenStatus: QuestionGenStatus = QuestionGenStatus.NONE

    // 问题生成错误信息（失败时记录）
    @Column(length = 500)
    var questionGenError: String? = null

    // 问题生成任务ID（用于幂等判断，防止旧任务覆盖新任务）
    @Column(length = 36)
    var questionGenTaskId: String? = null

    // 问题生成参数快照（不包含 Prompt、上下文或密钥）
    @Column(columnDefinition = "TEXT")
    var questionGenConfig: String? = null

    // 问题生成结果摘要
    @Column(length = 500)
    var questionGenMessage: String? = null

    var questionGenSavedCount: Int? = 0

    var questionGenSkippedCount: Int? = 0

    var questionGenUpdatedAt: LocalDateTime? = null

    @PrePersist
    fun onCreate() {
        uploadedAt = LocalDateTime.now()
        lastAccessedAt = LocalDateTime.now()
        accessCount = 1
    }

    /**
     * 增加访问次数
     */
    fun incrementAccessCount() {
        accessCount = (accessCount ?: 0) + 1
        lastAccessedAt = LocalDateTime.now()
    }

    /**
     * 增加提问次数
     */
    fun incrementQuestionCount() {
        questionCount = (questionCount ?: 0) + 1
        lastAccessedAt = LocalDateTime.now()
    }
}

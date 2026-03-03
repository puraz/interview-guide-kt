package interview.guide.entity

import interview.guide.common.model.AsyncTaskStatus
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 简历实体
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

    @Column(nullable = false, unique = true, length = 64)
    var fileHash: String? = null // 文件内容哈希，用于去重

    @Column(nullable = false)
    var originalFilename: String? = null // 原始文件名

    var fileSize: Long? = null // 文件大小（字节）

    var contentType: String? = null // 文件类型

    @Column(length = 500)
    var storageKey: String? = null // 存储文件Key

    @Column(length = 1000)
    var storageUrl: String? = null // 存储文件URL

    @Column(columnDefinition = "TEXT")
    var resumeText: String? = null // 解析后的简历文本

    @Column(nullable = false)
    var uploadedAt: LocalDateTime? = null // 上传时间

    var lastAccessedAt: LocalDateTime? = null // 最后访问时间

    var accessCount: Int? = 0 // 访问次数

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var analyzeStatus: AsyncTaskStatus = AsyncTaskStatus.PENDING // 分析状态

    @Column(length = 500)
    var analyzeError: String? = null // 分析错误信息

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
}

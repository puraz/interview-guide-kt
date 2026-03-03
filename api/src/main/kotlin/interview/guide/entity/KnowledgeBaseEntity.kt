package interview.guide.entity

import jakarta.persistence.*
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

    @Column(nullable = false, unique = true, length = 64)
    var fileHash: String? = null // 文件哈希

    @Column(nullable = false)
    var name: String? = null // 知识库名称

    @Column(length = 100)
    var category: String? = null // 分类

    @Column(nullable = false)
    var originalFilename: String? = null // 原始文件名

    var fileSize: Long? = null // 文件大小

    var contentType: String? = null // 文件类型

    @Column(length = 500)
    var storageKey: String? = null // 存储Key

    @Column(length = 1000)
    var storageUrl: String? = null // 存储URL

    @Column(nullable = false)
    var uploadedAt: LocalDateTime? = null // 上传时间

    var lastAccessedAt: LocalDateTime? = null // 最后访问时间

    var accessCount: Int? = 0 // 访问次数

    var questionCount: Int? = 0 // 提问次数

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var vectorStatus: VectorStatus = VectorStatus.PENDING // 向量化状态

    @Column(length = 500)
    var vectorError: String? = null // 向量化错误

    var chunkCount: Int? = 0 // 向量分块数量

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

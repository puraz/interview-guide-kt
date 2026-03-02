package com.example.business.entity

import com.example.business.enums.VectorStatus
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.Table
import java.time.LocalDateTime

/**
 * 知识库实体
 */
@Entity
@Table(name = "knowledge_bases")
interface KnowledgeBaseEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 文件内容SHA-256哈希值，用于去重
     */
    @Column(name = "file_hash")
    val fileHash: String

    /**
     * 知识库名称(用户自定义或从文件名提取)
     */
    @Column(name = "name")
    val name: String

    /**
     * 分类/分组
     */
    @Column(name = "category")
    val category: String?

    /**
     * 原始文件名
     */
    @Column(name = "original_filename")
    val originalFilename: String

    /**
     * 文件大小(字节)
     */
    @Column(name = "file_size")
    val fileSize: Long?

    /**
     * 文件类型
     */
    @Column(name = "content_type")
    val contentType: String?

    /**
     * RustFS存储的文件Key
     */
    @Column(name = "storage_key")
    val storageKey: String?

    /**
     * RustFS存储的文件URL
     */
    @Column(name = "storage_url")
    val storageUrl: String?

    /**
     * 上传时间
     */
    @Column(name = "uploaded_at")
    val uploadedAt: LocalDateTime

    /**
     * 最后访问时间
     */
    @Column(name = "last_accessed_at")
    val lastAccessedAt: LocalDateTime?

    /**
     * 访问次数
     */
    @Column(name = "access_count")
    val accessCount: Int?

    /**
     * 问题数量
     */
    @Column(name = "question_count")
    val questionCount: Int?

    /**
     * 向量化状态
     */
    @Column(name = "vector_status")
    val vectorStatus: VectorStatus?

    /**
     * 向量化错误信息
     */
    @Column(name = "vector_error")
    val vectorError: String?

    /**
     * 向量分块数量
     */
    @Column(name = "chunk_count")
    val chunkCount: Int?
}

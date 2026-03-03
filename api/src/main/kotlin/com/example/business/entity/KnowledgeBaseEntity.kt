package com.example.business.entity

import com.example.business.enums.VectorStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 知识库实体
 */
@Entity
@Table(name = "knowledge_bases")
open class KnowledgeBaseEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 文件内容SHA-256哈希值，用于去重
     */
    @Column(name = "file_hash", nullable = false, length = 64)
    open lateinit var fileHash: String

    /**
     * 知识库名称(用户自定义或从文件名提取)
     */
    @Column(name = "name", nullable = false)
    open lateinit var name: String

    /**
     * 分类/分组
     */
    @Column(name = "category")
    open var category: String? = null

    /**
     * 原始文件名
     */
    @Column(name = "original_filename", nullable = false)
    open lateinit var originalFilename: String

    /**
     * 文件大小(字节)
     */
    @Column(name = "file_size")
    open var fileSize: Long? = null

    /**
     * 文件类型
     */
    @Column(name = "content_type")
    open var contentType: String? = null

    /**
     * RustFS存储的文件Key
     */
    @Column(name = "storage_key")
    open var storageKey: String? = null

    /**
     * RustFS存储的文件URL
     */
    @Column(name = "storage_url")
    open var storageUrl: String? = null

    /**
     * 上传时间
     */
    @Column(name = "uploaded_at", nullable = false)
    open lateinit var uploadedAt: LocalDateTime

    /**
     * 最后访问时间
     */
    @Column(name = "last_accessed_at")
    open var lastAccessedAt: LocalDateTime? = null

    /**
     * 访问次数
     */
    @Column(name = "access_count")
    open var accessCount: Int? = null

    /**
     * 问题数量
     */
    @Column(name = "question_count")
    open var questionCount: Int? = null

    /**
     * 向量化状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vector_status")
    open var vectorStatus: VectorStatus? = null

    /**
     * 向量化错误信息
     */
    @Column(name = "vector_error")
    open var vectorError: String? = null

    /**
     * 向量分块数量
     */
    @Column(name = "chunk_count")
    open var chunkCount: Int? = null
}

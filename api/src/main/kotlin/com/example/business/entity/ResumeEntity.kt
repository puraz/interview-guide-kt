package com.example.business.entity

import com.example.business.enums.AsyncTaskStatus
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
 * 简历实体
 */
@Entity
@Table(name = "resumes")
open class ResumeEntity {

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
     * 解析后的简历文本
     */
    @Column(name = "resume_text", columnDefinition = "TEXT")
    open var resumeText: String? = null

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
     * 分析状态(异步分析)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "analyze_status")
    open var analyzeStatus: AsyncTaskStatus? = null

    /**
     * 分析错误信息
     */
    @Column(name = "analyze_error")
    open var analyzeError: String? = null
}

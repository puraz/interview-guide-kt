package com.example.business.entity

import com.example.business.enums.AsyncTaskStatus
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.Table
import java.time.LocalDateTime

/**
 * 简历实体
 */
@Entity
@Table(name = "resumes")
interface ResumeEntity {

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
     * 解析后的简历文本
     */
    @Column(name = "resume_text", sqlType = "TEXT")
    val resumeText: String?

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
     * 分析状态(异步分析)
     */
    @Column(name = "analyze_status")
    val analyzeStatus: AsyncTaskStatus?

    /**
     * 分析错误信息
     */
    @Column(name = "analyze_error")
    val analyzeError: String?
}

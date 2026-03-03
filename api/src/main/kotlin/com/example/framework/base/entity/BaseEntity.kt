package com.example.framework.base.entity

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.LocalDateTime

/**
 * 基础实体 - JPA版本
 * 包含通用的创建时间、修改时间
 */
@MappedSuperclass
open class BaseEntity {

    /**
     * The time when the object was created.
     *
     * In this example, this property is not
     * explicitly modified by business code,
     * but is automatically modified by `DraftInterceptor`
     */
    @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "created_at", nullable = false)
    open lateinit var createdAt: LocalDateTime

    /**
     * The time when the object was last modified
     *
     * In this example, this property is not
     * explicitly modified by business code,
     * but is automatically modified by `DraftInterceptor`
     */
    @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "updated_at")
    open var updatedAt: LocalDateTime? = null
}

package com.example.business.repository

import com.example.business.entity.ResumeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 简历仓库
 *
 * 说明：仅提供基础持久化能力，复杂查询在 Service 层使用 JPA 查询实现。
 */
@Repository
interface ResumeRepository : JpaRepository<ResumeEntity, Long>

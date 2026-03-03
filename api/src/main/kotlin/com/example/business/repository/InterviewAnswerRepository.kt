package com.example.business.repository

import com.example.business.entity.InterviewAnswerEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 面试答案仓库
 *
 * 说明：仅提供基础持久化能力，复杂查询在 Service 层使用 JPA 查询实现。
 */
@Repository
interface InterviewAnswerRepository : JpaRepository<InterviewAnswerEntity, Long>

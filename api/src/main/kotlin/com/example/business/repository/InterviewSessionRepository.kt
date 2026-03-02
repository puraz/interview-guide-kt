package com.example.business.repository

import com.example.business.entity.InterviewSessionEntity
import org.babyfish.jimmer.spring.repository.KRepository
import org.springframework.stereotype.Repository

/**
 * 面试会话仓库
 * 用途说明：仅提供基础持久化能力，复杂查询在 Service 内使用 sql.createQuery 实现
 */
@Repository
interface InterviewSessionRepository : KRepository<InterviewSessionEntity, Long>

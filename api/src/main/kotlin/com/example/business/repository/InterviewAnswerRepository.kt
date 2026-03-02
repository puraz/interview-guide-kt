package com.example.business.repository

import com.example.business.entity.InterviewAnswerEntity
import org.babyfish.jimmer.spring.repository.KRepository
import org.springframework.stereotype.Repository

/**
 * 面试答案仓库
 * 用途说明：仅提供基础持久化能力，复杂查询在 Service 内使用 sql.createQuery 实现
 */
@Repository
interface InterviewAnswerRepository : KRepository<InterviewAnswerEntity, Long>

package com.example.business.repository

import com.example.business.entity.KnowledgeBaseEntity
import org.babyfish.jimmer.spring.repository.KRepository
import org.springframework.stereotype.Repository

/**
 * 知识库仓库
 */
@Repository
interface KnowledgeBaseRepository : KRepository<KnowledgeBaseEntity, Long>

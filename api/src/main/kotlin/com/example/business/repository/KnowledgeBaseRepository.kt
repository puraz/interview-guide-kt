package com.example.business.repository

import com.example.business.entity.KnowledgeBaseEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 知识库仓库
 */
@Repository
interface KnowledgeBaseRepository : JpaRepository<KnowledgeBaseEntity, Long>

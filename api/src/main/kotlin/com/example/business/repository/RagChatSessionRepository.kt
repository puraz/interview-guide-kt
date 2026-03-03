package com.example.business.repository

import com.example.business.entity.RagChatSessionEntity
import org.babyfish.jimmer.spring.repository.KRepository
import org.springframework.stereotype.Repository

/**
 * RAG 会话仓库
 */
@Repository
interface RagChatSessionRepository : KRepository<RagChatSessionEntity, Long>

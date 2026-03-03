package com.example.business.repository

import com.example.business.entity.RagChatSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * RAG 会话仓库
 */
@Repository
interface RagChatSessionRepository : JpaRepository<RagChatSessionEntity, Long>

package com.example.business.repository

import com.example.business.entity.RagChatMessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * RAG 消息仓库
 */
@Repository
interface RagChatMessageRepository : JpaRepository<RagChatMessageEntity, Long>

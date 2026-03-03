package com.example.business.repository

import com.example.business.entity.RagChatMessageEntity
import org.babyfish.jimmer.spring.repository.KRepository
import org.springframework.stereotype.Repository

/**
 * RAG 消息仓库
 */
@Repository
interface RagChatMessageRepository : KRepository<RagChatMessageEntity, Long>

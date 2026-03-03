package com.example.business.service

import com.example.business.entity.KnowledgeBaseEntity
import com.example.business.entity.RagChatMessageEntity
import com.example.business.entity.RagChatSessionEntity
import com.example.business.repository.RagChatMessageRepository
import com.example.business.repository.RagChatSessionRepository
import com.example.framework.core.exception.BusinessException
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import java.time.LocalDateTime

/**
 * RAG 聊天会话服务
 *
 * 提供会话创建、消息流式处理与管理能力。
 */
@Service
class RagChatSessionService(
    private val sessionRepository: RagChatSessionRepository,
    private val messageRepository: RagChatMessageRepository,
    private val knowledgeBaseService: KnowledgeBaseService
) {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * 创建会话
     *
     * @param param 创建参数
     * @return 会话信息
     */
    @Transactional(rollbackFor = [Exception::class])
    fun createSession(param: CreateSessionParam): SessionVo {
        if (param.knowledgeBaseIds.isEmpty()) {
            throw BusinessException("至少选择一个知识库")
        }
        val kbList = loadKnowledgeBases(param.knowledgeBaseIds)
        if (kbList.size != param.knowledgeBaseIds.size) {
            throw BusinessException("部分知识库不存在")
        }

        val title = if (!param.title.isNullOrBlank()) {
            param.title.trim()
        } else {
            generateTitle(kbList.map { it.name })
        }

        val now = LocalDateTime.now()
        val session = RagChatSessionEntity().apply {
            this.title = title // 会话标题
            this.status = RagChatSessionEntity.SessionStatus.ACTIVE // 会话状态
            this.createdAt = now // 创建时间
            this.updatedAt = now // 更新时间
            this.messageCount = 0 // 消息数量
            this.isPinned = false // 是否置顶
            this.knowledgeBases = kbList.toMutableList() // 关联知识库
        }
        val saved = sessionRepository.save(session)

        return SessionVo(
            id = saved.id, // 会话ID
            title = title, // 会话标题
            knowledgeBaseIds = param.knowledgeBaseIds, // 知识库ID列表
            createdAt = now // 创建时间
        )
    }

    /**
     * 获取会话列表
     *
     * @return 会话列表
     */
    fun listSessions(): List<SessionListItemVo> {
        val sessions = entityManager.createQuery(
            """
            SELECT s
            FROM RagChatSessionEntity s
            ORDER BY s.isPinned DESC,
                     CASE WHEN s.updatedAt IS NULL THEN 1 ELSE 0 END,
                     s.updatedAt DESC,
                     s.createdAt DESC
            """.trimIndent(),
            RagChatSessionEntity::class.java
        )
            .resultList
        if (sessions.isEmpty()) {
            return emptyList()
        }

        val kbNameMap = loadSessionKnowledgeBaseNames(sessions.map { it.id })
        return sessions.map { session ->
            SessionListItemVo(
                id = session.id, // 会话ID
                title = session.title, // 会话标题
                messageCount = session.messageCount ?: 0, // 消息数量
                knowledgeBaseNames = kbNameMap[session.id] ?: emptyList(), // 知识库名称列表
                updatedAt = session.updatedAt, // 更新时间
                isPinned = session.isPinned ?: false // 是否置顶
            )
        }
    }

    /**
     * 获取会话详情
     *
     * @param sessionId 会话ID
     * @return 会话详情
     */
    fun getSessionDetail(sessionId: Long): SessionDetailVo {
        val session = findSessionBase(sessionId)
        val kbIds = loadSessionKnowledgeBaseIds(sessionId)
        val knowledgeBases = knowledgeBaseService.listByIds(kbIds)
        val messages = loadMessages(sessionId)
        return SessionDetailVo(
            id = session.id, // 会话ID
            title = session.title, // 会话标题
            knowledgeBases = knowledgeBases, // 知识库列表
            messages = messages, // 消息列表
            createdAt = session.createdAt, // 创建时间
            updatedAt = session.updatedAt // 更新时间
        )
    }

    /**
     * 准备流式消息
     *
     * @param sessionId 会话ID
     * @param question 用户问题
     * @return AI 消息ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun prepareStreamMessage(sessionId: Long, question: String): Long {
        val session = findSessionEntity(sessionId)
        val nextOrder = session.messageCount ?: 0
        val now = LocalDateTime.now()

        val userMessage = RagChatMessageEntity().apply {
            this.session = session // 关联会话
            this.type = RagChatMessageEntity.MessageType.USER // 消息类型
            this.content = question // 用户问题
            this.messageOrder = nextOrder // 消息顺序
            this.createdAt = now // 创建时间
            this.updatedAt = now // 更新时间
            this.completed = true // 用户消息默认完成
        }
        messageRepository.save(userMessage)

        val assistantMessage = RagChatMessageEntity().apply {
            this.session = session // 关联会话
            this.type = RagChatMessageEntity.MessageType.ASSISTANT // 消息类型
            this.content = "" // AI消息初始为空
            this.messageOrder = nextOrder + 1 // 消息顺序
            this.createdAt = now // 创建时间
            this.updatedAt = now // 更新时间
            this.completed = false // AI消息未完成
        }
        val savedAssistant = messageRepository.save(assistantMessage)

        session.messageCount = nextOrder + 2 // 更新消息数量
        session.updatedAt = now // 更新会话更新时间
        sessionRepository.save(session)

        return savedAssistant.id
    }

    /**
     * 完成流式消息
     *
     * @param messageId 消息ID
     * @param content 完整内容
     */
    @Transactional(rollbackFor = [Exception::class])
    fun completeStreamMessage(messageId: Long, content: String) {
        val message = messageRepository.findById(messageId).orElse(null)
            ?: throw BusinessException("消息不存在")
        message.content = content // 更新消息内容
        message.completed = true // 标记完成
        message.updatedAt = LocalDateTime.now() // 更新时间
        messageRepository.save(message)
    }

    /**
     * 获取流式回答
     *
     * @param sessionId 会话ID
     * @param question 用户问题
     * @return 流式输出
     */
    fun getStreamAnswer(sessionId: Long, question: String): Flux<String> {
        val kbIds = loadSessionKnowledgeBaseIds(sessionId)
        return knowledgeBaseService.answerQuestionStream(kbIds, question)
    }

    /**
     * 更新会话标题
     *
     * @param sessionId 会话ID
     * @param title 新标题
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateSessionTitle(sessionId: Long, title: String) {
        val session = findSessionEntity(sessionId)
        session.title = title.trim() // 更新标题
        session.updatedAt = LocalDateTime.now() // 更新时间
        sessionRepository.save(session)
    }

    /**
     * 切换置顶状态
     *
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun togglePin(sessionId: Long) {
        val session = findSessionEntity(sessionId)
        val next = !(session.isPinned ?: false)
        session.isPinned = next // 切换置顶状态
        session.updatedAt = LocalDateTime.now() // 更新时间
        sessionRepository.save(session)
    }

    /**
     * 更新会话知识库
     *
     * @param sessionId 会话ID
     * @param knowledgeBaseIds 知识库ID列表
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateSessionKnowledgeBases(sessionId: Long, knowledgeBaseIds: List<Long>) {
        if (knowledgeBaseIds.isEmpty()) {
            throw BusinessException("至少选择一个知识库")
        }
        val session = findSessionEntity(sessionId)
        val kbList = loadKnowledgeBases(knowledgeBaseIds)
        if (kbList.size != knowledgeBaseIds.size) {
            throw BusinessException("部分知识库不存在")
        }
        session.knowledgeBases = kbList.toMutableList() // 更新关联知识库
        session.updatedAt = LocalDateTime.now() // 更新时间
        sessionRepository.save(session)
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSession(sessionId: Long) {
        ensureSessionExists(sessionId)
        entityManager.createQuery("DELETE FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
            .setParameter("sessionId", sessionId)
            .executeUpdate()
        entityManager.createNativeQuery("DELETE FROM rag_session_knowledge_bases WHERE session_id = :sessionId")
            .setParameter("sessionId", sessionId)
            .executeUpdate()
        sessionRepository.deleteById(sessionId)
    }

    /**
     * 获取会话基础信息
     *
     * @param sessionId 会话ID
     * @return 会话基础信息
     */
    private fun findSessionBase(sessionId: Long): SessionBase {
        val session = findSessionEntity(sessionId)
        return SessionBase(
            id = session.id, // 会话ID
            title = session.title, // 标题
            messageCount = session.messageCount, // 消息数量
            updatedAt = session.updatedAt, // 更新时间
            createdAt = session.createdAt, // 创建时间
            isPinned = session.isPinned // 是否置顶
        )
    }

    /**
     * 加载会话实体
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    private fun findSessionEntity(sessionId: Long): RagChatSessionEntity {
        return sessionRepository.findById(sessionId).orElse(null) ?: throw BusinessException("会话不存在")
    }

    /**
     * 校验会话是否存在
     *
     * @param sessionId 会话ID
     */
    private fun ensureSessionExists(sessionId: Long) {
        val exists = sessionRepository.existsById(sessionId)
        if (!exists) {
            throw BusinessException("会话不存在")
        }
    }

    /**
     * 查询会话关联的知识库ID列表
     *
     * @param sessionId 会话ID
     * @return 知识库ID列表
     */
    private fun loadSessionKnowledgeBaseIds(sessionId: Long): List<Long> {
        return entityManager.createQuery(
            """
            SELECT kb.id
            FROM RagChatSessionEntity s
            JOIN s.knowledgeBases kb
            WHERE s.id = :sessionId
            """.trimIndent(),
            java.lang.Long::class.java
        )
            .setParameter("sessionId", sessionId)
            .resultList
            .map { it.toLong() }
    }

    /**
     * 批量查询会话关联的知识库名称
     *
     * @param sessionIds 会话ID列表
     * @return 会话ID到知识库名称列表的映射
     */
    private fun loadSessionKnowledgeBaseNames(sessionIds: List<Long>): Map<Long, List<String>> {
        if (sessionIds.isEmpty()) {
            return emptyMap()
        }
        val rows = entityManager.createQuery(
            """
            SELECT s.id, kb.name
            FROM RagChatSessionEntity s
            JOIN s.knowledgeBases kb
            WHERE s.id IN :sessionIds
            """.trimIndent(),
            Array<Any>::class.java
        )
            .setParameter("sessionIds", sessionIds)
            .resultList
        val map = mutableMapOf<Long, MutableList<String>>()
        for (row in rows) {
            val columns = row as Array<*>
            val sessionId = (columns[0] as Number).toLong()
            val name = columns[1] as String
            val list = map.getOrPut(sessionId) { mutableListOf() }
            list.add(name)
        }
        return map
    }

    /**
     * 查询会话消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    private fun loadMessages(sessionId: Long): List<MessageVo> {
        val messages = entityManager.createQuery(
            """
            SELECT m
            FROM RagChatMessageEntity m
            WHERE m.session.id = :sessionId
            ORDER BY m.messageOrder ASC
            """.trimIndent(),
            RagChatMessageEntity::class.java
        )
            .setParameter("sessionId", sessionId)
            .resultList
        return messages.map { message ->
            MessageVo(
                id = message.id, // 消息ID
                type = message.type.name.lowercase(), // 消息类型
                content = message.content, // 消息内容
                createdAt = message.createdAt // 创建时间
            )
        }
    }

    /**
     * 批量加载知识库实体
     *
     * @param ids 知识库ID列表
     * @return 知识库实体列表
     */
    private fun loadKnowledgeBases(ids: List<Long>): List<KnowledgeBaseEntity> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        return entityManager.createQuery(
            """
            SELECT kb
            FROM KnowledgeBaseEntity kb
            WHERE kb.id IN :ids
            """.trimIndent(),
            KnowledgeBaseEntity::class.java
        )
            .setParameter("ids", ids)
            .resultList
    }

    /**
     * 生成会话标题
     *
     * @param names 知识库名称列表
     * @return 会话标题
     */
    private fun generateTitle(names: List<String>): String {
        if (names.isEmpty()) {
            return "新对话"
        }
        if (names.size == 1) {
            return names.first()
        }
        return "${names.size} 个知识库对话"
    }

    /**
     * 创建会话参数
     */
    data class CreateSessionParam(
        @field:NotEmpty(message = "至少选择一个知识库")
        val knowledgeBaseIds: List<Long>, // 知识库ID列表
        val title: String? // 会话标题
    )

    /**
     * 发送消息参数
     */
    data class SendMessageParam(
        @field:NotBlank(message = "问题不能为空")
        val question: String // 用户问题
    )

    /**
     * 更新标题参数
     */
    data class UpdateTitleParam(
        @field:NotBlank(message = "标题不能为空")
        val title: String // 会话标题
    )

    /**
     * 更新知识库参数
     */
    data class UpdateKnowledgeBasesParam(
        @field:NotEmpty(message = "至少选择一个知识库")
        val knowledgeBaseIds: List<Long> // 知识库ID列表
    )

    /**
     * 会话信息
     */
    data class SessionVo(
        val id: Long, // 会话ID
        val title: String, // 会话标题
        val knowledgeBaseIds: List<Long>, // 知识库ID列表
        val createdAt: LocalDateTime // 创建时间
    )

    /**
     * 会话列表项
     */
    data class SessionListItemVo(
        val id: Long, // 会话ID
        val title: String, // 会话标题
        val messageCount: Int, // 消息数量
        val knowledgeBaseNames: List<String>, // 知识库名称列表
        val updatedAt: LocalDateTime?, // 更新时间
        val isPinned: Boolean // 是否置顶
    )

    /**
     * 会话详情
     */
    data class SessionDetailVo(
        val id: Long, // 会话ID
        val title: String, // 会话标题
        val knowledgeBases: List<KnowledgeBaseService.KnowledgeBaseListItemVo>, // 知识库列表
        val messages: List<MessageVo>, // 消息列表
        val createdAt: LocalDateTime?, // 创建时间
        val updatedAt: LocalDateTime? // 更新时间
    )

    /**
     * 消息信息
     */
    data class MessageVo(
        val id: Long, // 消息ID
        val type: String, // 消息类型
        val content: String, // 消息内容
        val createdAt: LocalDateTime? // 创建时间
    )

    private data class SessionBase(
        val id: Long, // 会话ID
        val title: String, // 标题
        val messageCount: Int?, // 消息数量
        val updatedAt: LocalDateTime?, // 更新时间
        val createdAt: LocalDateTime?, // 创建时间
        val isPinned: Boolean? // 是否置顶
    )
}

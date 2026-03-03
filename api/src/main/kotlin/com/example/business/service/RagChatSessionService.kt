package com.example.business.service

import com.example.business.entity.RagChatMessageEntity
import com.example.framework.core.exception.BusinessException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
    private val jdbcTemplate: JdbcTemplate,
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate,
    private val knowledgeBaseService: KnowledgeBaseService
) {
    private val logger = LoggerFactory.getLogger(RagChatSessionService::class.java)

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
        val kbList = knowledgeBaseService.listByIds(param.knowledgeBaseIds)
        if (kbList.size != param.knowledgeBaseIds.size) {
            throw BusinessException("部分知识库不存在")
        }

        val title = if (!param.title.isNullOrBlank()) {
            param.title.trim()
        } else {
            generateTitle(kbList.map { it.name })
        }

        val now = LocalDateTime.now()
        val sessionId = jdbcTemplate.queryForObject(
            """
            INSERT INTO rag_chat_sessions (title, status, created_at, updated_at, message_count, is_pinned)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            title,
            "ACTIVE",
            now,
            now,
            0,
            false
        ) ?: throw BusinessException("创建会话失败")

        batchInsertSessionKnowledgeBases(sessionId, param.knowledgeBaseIds)

        return SessionVo(
            id = sessionId, // 会话ID
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
        val sessions = jdbcTemplate.query(
            """
            SELECT id, title, message_count, updated_at, created_at, is_pinned
            FROM rag_chat_sessions
            ORDER BY is_pinned DESC, updated_at DESC NULLS LAST, created_at DESC
            """.trimIndent()
        ) { rs, _ ->
            SessionBase(
                id = rs.getLong("id"), // 会话ID
                title = rs.getString("title"), // 标题
                messageCount = rs.getObject("message_count") as Int?, // 消息数量
                updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime(), // 更新时间
                createdAt = rs.getTimestamp("created_at")?.toLocalDateTime(), // 创建时间
                isPinned = rs.getObject("is_pinned") as Boolean? // 是否置顶
            )
        }
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
        val session = findSessionBase(sessionId)
        val nextOrder = session.messageCount ?: 0
        val now = LocalDateTime.now()

        jdbcTemplate.update(
            """
            INSERT INTO rag_chat_messages (session_id, type, content, message_order, created_at, updated_at, completed)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            sessionId,
            RagChatMessageEntity.MessageType.USER.name,
            question,
            nextOrder,
            now,
            now,
            true
        )

        val assistantMessageId = jdbcTemplate.queryForObject(
            """
            INSERT INTO rag_chat_messages (session_id, type, content, message_order, created_at, updated_at, completed)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            sessionId,
            RagChatMessageEntity.MessageType.ASSISTANT.name,
            "",
            nextOrder + 1,
            now,
            now,
            false
        ) ?: throw BusinessException("创建AI消息失败")

        jdbcTemplate.update(
            "UPDATE rag_chat_sessions SET message_count = ?, updated_at = ? WHERE id = ?",
            nextOrder + 2,
            now,
            sessionId
        )

        return assistantMessageId
    }

    /**
     * 完成流式消息
     *
     * @param messageId 消息ID
     * @param content 完整内容
     */
    @Transactional(rollbackFor = [Exception::class])
    fun completeStreamMessage(messageId: Long, content: String) {
        val updated = jdbcTemplate.update(
            "UPDATE rag_chat_messages SET content = ?, completed = ?, updated_at = ? WHERE id = ?",
            content,
            true,
            LocalDateTime.now(),
            messageId
        )
        if (updated <= 0) {
            throw BusinessException("消息不存在")
        }
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
        val resolvedTitle = title.trim()
        val updated = jdbcTemplate.update(
            "UPDATE rag_chat_sessions SET title = ?, updated_at = ? WHERE id = ?",
            resolvedTitle,
            LocalDateTime.now(),
            sessionId
        )
        if (updated <= 0) {
            throw BusinessException("会话不存在")
        }
    }

    /**
     * 切换置顶状态
     *
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun togglePin(sessionId: Long) {
        val current = jdbcTemplate.query(
            "SELECT is_pinned FROM rag_chat_sessions WHERE id = ?",
            { rs, _ -> rs.getObject("is_pinned") as Boolean? },
            sessionId
        ).firstOrNull() ?: throw BusinessException("会话不存在")

        val next = !(current ?: false)
        jdbcTemplate.update(
            "UPDATE rag_chat_sessions SET is_pinned = ?, updated_at = ? WHERE id = ?",
            next,
            LocalDateTime.now(),
            sessionId
        )
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
        ensureSessionExists(sessionId)
        val kbList = knowledgeBaseService.listByIds(knowledgeBaseIds)
        if (kbList.size != knowledgeBaseIds.size) {
            throw BusinessException("部分知识库不存在")
        }
        jdbcTemplate.update("DELETE FROM rag_session_knowledge_bases WHERE session_id = ?", sessionId)
        batchInsertSessionKnowledgeBases(sessionId, knowledgeBaseIds)
        jdbcTemplate.update(
            "UPDATE rag_chat_sessions SET updated_at = ? WHERE id = ?",
            LocalDateTime.now(),
            sessionId
        )
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSession(sessionId: Long) {
        ensureSessionExists(sessionId)
        jdbcTemplate.update("DELETE FROM rag_chat_messages WHERE session_id = ?", sessionId)
        jdbcTemplate.update("DELETE FROM rag_session_knowledge_bases WHERE session_id = ?", sessionId)
        jdbcTemplate.update("DELETE FROM rag_chat_sessions WHERE id = ?", sessionId)
    }

    private fun findSessionBase(sessionId: Long): SessionBase {
        return jdbcTemplate.query(
            """
            SELECT id, title, message_count, updated_at, created_at, is_pinned
            FROM rag_chat_sessions
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                SessionBase(
                    id = rs.getLong("id"), // 会话ID
                    title = rs.getString("title"), // 标题
                    messageCount = rs.getObject("message_count") as Int?, // 消息数量
                    updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime(), // 更新时间
                    createdAt = rs.getTimestamp("created_at")?.toLocalDateTime(), // 创建时间
                    isPinned = rs.getObject("is_pinned") as Boolean? // 是否置顶
                )
            },
            sessionId
        ).firstOrNull() ?: throw BusinessException("会话不存在")
    }

    private fun ensureSessionExists(sessionId: Long) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rag_chat_sessions WHERE id = ?",
            Long::class.java,
            sessionId
        ) ?: 0L
        if (exists <= 0L) {
            throw BusinessException("会话不存在")
        }
    }

    private fun loadSessionKnowledgeBaseIds(sessionId: Long): List<Long> {
        return jdbcTemplate.query(
            "SELECT knowledge_base_id FROM rag_session_knowledge_bases WHERE session_id = ?",
            { rs, _ -> rs.getLong("knowledge_base_id") },
            sessionId
        )
    }

    private fun loadSessionKnowledgeBaseNames(sessionIds: List<Long>): Map<Long, List<String>> {
        if (sessionIds.isEmpty()) {
            return emptyMap()
        }
        val sql = """
            SELECT rsk.session_id, kb.name
            FROM rag_session_knowledge_bases rsk
            JOIN knowledge_bases kb ON rsk.knowledge_base_id = kb.id
            WHERE rsk.session_id IN (:sessionIds)
        """.trimIndent()
        val rows = namedParameterJdbcTemplate.query(sql, mapOf("sessionIds" to sessionIds)) { rs, _ ->
            rs.getLong("session_id") to rs.getString("name")
        }
        val map = mutableMapOf<Long, MutableList<String>>()
        for (row in rows) {
            val list = map.getOrPut(row.first) { mutableListOf() }
            list.add(row.second)
        }
        return map
    }

    private fun loadMessages(sessionId: Long): List<MessageVo> {
        return jdbcTemplate.query(
            """
            SELECT id, type, content, created_at
            FROM rag_chat_messages
            WHERE session_id = ?
            ORDER BY message_order ASC
            """.trimIndent(),
            { rs, _ ->
                MessageVo(
                    id = rs.getLong("id"), // 消息ID
                    type = rs.getString("type").lowercase(), // 消息类型
                    content = rs.getString("content"), // 消息内容
                    createdAt = rs.getTimestamp("created_at")?.toLocalDateTime() // 创建时间
                )
            },
            sessionId
        )
    }

    private fun batchInsertSessionKnowledgeBases(sessionId: Long, knowledgeBaseIds: List<Long>) {
        if (knowledgeBaseIds.isEmpty()) {
            return
        }
        jdbcTemplate.batchUpdate(
            "INSERT INTO rag_session_knowledge_bases (session_id, knowledge_base_id) VALUES (?, ?)",
            knowledgeBaseIds.map { id -> arrayOf(sessionId, id) }
        )
    }

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
        val title: String, // 会话标题
        val messageCount: Int?, // 消息数量
        val updatedAt: LocalDateTime?, // 更新时间
        val createdAt: LocalDateTime?, // 创建时间
        val isPinned: Boolean? // 是否置顶
    )
}

package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.KnowledgeBaseEntity
import interview.guide.entity.RagChatMessageEntity
import interview.guide.entity.RagChatSessionEntity
import interview.guide.repository.KnowledgeBaseRepository
import interview.guide.repository.RagChatMessageRepository
import interview.guide.repository.RagChatSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Service
class RagChatSessionService(
    private val sessionRepository: RagChatSessionRepository, // 会话仓库
    private val messageRepository: RagChatMessageRepository, // 消息仓库
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val queryService: KnowledgeBaseQueryService // 查询服务
) {

    private val log = LoggerFactory.getLogger(RagChatSessionService::class.java)

    /**
     * 创建新会话
     */
    @Transactional
    fun createSession(request: CreateSessionRequest): RagSessionVo {
        val knowledgeBases = knowledgeBaseRepository.findAllById(request.knowledgeBaseIds)
        if (knowledgeBases.size != request.knowledgeBaseIds.size) {
            throw BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在")
        }

        val session = RagChatSessionEntity().apply {
            this.title = if (!request.title.isNullOrBlank()) request.title else generateTitle(knowledgeBases)
            this.knowledgeBases = knowledgeBases.toMutableSet()
        }

        val saved = sessionRepository.save(session)
        log.info("创建 RAG 聊天会话: id={}, title={}", saved.id, saved.title)
        return RagSessionVo(saved.id, saved.title, saved.getKnowledgeBaseIds(), saved.createdAt)
    }

    /**
     * 获取会话列表
     */
    fun listSessions(): List<RagSessionListItemVo> {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc().map { session ->
            RagSessionListItemVo(
                id = session.id,
                title = session.title,
                messageCount = session.messageCount,
                knowledgeBaseNames = session.knowledgeBases.mapNotNull { it.name },
                updatedAt = session.updatedAt,
                isPinned = session.isPinned
            )
        }
    }

    /**
     * 获取会话详情（包含消息）
     */
    fun getSessionDetail(sessionId: Long): RagSessionDetailVo {
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        val messages = messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId)
        val kbVos = session.knowledgeBases.map { kb ->
            KnowledgeBaseListItemVo(
                id = kb.id,
                name = kb.name,
                category = kb.category,
                originalFilename = kb.originalFilename,
                fileSize = kb.fileSize,
                contentType = kb.contentType,
                uploadedAt = kb.uploadedAt,
                lastAccessedAt = kb.lastAccessedAt,
                accessCount = kb.accessCount,
                questionCount = kb.questionCount,
                vectorStatus = kb.vectorStatus,
                vectorError = kb.vectorError,
                chunkCount = kb.chunkCount
            )
        }

        val messageVos = messages.map { msg ->
            RagMessageVo(
                id = msg.id,
                type = msg.getTypeString(),
                content = msg.content,
                createdAt = msg.createdAt
            )
        }

        return RagSessionDetailVo(
            id = session.id,
            title = session.title,
            knowledgeBases = kbVos,
            messages = messageVos,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt
        )
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     */
    @Transactional
    fun prepareStreamMessage(sessionId: Long, question: String): Long {
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        val nextOrder = session.messageCount ?: 0

        val userMessage = RagChatMessageEntity().apply {
            this.session = session
            this.type = RagChatMessageEntity.MessageType.USER
            this.content = question
            this.messageOrder = nextOrder
            this.completed = true
        }
        messageRepository.save(userMessage)

        val assistantMessage = RagChatMessageEntity().apply {
            this.session = session
            this.type = RagChatMessageEntity.MessageType.ASSISTANT
            this.content = ""
            this.messageOrder = nextOrder + 1
            this.completed = false
        }
        val savedAssistant = messageRepository.save(assistantMessage)

        session.messageCount = nextOrder + 2
        sessionRepository.save(session)

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, savedAssistant.id)
        return savedAssistant.id ?: 0L
    }

    /**
     * 流式响应完成后更新消息
     */
    @Transactional
    fun completeStreamMessage(messageId: Long, content: String) {
        val message = messageRepository.findById(messageId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "消息不存在") }

        message.content = content
        message.completed = true
        messageRepository.save(message)

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length)
    }

    /**
     * 获取流式回答
     */
    fun getStreamAnswer(sessionId: Long, question: String): Flux<String> {
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")
        val kbIds = session.getKnowledgeBaseIds()
        return queryService.answerQuestionStream(kbIds, question)
    }

    /**
     * 更新会话标题
     */
    @Transactional
    fun updateSessionTitle(sessionId: Long, title: String) {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "会话不存在") }
        session.title = title
        sessionRepository.save(session)
        log.info("更新会话标题: sessionId={}, title={}", sessionId, title)
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    fun togglePin(sessionId: Long) {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "会话不存在") }
        val currentPinned = session.isPinned ?: false
        session.isPinned = !currentPinned
        sessionRepository.save(session)
        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.isPinned)
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    fun updateSessionKnowledgeBases(sessionId: Long, knowledgeBaseIds: List<Long>) {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "会话不存在") }
        val knowledgeBases = knowledgeBaseRepository.findAllById(knowledgeBaseIds)
        session.knowledgeBases = knowledgeBases.toMutableSet()
        sessionRepository.save(session)
        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds)
    }

    /**
     * 删除会话
     */
    @Transactional
    fun deleteSession(sessionId: Long) {
        if (!sessionRepository.existsById(sessionId)) {
            throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")
        }
        sessionRepository.deleteById(sessionId)
        log.info("删除会话: sessionId={}", sessionId)
    }

    private fun generateTitle(knowledgeBases: List<KnowledgeBaseEntity>): String {
        return when (knowledgeBases.size) {
            0 -> "新对话"
            1 -> knowledgeBases.first().name ?: "新对话"
            else -> "${knowledgeBases.size} 个知识库对话"
        }
    }
}

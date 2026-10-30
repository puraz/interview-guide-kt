package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper
import interview.guide.infrastructure.mapper.RagChatMapper
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity
import interview.guide.modules.knowledgebase.model.RagChatVo
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import java.util.HashSet

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Service
class RagChatSessionService(
    private val sessionRepository: RagChatSessionRepository, // 会话仓库
    private val messageRepository: RagChatMessageRepository, // 消息仓库
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val queryService: KnowledgeBaseQueryService, // RAG 查询服务
    private val ragChatMapper: RagChatMapper, // RAG 会话映射器
    private val knowledgeBaseMapper: KnowledgeBaseMapper, // 知识库映射器
    private val queryProperties: KnowledgeBaseQueryProperties // RAG 配置（历史上下文）
) {

    private val log = LoggerFactory.getLogger(RagChatSessionService::class.java)

    /**
     * 创建新会话
     */
    @Transactional
    fun createSession(request: RagChatVo.CreateSessionRequest): RagChatVo.SessionDTO {
        // 验证知识库存在
        val knowledgeBases = knowledgeBaseRepository.findAllById(request.knowledgeBaseIds)

        if (knowledgeBases.size != request.knowledgeBaseIds.size) {
            throw BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在")
        }

        // 创建会话
        val session = RagChatSessionEntity()
        val title = request.title
        session.title = if (!title.isNullOrBlank()) title else generateTitle(knowledgeBases)
        session.knowledgeBases = HashSet(knowledgeBases)

        val saved = sessionRepository.save(session)

        log.info("创建 RAG 聊天会话: id={}, title={}", saved.id, saved.title)

        return ragChatMapper.toSessionDTO(saved)
    }

    /**
     * 获取会话列表
     */
    fun listSessions(): List<RagChatVo.SessionListItemDTO> {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .map { session -> ragChatMapper.toSessionListItemDTO(session) }
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    fun getSessionDetail(sessionId: Long): RagChatVo.SessionDetailDTO {
        // 先加载会话和知识库
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        // 再单独加载消息（避免笛卡尔积）
        val messages = messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId)

        // 转换知识库列表
        val kbDTOs = knowledgeBaseMapper.toListItemDTOList(ArrayList(session.knowledgeBases))

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs)
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    @Transactional
    fun prepareStreamMessage(sessionId: Long, question: String): Long {
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        // 获取当前消息数量作为起始顺序
        val nextOrder = session.messageCount ?: 0

        // 保存用户消息
        val userMessage = RagChatMessageEntity()
        userMessage.session = session
        userMessage.type = RagChatMessageEntity.MessageType.USER
        userMessage.content = question
        userMessage.messageOrder = nextOrder
        userMessage.completed = true
        messageRepository.save(userMessage)

        // 创建 AI 消息占位（未完成）
        var assistantMessage = RagChatMessageEntity()
        assistantMessage.session = session
        assistantMessage.type = RagChatMessageEntity.MessageType.ASSISTANT
        assistantMessage.content = ""
        assistantMessage.messageOrder = nextOrder + 1
        assistantMessage.completed = false
        assistantMessage = messageRepository.save(assistantMessage)

        // 更新会话消息数量
        session.messageCount = nextOrder + 2
        sessionRepository.save(session)

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.id)

        return assistantMessage.id
            ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "消息保存失败")
    }

    /**
     * 流式响应完成后更新消息
     */
    @Transactional
    fun completeStreamMessage(messageId: Long, content: String) {
        val message = messageRepository.findById(messageId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "消息不存在")

        message.content = content
        message.completed = true
        messageRepository.save(message)

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length)
    }

    /**
     * 获取流式回答（带多轮上下文）
     */
    fun getStreamAnswer(sessionId: Long, question: String): Flux<String> {
        val session = sessionRepository.findByIdWithKnowledgeBases(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        val kbIds = session.getKnowledgeBaseIds()
        val history: List<Message> = if (queryProperties.history.enabled) {
            loadHistoryMessages(sessionId)
        } else {
            emptyList()
        }

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size)
        return queryService.answerQuestionStream(kbIds, question, history)
    }

    /**
     * 更新会话标题
     */
    @Transactional
    fun updateSessionTitle(sessionId: Long, title: String) {
        val session = sessionRepository.findById(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        session.title = title
        sessionRepository.save(session)

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title)
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    fun togglePin(sessionId: Long) {
        val session = sessionRepository.findById(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        // 处理 null 值（兼容旧数据）
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
        val session = sessionRepository.findById(sessionId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在")

        val knowledgeBases: List<KnowledgeBaseEntity> = knowledgeBaseRepository.findAllById(knowledgeBaseIds)

        session.knowledgeBases = HashSet(knowledgeBases)
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

    // ========== 私有方法 ==========

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     * 排除当前轮的 user 消息（prepareStreamMessage 中 completed=true 但尚未回答）。
     */
    private fun loadHistoryMessages(sessionId: Long): List<Message> {
        val limit = queryProperties.history.maxMessages + 1
        val recent = messageRepository.findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit))

        if (recent.isEmpty()) {
            return emptyList()
        }

        // 查询结果按 messageOrder DESC 排列，最后一条（DESC 首条）是当前轮的 user 消息，排除
        val historyMessages = if (recent.size <= 1) {
            emptyList()
        } else {
            recent.subList(1, recent.size)
        }

        // 反转为正序（时间从早到晚）
        return historyMessages.reversed().map { m ->
            if (m.type == RagChatMessageEntity.MessageType.USER) {
                UserMessage(m.content.orEmpty()) as Message
            } else {
                AssistantMessage(m.content.orEmpty()) as Message
            }
        }
    }

    private fun generateTitle(knowledgeBases: List<KnowledgeBaseEntity>): String {
        if (knowledgeBases.isEmpty()) {
            return "新对话"
        }
        if (knowledgeBases.size == 1) {
            return knowledgeBases.first().name.orEmpty()
        }
        return knowledgeBases.size.toString() + " 个知识库对话"
    }
}

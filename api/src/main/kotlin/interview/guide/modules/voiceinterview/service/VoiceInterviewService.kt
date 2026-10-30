package interview.guide.modules.voiceinterview.service

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest
import interview.guide.modules.voiceinterview.dto.SessionMetaVo
import interview.guide.modules.voiceinterview.dto.SessionResponseVo
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageVo
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.LocalDateTime

/**
 * Voice Interview Service
 * 语音面试服务
 *
 * Provides business logic for voice interview session management including:
 * - Session lifecycle management (create, end, retrieve)
 * - Phase transitions and state tracking
 * - Message persistence and conversation history
 * - Redis caching for active sessions
 */
@Service
class VoiceInterviewService(
    private val sessionRepository: VoiceInterviewSessionRepository, // 会话仓库
    private val messageRepository: VoiceInterviewMessageRepository, // 消息仓库
    private val evaluationRepository: VoiceInterviewEvaluationRepository, // 评估仓库
    private val redissonClient: RedissonClient, // Redis 客户端（会话缓存）
    private val properties: VoiceInterviewProperties, // 语音面试配置
    private val voiceEvaluateStreamProducer: VoiceEvaluateStreamProducer, // 评估任务生产者
    private val llmProviderRegistry: LlmProviderRegistry // LLM Provider 注册表（与 Java HEAD 保持依赖）
) {

    private val log = LoggerFactory.getLogger(VoiceInterviewService::class.java)

    companion object {
        private const val SESSION_CACHE_KEY_PREFIX = "voice:interview:session:" // 会话缓存 Key 前缀
        private const val CACHE_TTL_HOURS = 1 // 会话缓存时长（小时）
        private const val DEFAULT_USER_ID = "default" // 默认用户ID
        private val PENDING_EVALUATION_REQUEUE_DELAY: Duration = Duration.ofMinutes(3) // 卡住 PENDING 评估的重投延迟
        private val PROCESSING_EVALUATION_TIMEOUT: Duration = Duration.ofMinutes(30) // PROCESSING 评估超时时间
    }

    /**
     * Create a new voice interview session
     * 创建新的语音面试会话
     *
     * @param request Session creation request with role type and phase configuration // 会话创建请求
     * @return SessionResponseVo with session details and WebSocket URL // 会话详情与 WebSocket 地址
     */
    @Transactional
    fun createSession(request: CreateSessionRequest): SessionResponseVo {
        val effectiveSkillId = request.skillId ?: InterviewDefaults.SKILL_ID
        val effectiveLlmProvider = if (!request.llmProvider.isNullOrBlank()) request.llmProvider else null

        val session = VoiceInterviewSessionEntity().apply {
            userId = DEFAULT_USER_ID
            roleType = effectiveSkillId
            skillId = effectiveSkillId
            difficulty = request.difficulty ?: InterviewDefaults.DIFFICULTY
            customJdText = request.customJdText
            resumeId = request.resumeId
            introEnabled = request.introEnabled ?: false
            techEnabled = request.techEnabled ?: true
            projectEnabled = request.projectEnabled ?: true
            hrEnabled = request.hrEnabled ?: true
            llmProvider = effectiveLlmProvider
            plannedDuration = request.plannedDuration ?: 30
            currentPhase = determineFirstPhase(request)
        }

        val saved = sessionRepository.save(session)
        cacheSession(saved)

        log.info(
            "Created voice interview session: {} with template: {}, phase: {}",
            saved.id, effectiveSkillId, saved.currentPhase
        )

        return buildSessionResponse(saved)
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，此方法不会重复操作。
     */
    @Transactional
    fun endSessionIfInProgress(sessionId: String) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        val session = sessionRepository.findById(sessionIdLong).orElse(null)
        if (session == null || session.status != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId)
        endSession(session)
        sendEvaluateTaskAfterCommit(sessionIdLong)
    }

    /**
     * End interview session and update status
     * 结束面试会话并更新状态
     *
     * @param sessionId Session ID (String format, will be converted to Long) // 会话ID（字符串）
     */
    @Transactional
    fun endSession(sessionId: String) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        val session = getSession(sessionIdLong)

        if (session == null) {
            log.warn("Session not found: {}", sessionId)
            return
        }

        endSession(session)
        sendEvaluateTaskAfterCommit(sessionIdLong)
    }

    /**
     * 结束会话：写结束时间/阶段/状态/实际时长与评估状态
     */
    private fun endSession(session: VoiceInterviewSessionEntity) {
        session.endTime = LocalDateTime.now()
        session.currentPhase = InterviewPhase.COMPLETED
        session.status = VoiceInterviewSessionStatus.COMPLETED
        session.actualDuration = session.startTime
            ?.let { Duration.between(it, LocalDateTime.now()).seconds.toInt() } ?: 0
        session.evaluateStatus = AsyncTaskStatus.PENDING

        sessionRepository.save(session)
        invalidateSessionCache(session.id)

        log.info(
            "Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
            session.id, session.actualDuration
        )
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID (String format, will be converted to Long) // 会话ID（字符串）
     * @return VoiceInterviewSessionEntity or null if not found // 会话实体，不存在返回 null
     */
    fun getSession(sessionId: String): VoiceInterviewSessionEntity? {
        return getSession(parseSessionId(sessionId))
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID as Long // 会话ID
     * @return VoiceInterviewSessionEntity or null if not found // 会话实体，不存在返回 null
     */
    fun getSession(sessionId: Long?): VoiceInterviewSessionEntity? {
        if (sessionId == null) {
            return null
        }

        // Try cache first // 优先读取缓存
        val cacheKey = getSessionCacheKey(sessionId)
        val bucket: RBucket<VoiceInterviewSessionEntity> = redissonClient.getBucket(cacheKey)
        val cached = bucket.get()

        if (cached != null) {
            log.debug("Session {} found in cache", sessionId)
            return cached
        }

        // Fallback to database // 缓存未命中回源数据库
        return sessionRepository.findById(sessionId).orElse(null)
    }

    /**
     * Start a new interview phase
     * 开始新的面试阶段
     *
     * @param sessionId Session ID (String format) // 会话ID（字符串）
     * @param phaseStr  Phase as string (INTRO, TECH, PROJECT, HR) // 阶段名称
     */
    @Transactional
    fun startPhase(sessionId: String, phaseStr: String) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        val session = getSession(sessionIdLong)

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId)
            return
        }

        try {
            val newPhase = InterviewPhase.valueOf(phaseStr.uppercase())
            val oldPhase = session.currentPhase
            session.currentPhase = newPhase
            sessionRepository.save(session)
            cacheSession(session) // Update cache // 更新缓存

            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase)
        } catch (e: IllegalArgumentException) {
            log.error("Invalid phase string: {}", phaseStr, e)
        }
    }

    /**
     * Get current phase for session
     * 获取会话当前阶段
     *
     * @param sessionId Session ID (String format) // 会话ID（字符串）
     * @return Current InterviewPhase or null if session not found // 当前阶段，会话不存在返回 null
     */
    fun getCurrentPhase(sessionId: String): InterviewPhase? {
        val session = getSession(sessionId)
        return session?.currentPhase
    }

    /**
     * Save dialogue message (user and AI text) to database
     * 保存对话消息（用户和AI文本）到数据库
     *
     * @param sessionId Session ID (String format) // 会话ID（字符串）
     * @param userText  User's recognized speech text // 用户语音识别文本
     * @param aiText    AI's generated response text // AI 生成文本
     */
    @Transactional
    fun saveMessage(sessionId: String, userText: String?, aiText: String?) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        val session = getSession(sessionIdLong)

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId)
            return
        }

        val normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText)
        val normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText)

        val answerAttached = normalizedUserText != null &&
            fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText)
        if (normalizedAiText == null) {
            return
        }

        val message = VoiceInterviewMessageEntity().apply {
            this.sessionId = sessionIdLong
            messageType = "DIALOGUE"
            phase = session.currentPhase
            userRecognizedText = if (normalizedUserText != null && !answerAttached) normalizedUserText else null
            aiGeneratedText = normalizedAiText
            sequenceNum = getNextSequenceNum(sessionIdLong)
        }

        messageRepository.save(message)
        log.debug(
            "Saved message for session: {}, phase: {}, sequence: {}",
            sessionId, session.currentPhase, message.sequenceNum
        )
    }

    /**
     * 把用户回答回填到最近一条「只有 AI 问题、没有用户回答」的消息
     *
     * @return 是否成功回填 // true 表示本次回答已附着到上一条问题
     */
    private fun fillLatestUnansweredQuestion(sessionId: Long, userText: String): Boolean {
        val message = messageRepository
            .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                sessionId
            )
            ?: return false

        message.userRecognizedText = userText
        messageRepository.save(message)
        log.debug(
            "Filled answer for voice message: sessionId={}, sequence={}",
            sessionId, message.sequenceNum
        )
        return true
    }

    /**
     * Get conversation history for a session
     * 获取会话的对话历史记录
     *
     * @param sessionId Session ID (String format) // 会话ID（字符串）
     * @return List of messages ordered by sequence number // 按序号升序的消息列表
     */
    fun getConversationHistory(sessionId: String): List<VoiceInterviewMessageEntity> {
        val sessionIdLong = parseSessionId(sessionId) ?: return emptyList()
        return messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
        )
    }

    /**
     * 读取会话已持久化的上下文摘要行（message_type=SUMMARY）。无则 null。
     */
    fun loadSummaryRow(sessionId: String): VoiceInterviewMessageEntity? {
        val sessionIdLong = parseSessionId(sessionId) ?: return null
        return messageRepository.findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
        )
    }

    /**
     * 持久化上下文摘要行（真实覆盖边界版本，P1-06 起的唯一生产入口）。
     * 在会话行悲观锁内原地更新或创建 SUMMARY 行；锁内校验边界单调不回退。
     * LLM 压缩必须在事务外完成，本方法只做边界写入。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSummaryRow(sessionId: String, summary: String?, coveredSequenceNum: Int) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        sessionRepository.findByIdForUpdate(sessionIdLong)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        val row = messageRepository
            .findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
            )
            ?: VoiceInterviewMessageEntity().apply {
                this.sessionId = sessionIdLong
                messageType = VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
                sequenceNum = -1
            }

        val existingBoundary = row.summaryCoveredSequenceNum
        if (existingBoundary != null && coveredSequenceNum < existingBoundary) {
            log.warn(
                "摘要覆盖边界回退被拒绝: sessionId={}, existing={}, incoming={}",
                sessionId, existingBoundary, coveredSequenceNum
            )
            return
        }
        row.aiGeneratedText = summary
        // 保留负 sequenceNum 排序语义，但不再作为覆盖边界的事实来源
        row.sequenceNum = minOf(row.sequenceNum ?: -1, -1)
        row.summaryCoveredSequenceNum = coveredSequenceNum
        messageRepository.save(row)
    }

    /**
     * 旧版按轮数编码的摘要持久化（负 sequenceNum 编码 coveredTurns）。
     * 生产路径禁止再调用——会写出新字段为空的行导致迁移反复触发；
     * 仅供数据修复工具与旧测试兼容使用。
     */
    @Deprecated("旧版摘要编码，仅保留数据修复与旧测试兼容")
    @Transactional(rollbackFor = [Exception::class])
    fun saveSummaryRowLegacy(sessionId: String, summary: String?, coveredTurns: Int) {
        val sessionIdLong = parseSessionId(sessionId) ?: return
        sessionRepository.findByIdForUpdate(sessionIdLong)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        val row = messageRepository
            .findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
            )
            ?: VoiceInterviewMessageEntity().apply {
                this.sessionId = sessionIdLong
                messageType = VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
            }
        row.aiGeneratedText = summary
        row.sequenceNum = -(coveredTurns + 1)
        messageRepository.save(row)
    }

    /**
     * Get conversation history as DTOs (for frontend)
     * 获取会话对话历史（供前端展示的 DTO）
     */
    fun getConversationHistoryDTO(sessionId: String): List<VoiceInterviewMessageVo> {
        return getConversationHistory(sessionId).map { msg ->
            VoiceInterviewMessageVo(
                id = msg.id,
                sessionId = msg.sessionId,
                messageType = msg.messageType,
                phase = msg.phase?.name,
                userRecognizedText = msg.userRecognizedText,
                aiGeneratedText = msg.aiGeneratedText,
                timestamp = msg.timestamp,
                sequenceNum = msg.sequenceNum
            )
        }
    }

    /**
     * Pause interview session
     * 暂停面试会话
     *
     * @param sessionId Session ID // 会话ID
     * @param reason Pause reason (user_initiated or timeout) // 暂停原因
     */
    @Transactional
    fun pauseSession(sessionId: String, reason: String?) {
        val sessionIdLong = parseSessionId(sessionId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        val session = sessionRepository.findById(sessionIdLong).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        if (session.status != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw BusinessException(
                ErrorCode.BAD_REQUEST,
                "会话状态为 ${session.status}，无法暂停"
            )
        }

        session.status = VoiceInterviewSessionStatus.PAUSED
        session.pausedAt = LocalDateTime.now()

        sessionRepository.save(session)
        invalidateSessionCache(sessionIdLong)

        log.info("Session {} paused, reason: {}", sessionId, reason)
    }

    /**
     * Resume interview session
     * 恢复面试会话
     *
     * @param sessionId Session ID // 会话ID
     * @return SessionResponseVo with WebSocket URL // 含 WebSocket 地址的会话响应
     */
    @Transactional
    fun resumeSession(sessionId: String): SessionResponseVo {
        val sessionIdLong = parseSessionId(sessionId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        val session = sessionRepository.findById(sessionIdLong).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "会话不存在: $sessionId")

        if (session.status != VoiceInterviewSessionStatus.PAUSED) {
            throw BusinessException(
                ErrorCode.BAD_REQUEST,
                "会话状态为 ${session.status}，无法恢复"
            )
        }

        session.status = VoiceInterviewSessionStatus.IN_PROGRESS
        session.resumedAt = LocalDateTime.now()

        val saved = sessionRepository.save(session)
        cacheSession(saved)

        log.info(
            "Session {} resumed with {} messages in conversation history",
            sessionId, countDialogueMessages(sessionIdLong)
        )

        return buildSessionResponse(saved)
    }

    /**
     * Get all sessions for a user
     * 获取用户所有会话
     *
     * @param userId User ID (optional, defaults to DEFAULT_USER_ID) // 用户ID，默认 default
     * @param status Filter by status (optional) // 状态过滤
     * @return List of session metadata // 会话元数据列表
     */
    fun getAllSessions(userId: String?, status: String?): List<SessionMetaVo> {
        val effectiveUserId = userId ?: DEFAULT_USER_ID

        val sessions = if (!status.isNullOrEmpty()) {
            val statusEnum = VoiceInterviewSessionStatus.valueOf(status.uppercase())
            sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(effectiveUserId, statusEnum)
        } else {
            sessionRepository.findByUserIdOrderByUpdatedAtDesc(effectiveUserId)
        }

        return sessions.map { session ->
            SessionMetaVo(
                sessionId = session.id,
                roleType = session.roleType,
                status = session.status?.name,
                currentPhase = session.currentPhase?.name,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                actualDuration = session.actualDuration,
                messageCount = countDialogueMessages(session.id),
                evaluateStatus = session.evaluateStatus?.name,
                evaluateError = session.evaluateError
            )
        }
    }

    /**
     * Get session DTO by ID
     * 通过ID获取会话DTO
     *
     * @param sessionId Session ID as Long // 会话ID
     * @return SessionResponseVo with session details or null if not found // 会话响应，不存在返回 null
     */
    fun getSessionDTO(sessionId: Long?): SessionResponseVo? {
        val session = getSession(sessionId) ?: return null
        return buildSessionResponse(session)
    }

    /**
     * Check if session should transition to next phase based on duration and question count
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     *
     * @param session        Current session // 当前会话
     * @param phaseStartTime Time when current phase started // 当前阶段开始时间
     * @param questionCount  Number of questions asked in current phase // 当前阶段已提问数
     * @return true if should transition, false otherwise // 是否应切换阶段
     */
    fun shouldTransitionToNextPhase(
        session: VoiceInterviewSessionEntity,
        phaseStartTime: LocalDateTime,
        questionCount: Int
    ): Boolean {
        val currentPhase = session.currentPhase
        if (currentPhase == null || currentPhase == InterviewPhase.COMPLETED) {
            return false
        }

        val phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now())
        val config = getPhaseConfig(currentPhase)

        // Rule 1: Max duration reached (forced transition) // 规则1：达到最长时长强制切换
        if (phaseDuration.toMinutes() >= config.maxDuration) {
            log.info(
                "Phase {} reached max duration {} minutes, forcing transition",
                currentPhase, config.maxDuration
            )
            return true
        }

        // Rule 2: Min questions reached and sufficient information gathered (AI judgment)
        // For MVP, we use a simple heuristic based on question count
        // 规则2：达到最多问题数
        if (questionCount >= config.maxQuestions) {
            log.info(
                "Phase {} reached max questions {}, suggesting transition",
                currentPhase, config.maxQuestions
            )
            return true
        }

        // Rule 3: Suggested duration reached with min questions // 规则3：达到建议时长且满足最少问题数
        if (phaseDuration.toMinutes() >= config.suggestedDuration &&
            questionCount >= config.minQuestions
        ) {
            log.info(
                "Phase {} reached suggested duration {} with {} questions, suggesting transition",
                currentPhase, config.suggestedDuration, questionCount
            )
            return true
        }

        return false
    }

    /**
     * Get the next enabled phase after current phase
     * 获取当前阶段之后的下一个启用的阶段
     *
     * @param session Current session // 当前会话
     * @return Next InterviewPhase or COMPLETED if no more phases // 下一阶段，无更多阶段返回 COMPLETED
     */
    fun getNextPhase(session: VoiceInterviewSessionEntity): InterviewPhase {
        val current = session.currentPhase ?: return getFirstEnabledPhase(session)

        return when (current) {
            InterviewPhase.INTRO -> when {
                session.techEnabled == true -> InterviewPhase.TECH
                session.projectEnabled == true -> InterviewPhase.PROJECT
                session.hrEnabled == true -> InterviewPhase.HR
                else -> InterviewPhase.COMPLETED
            }

            InterviewPhase.TECH -> when {
                session.projectEnabled == true -> InterviewPhase.PROJECT
                session.hrEnabled == true -> InterviewPhase.HR
                else -> InterviewPhase.COMPLETED
            }

            InterviewPhase.PROJECT -> if (session.hrEnabled == true) {
                InterviewPhase.HR
            } else {
                InterviewPhase.COMPLETED
            }

            InterviewPhase.HR, InterviewPhase.COMPLETED -> InterviewPhase.COMPLETED
        }
    }

    // ==================== Private Helper Methods ====================

    /**
     * Determine the first phase based on enabled phases
     * 根据启用的阶段确定第一个阶段
     */
    private fun determineFirstPhase(request: CreateSessionRequest): InterviewPhase {
        if (request.introEnabled == true) return InterviewPhase.INTRO
        if (request.techEnabled == true) return InterviewPhase.TECH
        if (request.projectEnabled == true) return InterviewPhase.PROJECT
        if (request.hrEnabled == true) return InterviewPhase.HR
        return InterviewPhase.COMPLETED
    }

    /**
     * Get first enabled phase from session
     * 获取会话首个启用的阶段
     */
    private fun getFirstEnabledPhase(session: VoiceInterviewSessionEntity): InterviewPhase {
        if (session.introEnabled == true) return InterviewPhase.INTRO
        if (session.techEnabled == true) return InterviewPhase.TECH
        if (session.projectEnabled == true) return InterviewPhase.PROJECT
        if (session.hrEnabled == true) return InterviewPhase.HR
        return InterviewPhase.COMPLETED
    }

    /**
     * 构建会话响应
     */
    private fun buildSessionResponse(session: VoiceInterviewSessionEntity): SessionResponseVo {
        return SessionResponseVo(
            sessionId = session.id,
            roleType = session.roleType,
            currentPhase = session.currentPhase?.name,
            status = session.status?.name,
            startTime = session.startTime,
            plannedDuration = session.plannedDuration,
            webSocketUrl = session.id?.let { String.format("/ws/voice-interview/%d", it) }
        )
    }

    /**
     * Get phase configuration from properties
     * 根据阶段读取时长/题量配置
     */
    private fun getPhaseConfig(phase: InterviewPhase): VoiceInterviewProperties.DurationConfig {
        return when (phase) {
            InterviewPhase.INTRO -> properties.phase.intro
            InterviewPhase.TECH -> properties.phase.tech
            InterviewPhase.PROJECT -> properties.phase.project
            InterviewPhase.HR -> properties.phase.hr
            else -> VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0)
        }
    }

    /**
     * Get next sequence number for messages in a session
     * 获取会话下一条消息序号
     */
    private fun getNextSequenceNum(sessionId: Long): Int {
        return countDialogueMessages(sessionId).toInt() + 1
    }

    /**
     * 统计非摘要消息数量
     */
    private fun countDialogueMessages(sessionId: Long?): Long {
        if (sessionId == null) {
            return 0
        }
        return messageRepository.countBySessionIdAndMessageTypeNot(
            sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
        )
    }

    /**
     * Update evaluation status on session entity (shared by Producer/Consumer/Controller)
     * 更新会话评估状态（Producer/Consumer/Controller 共用）
     */
    fun updateEvaluateStatus(sessionId: Long, status: AsyncTaskStatus, error: String?) {
        try {
            sessionRepository.findById(sessionId).ifPresent { session ->
                session.evaluateStatus = status
                session.evaluateError = error
                sessionRepository.save(session)
                invalidateSessionCache(sessionId)
                log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status)
            }
        } catch (e: Exception) {
            log.error(
                "Failed to update evaluation status: sessionId={}, status={}, error={}",
                sessionId, status, e.message, e
            )
        }
    }

    /**
     * Trigger async evaluation for a session (called by Controller)
     * 触发异步评估（Controller 调用）
     */
    @Transactional
    fun triggerEvaluation(sessionId: Long) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
        sendEvaluateTaskAfterCommit(sessionId)
    }

    /**
     * 事务提交后再投递评估任务，避免消费者读到未提交状态
     */
    private fun sendEvaluateTaskAfterCommit(sessionId: Long) {
        val sendTask = Runnable { voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString()) }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                sendTask.run()
            }
        })
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    fun deleteSession(sessionId: Long) {
        if (!sessionRepository.existsById(sessionId)) {
            throw BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: $sessionId")
        }
        evaluationRepository.findBySessionId(sessionId)?.let { evaluationRepository.delete(it) }
        messageRepository.deleteBySessionId(sessionId)
        sessionRepository.deleteById(sessionId)
        log.info("Deleted voice interview session: {}", sessionId)
    }

    /**
     * Cache session in Redis
     * 将会话写入 Redis 缓存
     */
    private fun cacheSession(session: VoiceInterviewSessionEntity) {
        val cacheKey = getSessionCacheKey(session.id)
        val bucket: RBucket<VoiceInterviewSessionEntity> = redissonClient.getBucket(cacheKey)
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS.toLong()))
        log.debug("Cached session: {}", session.id)
    }

    /**
     * Invalidate session cache in Redis
     * 删除会话 Redis 缓存
     */
    private fun invalidateSessionCache(sessionId: Long?) {
        if (sessionId == null) {
            return
        }
        val cacheKey = getSessionCacheKey(sessionId)
        val bucket: RBucket<VoiceInterviewSessionEntity> = redissonClient.getBucket(cacheKey)
        bucket.delete()
        log.debug("Invalidated cache for session: {}", sessionId)
    }

    /**
     * Generate Redis cache key for session
     * 生成会话缓存 Key
     */
    private fun getSessionCacheKey(sessionId: Long?): String {
        return SESSION_CACHE_KEY_PREFIX + sessionId
    }

    /**
     * Parse session ID from String to Long with error handling
     * 解析字符串会话ID，非法格式返回 null
     */
    private fun parseSessionId(sessionId: String?): Long? {
        if (sessionId == null) {
            return null
        }
        return try {
            sessionId.toLong()
        } catch (e: NumberFormatException) {
            log.error("Invalid session ID format: {}", sessionId, e)
            null
        }
    }

    /**
     * 清理超时会话，重新投递卡住的 PENDING 评估，并结束超时的 PROCESSING 评估。
     * 由 @Scheduled 在 WebSocketHandler 中定时触发。
     */
    @Transactional
    fun cleanupStaleSessions(): Int {
        val staleThreshold = LocalDateTime.now().minusHours(2)

        val staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold)

        var cleaned = 0
        for (session in staleSessions) {
            log.info(
                "Cleaning up stale IN_PROGRESS session {}, started at {}",
                session.id, session.startTime
            )
            endSession(session)
            sendEvaluateTaskAfterCommit(session.id ?: continue)
            cleaned++
        }

        val pendingStaleThreshold = LocalDateTime.now()
            .minus(PENDING_EVALUATION_REQUEUE_DELAY)
        val pendingEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PENDING, pendingStaleThreshold)

        for (session in pendingEvals) {
            log.warn(
                "Requeueing stale PENDING evaluation for session {}, last updated at {}",
                session.id, session.updatedAt
            )
            session.evaluateError = null
            session.updatedAt = LocalDateTime.now()
            sessionRepository.save(session)
            invalidateSessionCache(session.id)
            sendEvaluateTaskAfterCommit(session.id ?: continue)
            cleaned++
        }

        val evalStaleThreshold = LocalDateTime.now()
            .minus(PROCESSING_EVALUATION_TIMEOUT)
        val stuckEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold)

        for (session in stuckEvals) {
            log.info("Resetting stuck PROCESSING evaluation for session {}", session.id)
            session.evaluateStatus = AsyncTaskStatus.FAILED
            session.evaluateError = "评估超时，请重新触发"
            sessionRepository.save(session)
            invalidateSessionCache(session.id)
            cleaned++
        }

        return cleaned
    }
}

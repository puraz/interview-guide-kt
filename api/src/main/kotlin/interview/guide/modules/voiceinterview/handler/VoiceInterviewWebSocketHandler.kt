package interview.guide.modules.voiceinterview.handler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.context.VoiceContextCompressor
import interview.guide.modules.voiceinterview.context.VoiceHistoryLoader
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity
import interview.guide.modules.voiceinterview.service.DashscopeLlmService
import interview.guide.modules.voiceinterview.service.QwenAsrService
import interview.guide.modules.voiceinterview.service.QwenTtsService
import interview.guide.modules.voiceinterview.service.VoiceInterviewService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.Base64
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket Handler for Voice Interview
 * 语音面试 WebSocket 处理器
 *
 * Handles real-time bidirectional audio streaming for voice interviews.
 * Processing pipeline: User Audio → STT → LLM → TTS → AI Audio
 */
@Component
class VoiceInterviewWebSocketHandler(
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val sttService: QwenAsrService, // 语音识别服务
    private val ttsService: QwenTtsService, // 语音合成服务
    private val llmService: DashscopeLlmService, // LLM 对话服务
    private val interviewService: VoiceInterviewService, // 语音面试业务服务
    private val voiceContextCompressor: VoiceContextCompressor, // 上下文压缩器
    private val voiceHistoryLoader: VoiceHistoryLoader, // 有界历史加载器
    private val voiceInterviewProperties: VoiceInterviewProperties, // 语音面试配置
    private val meterRegistryProvider: ObjectProvider<MeterRegistry> // 指标注册表（可选）
) : TextWebSocketHandler(), DisposableBean {

    private val log = LoggerFactory.getLogger(VoiceInterviewWebSocketHandler::class.java)

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度（与 VoiceInterviewProperties 的防抖配置配合）
     */
    private val utteranceMergeScheduler: ScheduledExecutorService = createUtteranceMergeScheduler()

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在独立线程池上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     * Java 25 虚拟线程池在此改为无界守护线程池（并发上限仍由业务 Semaphore 控制）。
     */
    private val voicePipelineExecutor: ExecutorService = createVoicePipelineExecutor()

    private val sessions: ConcurrentHashMap<String, WebSocketSession> = ConcurrentHashMap() // 活跃 WebSocket 会话
    private val sessionStates: ConcurrentHashMap<String, SessionState> = ConcurrentHashMap() // 每会话状态
    private val openingAudioCache: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap() // 开场白音频缓存

    // Activity tracking for pause timeout
    // 活动跟踪（用于暂停超时）
    private val lastActivityTime: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    companion object {
        private const val WARNING_TIME_MS: Long = (4.5 * 60 * 1000).toLong() // 4:30
        private const val PAUSE_TIMEOUT_MS: Long = 5 * 60 * 1000 // 5:00
        private const val WS_SEND_TIME_LIMIT_MS: Int = 10_000 // WebSocket 发送时间上限
        private const val WS_SEND_BUFFER_LIMIT_BYTES: Int = 512 * 1024 // WebSocket 发送缓冲上限
        /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
        private const val AI_SPEAK_COOLDOWN_MS: Long = 800
        private const val MAX_ASR_READY_RETRY: Int = 2
        private const val ASR_READY_CHECK_DELAY_SECONDS: Long = 10
        private const val DEFAULT_OPENING_QUESTION_ALGORITHM: String =
            "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。"
        private const val DEFAULT_OPENING_QUESTION_BACKEND: String =
            "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。"

        /**
         * 创建防抖调度器：2 个调度线程，daemon，取消任务即移除
         */
        private fun createUtteranceMergeScheduler(): ScheduledExecutorService {
            val executor = ScheduledThreadPoolExecutor(2) { r ->
                Thread(r, "voice-utterance-merge").apply { isDaemon = true }
            }
            executor.removeOnCancelPolicy = true
            executor.executeExistingDelayedTasksAfterShutdownPolicy = false
            return executor
        }

        /**
         * 创建语音管线线程池：Java 17 无虚拟线程，使用无界缓存线程池保持并发语义
         */
        private fun createVoicePipelineExecutor(): ExecutorService {
            val counter = AtomicInteger(0)
            return Executors.newCachedThreadPool { r ->
                Thread(r, "voice-pipeline-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        /**
         * 无会话、append 失败等均可重连 ASR
         */
        private fun shouldRecoverAsrConnection(ex: IllegalStateException): Boolean {
            val m = ex.message ?: return false
            return m.contains("No active session") || m.contains("ASR append failed")
        }

        /**
         * ASR 连接尚未就绪
         */
        private fun isAsrNotReady(ex: IllegalStateException): Boolean {
            val m = ex.message
            return m != null && m.contains("ASR session not ready")
        }
    }

    /**
     * 应用启动时预热开场白音频缓存（默认关闭）
     */
    @PostConstruct
    fun warmupOpeningAudioCache() {
        if (!voiceInterviewProperties.openingAudioWarmupEnabled) {
            log.info("Opening audio cache warmup is disabled")
            return
        }
        voicePipelineExecutor.execute {
            try {
                val opening = voiceInterviewProperties.opening
                val allTemplates = LinkedHashSet<String>()
                allTemplates.addAll(opening.skillQuestions.values)
                allTemplates.add(opening.algorithmQuestion)
                allTemplates.add(opening.backendQuestion)
                for (template in allTemplates) {
                    preloadOpeningAudio(template)
                }
                log.info("Opening audio cache warmed: {} entries", openingAudioCache.size)
            } catch (e: Exception) {
                log.warn("Opening audio cache warmup skipped: {}", e.message)
            }
        }
    }

    /**
     * 预热单条开场白音频
     */
    private fun preloadOpeningAudio(text: String?) {
        if (text.isNullOrBlank()) {
            return
        }
        val wavAudio = synthesizeToWav(text)
        if (wavAudio.isNotEmpty()) {
            openingAudioCache[text] = wavAudio
        }
    }

    /**
     * WebSocket 建连：初始化会话、启动 ASR、发送欢迎语与开场问题
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val sessionId = extractSessionId(session)

        // Increase message size limits for audio streaming
        // 1 second of PCM audio @ 16kHz, 16-bit = ~32KB raw, ~42KB base64
        // Set limit to 256KB to allow some buffer and multiple messages
        session.textMessageSizeLimit = 256 * 1024 // 256KB
        session.binaryMessageSizeLimit = 256 * 1024 // 256KB

        val safeSession = ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        )

        sessions[sessionId] = safeSession
        sessionStates[sessionId] = SessionState()
        lastActivityTime[sessionId] = System.currentTimeMillis()
        log.info("WebSocket connection established for session: {}", sessionId)

        try {
            startDashScopeStt(sessionId, safeSession)

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage())
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession)
        } catch (e: Exception) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e)
            sendError(safeSession, "初始化语音识别失败: ${e.message}")
        }
    }

    /**
     * Create welcome message
     * 创建欢迎消息
     */
    private fun createWelcomeMessage(): String {
        return toJson(
            mapOf(
                "type" to "control",
                "action" to "welcome",
                "message" to "连接成功，准备开始语音面试",
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * 序列化 JSON
     */
    private fun toJson(obj: Any?): String {
        return try {
            objectMapper.writeValueAsString(obj)
        } catch (e: Exception) {
            log.error("Error serializing JSON", e)
            "{}"
        }
    }

    /**
     * Send message to WebSocket session
     * 发送文本消息到 WebSocket 会话
     */
    private fun sendMessage(session: WebSocketSession, message: String) {
        try {
            if (session.isOpen) {
                session.sendMessage(TextMessage(message))
                log.debug("Message sent to session: length={}", message.length)
            } else {
                log.warn("Session is closed, cannot send message")
            }
        } catch (e: Exception) {
            log.error("Error sending message to session", e)
        }
    }

    /**
     * 首次连接且无历史消息时自动发送开场问题
     */
    private fun triggerOpeningQuestionIfNeeded(sessionId: String, session: WebSocketSession) {
        voicePipelineExecutor.execute {
            try {
                if (!session.isOpen) {
                    return@execute
                }

                val sessionEntity = getSessionEntity(sessionId)
                if (sessionEntity == null) {
                    log.warn("Session entity not found when sending opening question: {}", sessionId)
                    return@execute
                }

                val history = getHistory(sessionId, sessionEntity.llmProvider)
                if (history.isNotEmpty()) {
                    // 已有历史对话（如重连/恢复），不重复开场
                    return@execute
                }

                val aiReply = buildOpeningQuestion(sessionEntity)
                if (aiReply.isBlank()) {
                    return@execute
                }

                if (!session.isOpen) {
                    return@execute
                }

                // 先落库再推前端，确保用户提交时 DB 中已有该条消息
                saveMessage(sessionId, null, aiReply)
                sendTextMessage(session, aiReply, true)

                // 语音随后下发
                val wavAudio = getOpeningWavAudio(aiReply)
                if (wavAudio.isNotEmpty() && session.isOpen) {
                    sendAudio(session, wavAudio, aiReply)
                }

                log.info("Opening question sent for session {}", sessionId)
            } catch (e: Exception) {
                log.error("Failed to send opening question for session {}", sessionId, e)
            }
        }
    }

    /**
     * 获取开场白音频（优先缓存）
     */
    private fun getOpeningWavAudio(text: String): ByteArray {
        val cached = openingAudioCache[text]
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }
        val wav = synthesizeToWav(text)
        if (wav.isNotEmpty()) {
            openingAudioCache[text] = wav
        }
        return wav
    }

    /**
     * TTS 合成并转换为 WAV
     */
    private fun synthesizeToWav(text: String): ByteArray {
        val pcm = ttsService.synthesize(text)
        if (pcm.isEmpty()) {
            return ByteArray(0)
        }
        return convertPcmToWav(pcm)
    }

    /**
     * 按技能模板选择开场问题
     */
    private fun buildOpeningQuestion(sessionEntity: VoiceInterviewSessionEntity): String {
        val skillId = sessionEntity.skillId ?: ""
        val opening = voiceInterviewProperties.opening
        val bySkill = opening.skillQuestions[skillId]
        if (!bySkill.isNullOrBlank()) {
            return bySkill
        }

        if (opening.algorithmSkills.contains(skillId)) {
            val configured = opening.algorithmQuestion
            return if (configured.isNotBlank()) configured else DEFAULT_OPENING_QUESTION_ALGORITHM
        }
        val configured = opening.backendQuestion
        return if (configured.isNotBlank()) configured else DEFAULT_OPENING_QUESTION_BACKEND
    }

    /**
     * 处理客户端文本消息（音频/控制）
     */
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val sessionId = extractSessionId(session)

        try {
            val msg: JsonNode = objectMapper.readTree(message.payload)
            val type = msg.get("type")?.asText() ?: ""
            val messageSize = message.payload.length
            val messageSizeKB = messageSize / 1024
            if ("audio" == type) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB)
            } else {
                log.info(
                    "[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                    sessionId, type, messageSizeKB, messageSize
                )
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB)
            }

            // Update last activity time for pause timeout detection
            // 更新最后活动时间（用于暂停超时检测）
            lastActivityTime[sessionId] = System.currentTimeMillis()

            when (type) {
                "audio" -> {
                    val audioData = if (msg.has("data")) msg.get("data").asText() else null
                    if (!audioData.isNullOrEmpty()) {
                        handleUserAudio(sessionId, audioData)
                    } else {
                        log.warn("Received audio message without data")
                    }
                }

                "control" -> {
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage::class.java))
                    } catch (e: Exception) {
                        log.error("Error handling control message for session {}", sessionId, e)
                        sendError(session, "控制消息处理失败: ${e.message}")
                    }
                }

                else -> log.warn("Unknown message type: {} for session {}", type, sessionId)
            }
        } catch (e: Exception) {
            log.error("Error handling message for session {}", sessionId, e)
            sendError(session, "消息处理失败: ${e.message}")
        }
    }

    /**
     * 容器销毁：停止线程池
     */
    override fun destroy() {
        voicePipelineExecutor.shutdownNow()
        utteranceMergeScheduler.shutdownNow()
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * WebSocket 断连：停止 ASR、清理状态并兜底结束进行中的会话
     */
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val sessionId = extractSessionId(session)
        try {
            sessions.remove(sessionId)
            val removedState = sessionStates.remove(sessionId)
            removedState?.getProcessingThread()?.interrupt()
            lastActivityTime.remove(sessionId)

            // Stop STT transcription
            sttService.stopTranscription(sessionId)
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status)

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId)
            } catch (endEx: Exception) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.message)
            }
        } catch (e: Exception) {
            log.error("Error cleaning up session {} after close", sessionId, e)
        }
    }

    /**
     * WebSocket 传输异常
     */
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception)
    }

    /**
     * 启动 DashScope ASR
     */
    private fun startDashScopeStt(sessionId: String, session: WebSocketSession) {
        sttService.startTranscription(
            sessionId,
            { text -> handleSttResult(sessionId, text, true) },
            { text -> handleSttResult(sessionId, text, false) },
            { sendAsrReady(session) },
            { error ->
                log.error("STT error for session {}", sessionId, error)
                sendError(session, "语音识别失败: ${error.message}")
            }
        )

        scheduleAsrReadyCheck(sessionId, session, 0)
    }

    /**
     * 延迟检查 ASR 是否就绪
     */
    private fun scheduleAsrReadyCheck(sessionId: String, session: WebSocketSession, retryCount: Int) {
        utteranceMergeScheduler.schedule(
            { checkAsrReadyOrRetry(sessionId, session, retryCount) },
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * 检查 ASR 就绪状态，未就绪则重连
     */
    private fun checkAsrReadyOrRetry(sessionId: String, session: WebSocketSession, retryCount: Int) {
        if (!session.isOpen || sttService.isReady(sessionId)) {
            return
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            val nextRetry = retryCount + 1
            log.warn(
                "[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY
            )
            sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连")
            restartDashScopeStt(sessionId)
            scheduleAsrReadyCheck(sessionId, session, nextRetry)
            return
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount)
        sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试")
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）
     */
    private fun restartDashScopeStt(sessionId: String) {
        val session = sessions[sessionId]
        if (session == null || !session.isOpen) {
            return
        }
        sttService.restartTranscription(
            sessionId,
            { text -> handleSttResult(sessionId, text, true) },
            { text -> handleSttResult(sessionId, text, false) },
            { sendAsrReady(session) },
            { error ->
                log.error("STT error for session {}", sessionId, error)
                sendError(session, "语音识别失败: ${error.message}")
            }
        )
    }

    /**
     * Handle user audio message
     * Just send audio to STT transcriber (results come via callback)
     * 处理用户音频：解码 Base64 后转发给 ASR
     */
    private fun handleUserAudio(sessionId: String, base64Audio: String) {
        val session = sessions[sessionId]
        if (session == null) {
            log.warn("Session not found: {}", sessionId)
            return
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        val state = sessionStates[sessionId]
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return
        }

        try {
            val audioData = Base64.getDecoder().decode(base64Audio)
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.size)

            try {
                sttService.sendAudio(sessionId, audioData)
            } catch (ex: IllegalStateException) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId)
                    return
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn(
                        "[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                        sessionId, ex.message ?: "unknown"
                    )
                    restartDashScopeStt(sessionId)
                    var sent = false
                    for (i in 0 until 15) {
                        try {
                            Thread.sleep(80)
                            sttService.sendAudio(sessionId, audioData)
                            sent = true
                            break
                        } catch (retry: IllegalStateException) {
                            if (isAsrNotReady(retry)) {
                                continue
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId)
                        sendError(session, "语音识别连接中断，请刷新页面后重试")
                    }
                } else {
                    throw ex
                }
            }
        } catch (e: Exception) {
            log.error("Error handling user audio for session {}", sessionId, e)
            val errorMessage = getErrorMessage(e)
            sendError(session, errorMessage)
        }
    }

    /**
     * Handle STT result from callback (partial = live; final = committed segment for LLM).
     * 处理 ASR 结果：partial 推字幕，final 合并到缓冲区
     */
    private fun handleSttResult(sessionId: String, recognizedText: String, isFinalSegment: Boolean) {
        val session = sessions[sessionId]
        val state = sessionStates[sessionId]

        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId)
            return
        }

        // 用户已提交或 AI 正在回答时，丢弃上一轮迟到的 partial/final，防止污染下一轮字幕。
        if (state.isProcessing().get() || state.isAiSpeakingOrCooldown()) {
            log.debug(
                "Discarding late STT result for session {}, final={}, textLength={}",
                sessionId, isFinalSegment, recognizedText.length
            )
            return
        }

        if (!isFinalSegment) {
            state.markSttActivity()
            sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false)
            return
        }

        log.debug("STT final segment for session {}: textLength={}", sessionId, recognizedText.length)
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received")

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText)
        sendSubtitle(session, state.getMergeBufferPreview(), false)
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    private fun flushMergedUtteranceToLlm(sessionId: String) {
        val session = sessions[sessionId]
        val state = sessionStates[sessionId]
        if (session == null || state == null || !session.isOpen) {
            return
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                { flushMergedUtteranceToLlm(sessionId) },
                400,
                TimeUnit.MILLISECONDS
            )
            return
        }
        val mergeStartAt = state.getMergeStartedAt()
        val userText = state.takeMergeBufferAndClear()
        if (userText.isNullOrBlank()) {
            state.isProcessing().set(false)
            return
        }
        val mergeWaitMs = maxOf(0, System.currentTimeMillis() - mergeStartAt)
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success")
        state.setAccumulatedText(userText)
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length)

        // 提交到独立线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voicePipelineExecutor.execute {
            state.setProcessingThread(Thread.currentThread())
            try {
                triggerLlmResponse(sessionId, session, state)
            } finally {
                state.isProcessing().set(false)
                state.setProcessingThread(null)
            }
        }
    }

    /**
     * Trigger LLM response for completed sentence.
     * When streaming is enabled, uses sentence-level TTS overlap: each detected sentence
     * triggers a concurrent TTS call, so TTS runs in parallel with the rest of LLM generation.
     * 触发 LLM 回复（流式/非流式），并按配置合成/推送音频
     */
    private fun triggerLlmResponse(sessionId: String, session: WebSocketSession, state: SessionState) {
        val turnStartNanos = System.nanoTime()
        state.aiSpeaking.set(true)
        try {
            if (!session.isOpen) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId)
                return
            }

            val userText = state.getAccumulatedText()
            if (userText.isBlank()) {
                log.warn("Empty user text, skipping LLM response")
                return
            }

            log.info("Getting LLM response for session {}, textLength={}", sessionId, userText.length)

            val sessionEntity = getSessionEntity(sessionId)
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId)
                sendError(session, "会话不存在，请重新开始面试")
                return
            }

            val conversationHistory = getHistory(sessionId, sessionEntity.llmProvider)

            val llmStartNanos = System.nanoTime()
            val firstTokenAtNanos = AtomicLong(0)
            val streamEnabled = voiceInterviewProperties.llmStreamingEnabled
            val aiReply: String

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                val ttsSemaphore = Semaphore(
                    maxOf(1, voiceInterviewProperties.maxConcurrentTtsPerSession)
                )
                val chunkedEnabled = voiceInterviewProperties.chunkedAudioEnabled
                val ttsTimeoutSec = maxOf(5, voiceInterviewProperties.ttsTimeoutSeconds).toLong()
                val chunkEmitter = if (chunkedEnabled) {
                    OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                } else {
                    null
                }
                val ttsFutures = mutableListOf<CompletableFuture<ByteArray>>()

                aiReply = llmService.chatStreamSentences(
                    userText,
                    { partialText ->
                        if (!partialText.isNullOrBlank() && session.isOpen) {
                            if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                                recordTimerSinceNanos(
                                    "app.voice.interview.llm.first_token_latency",
                                    llmStartNanos,
                                    "status", "success"
                                )
                            }
                            sendTextMessage(session, partialText, false)
                        }
                    },
                    { sentence ->
                        if (sentence.isNotBlank()) {
                            if (chunkEmitter != null) {
                                chunkEmitter.submit(sentence)
                            } else {
                                ttsSemaphore.acquireUninterruptibly()
                                val future = CompletableFuture.supplyAsync({
                                    try {
                                        ttsService.synthesize(sentence)
                                    } finally {
                                        ttsSemaphore.release()
                                    }
                                }, voicePipelineExecutor)
                                ttsFutures.add(future)
                            }
                        }
                    },
                    sessionEntity,
                    conversationHistory
                )

                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success")
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true")
                log.info("LLM response for session {}: replyLength={}", sessionId, aiReply.length)

                if (!session.isOpen) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId)
                    return
                }

                sendSubtitle(session, userText, true)
                sendTextMessage(session, aiReply, true)
                saveMessage(sessionId, userText, aiReply)

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (chunkEmitter != null) {
                    val ttsStartNanos = System.nanoTime()
                    chunkEmitter.finish()
                    val emittedChunks = chunkEmitter.awaitCompletion()
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success")
                    if (emittedChunks == 0 && session.isOpen) {
                        log.info(
                            "[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                            sessionId
                        )
                        try {
                            val fallbackPcm = ttsService.synthesize(aiReply)
                            if (fallbackPcm.isNotEmpty()) {
                                sendAudio(session, convertPcmToWav(fallbackPcm), aiReply)
                            }
                        } catch (e: Exception) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.message)
                        }
                    }
                } else if (ttsFutures.isNotEmpty()) {
                    val ttsStartNanos = System.nanoTime()
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    val pcmChunks = mutableListOf<ByteArray>()
                    var totalSize = 0
                    var failedCount = 0
                    var audioSentByFallback = false
                    for (f in ttsFutures) {
                        try {
                            val pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS)
                            if (pcm != null && pcm.isNotEmpty()) {
                                pcmChunks.add(pcm)
                                totalSize += pcm.size
                            }
                        } catch (e: Exception) {
                            f.cancel(true)
                            failedCount++
                            log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.message)
                        }
                    }
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success")

                    if (!session.isOpen) {
                        log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId)
                        return
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && session.isOpen) {
                        log.info(
                            "[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                            sessionId, failedCount
                        )
                        try {
                            val fallbackPcm = ttsService.synthesize(aiReply)
                            if (fallbackPcm.isNotEmpty()) {
                                val wavAudio = convertPcmToWav(fallbackPcm)
                                log.info(
                                    "[Session: {}] Fallback TTS succeeded, WAV size: {} bytes",
                                    sessionId, wavAudio.size
                                )
                                sendAudio(session, wavAudio, aiReply)
                                audioSentByFallback = true
                            }
                        } catch (e: Exception) {
                            log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.message)
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen) {
                            val mergedPcm = ByteArray(totalSize)
                            var offset = 0
                            for (chunk in pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.size)
                                offset += chunk.size
                            }
                            val wavAudio = convertPcmToWav(mergedPcm)
                            log.info(
                                "[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                sessionId, pcmChunks.size, wavAudio.size
                            )
                            sendAudio(session, wavAudio, aiReply)
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId)
                            incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty")
                        }
                    }
                }
            } else {
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory)
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success")
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false")
                log.info("LLM response for session {}: replyLength={}", sessionId, aiReply.length)

                if (!session.isOpen) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId)
                    return
                }

                sendSubtitle(session, userText, true)
                sendTextMessage(session, aiReply, true)
                saveMessage(sessionId, userText, aiReply)

                val ttsStartNanos = System.nanoTime()
                log.info(
                    "[Session: {}] Starting TTS synthesis for text (length: {})",
                    sessionId, aiReply.length
                )
                val aiAudio = ttsService.synthesize(aiReply)
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success")

                if (!session.isOpen) {
                    return
                }

                if (aiAudio.isEmpty()) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId)
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty")
                } else {
                    val wavAudio = convertPcmToWav(aiAudio)
                    sendAudio(session, wavAudio, aiReply)
                }
            }

            state.setAccumulatedText("")
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success")
            incrementCounter("app.voice.interview.turn.completed", "status", "success")
        } catch (e: Exception) {
            log.error("Error triggering LLM response for session {}", sessionId, e)
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure")
            incrementCounter("app.voice.interview.turn.completed", "status", "failure")
            incrementCounter("app.voice.interview.errors", "stage", "turn")
            if (session.isOpen) {
                sendError(session, "AI响应失败: ${e.message}")
            }
        } finally {
            state.aiSpeaking.set(false)
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS)
        }
    }

    /**
     * Convert exception to user-friendly error message
     * 将异常转换为用户可读提示
     */
    private fun getErrorMessage(e: Exception): String {
        val cause = e.cause

        // Check for specific Aliyun errors
        if (cause != null) {
            val message = cause.message
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY"
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试"
                }
            }
        }

        // Default error message
        return "语音处理失败：${e.message}"
    }

    /**
     * Handle control message (end_interview, start_phase)
     * 处理控制消息
     */
    private fun handleControl(sessionId: String, control: WebSocketControlMessage) {
        log.info(
            "Control message for session {}: action={}, phase={}",
            sessionId, control.action, control.phase
        )

        when (control.action) {
            "submit" -> {
                val textObj = control.data?.get("text")
                if (textObj is String && textObj.isNotBlank()) {
                    sessionStates[sessionId]?.setMergeBufferDirectly(textObj)
                }
                flushMergedUtteranceToLlm(sessionId)
            }

            "end_interview" -> interviewService.endSession(sessionId)

            "start_phase" -> interviewService.startPhase(sessionId, control.phase ?: "")
        }
    }

    /**
     * 推送字幕
     */
    private fun sendSubtitle(session: WebSocketSession, text: String?, isFinal: Boolean) {
        val subtitle = WebSocketSubtitleMessage(
            type = "subtitle",
            text = text,
            isFinal = isFinal
        )
        sendMessage(session, toJson(subtitle))
    }

    /**
     * 推送完整音频（WAV + 对应文本）
     */
    private fun sendAudio(session: WebSocketSession, audio: ByteArray, text: String) {
        if (!session.isOpen) {
            return
        }
        val base64Audio = Base64.getEncoder().encodeToString(audio)
        log.info(
            "Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
            audio.size, base64Audio.length
        )
        sendMessage(
            session,
            toJson(
                mapOf(
                    "type" to "audio",
                    "data" to base64Audio,
                    "text" to text
                )
            )
        )
    }

    /**
     * 推送增量文本
     */
    private fun sendTextMessage(session: WebSocketSession, text: String) {
        sendTextMessage(session, text, false)
    }

    /**
     * 推送文本（可标记 final）
     */
    private fun sendTextMessage(session: WebSocketSession, text: String, isFinal: Boolean) {
        sendMessage(
            session,
            toJson(
                mapOf(
                    "type" to "text",
                    "content" to text,
                    "final" to isFinal
                )
            )
        )
    }

    /**
     * 推送错误消息
     */
    private fun sendError(session: WebSocketSession, error: String?) {
        sendMessage(session, toJson(mapOf("type" to "error", "message" to error)))
    }

    /**
     * 推送 ASR 就绪状态
     */
    private fun sendAsrReady(session: WebSocketSession) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪")
    }

    /**
     * 推送 ASR 状态控制消息
     */
    private fun sendAsrStatus(session: WebSocketSession?, action: String, message: String?) {
        if (session == null || !session.isOpen) {
            return
        }
        sendMessage(
            session,
            toJson(
                mapOf(
                    "type" to "control",
                    "action" to action,
                    "message" to message,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        )
    }

    /**
     * 推送单个音频分块（流式 TTS）
     */
    private fun sendAudioChunk(session: WebSocketSession, wavAudio: ByteArray, index: Int, isLast: Boolean) {
        if (!session.isOpen) {
            return
        }
        val base64Audio = Base64.getEncoder().encodeToString(wavAudio)
        sendMessage(
            session,
            toJson(
                mapOf(
                    "type" to "audio_chunk",
                    "data" to base64Audio,
                    "index" to index,
                    "isLast" to isLast
                )
            )
        )
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes", index, isLast, wavAudio.size)
    }

    /**
     * 推送音频播放完成
     */
    private fun sendAudioComplete(session: WebSocketSession?) {
        if (session == null || !session.isOpen) {
            return
        }
        sendMessage(
            session,
            toJson(
                mapOf(
                    "type" to "control",
                    "action" to "audio_complete",
                    "message" to "面试官语音播放完成",
                    "timestamp" to System.currentTimeMillis()
                )
            )
        )
    }

    /**
     * 有序 TTS 分块发射器：句子并发合成，按索引顺序推送
     */
    private inner class OrderedTtsChunkEmitter(
        private val sessionId: String, // 会话ID
        private val session: WebSocketSession, // WebSocket 会话
        private val ttsSemaphore: Semaphore, // 并发 TTS 信号量
        private val ttsTimeoutSec: Long // 单句 TTS 超时（秒）
    ) {

        private val futures: ConcurrentHashMap<Int, CompletableFuture<ByteArray>> = ConcurrentHashMap() // 分块结果
        private val nextIndex = AtomicInteger() // 下一个分块索引
        private val emittedChunks = AtomicInteger() // 已推送分块数
        private val lock = java.lang.Object() // 等待/通知锁
        private val completion: CompletableFuture<Int> // 排空完成信号
        @Volatile
        private var totalChunks = -1 // 总分块数（-1 表示未结束）

        init {
            completion = CompletableFuture.supplyAsync({ drainChunks() }, voicePipelineExecutor)
        }

        /**
         * 提交一个句子进行 TTS 合成
         */
        fun submit(sentence: String) {
            val index = nextIndex.getAndIncrement()
            ttsSemaphore.acquireUninterruptibly()
            val future = CompletableFuture.supplyAsync({
                try {
                    ttsService.synthesize(sentence)
                } finally {
                    ttsSemaphore.release()
                }
            }, voicePipelineExecutor)

            futures[index] = future
            synchronized(lock) {
                lock.notifyAll()
            }
        }

        /**
         * 标记所有句子已提交
         */
        fun finish() {
            synchronized(lock) {
                totalChunks = nextIndex.get()
                lock.notifyAll()
            }
        }

        /**
         * 等待所有分块推送完成
         */
        fun awaitCompletion(): Int {
            val timeoutSec = maxOf(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * maxOf(1, nextIndex.get()))
            return try {
                completion.get(timeoutSec, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.warn(
                    "[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                    sessionId, e.message
                )
                completion.cancel(true)
                val emitted = emittedChunks.get()
                if (emitted > 0) {
                    sendAudioComplete(session)
                }
                emitted
            }
        }

        /**
         * 按索引顺序排空并推送分块
         */
        private fun drainChunks(): Int {
            var index = 0
            try {
                while (true) {
                    val future = waitForFuture(index)
                    if (future == null) {
                        val emitted = emittedChunks.get()
                        if (emitted > 0) {
                            sendAudioComplete(session)
                        }
                        return emitted
                    }

                    try {
                        val pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS)
                        if (pcm != null && pcm.isNotEmpty() && session.isOpen) {
                            sendAudioChunk(session, convertPcmToWav(pcm), index, false)
                            emittedChunks.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        future.cancel(true)
                        log.warn("[Session: {}] Streaming TTS chunk {} failed", sessionId, index, e)
                    } finally {
                        futures.remove(index)
                        index++
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId)
                val emitted = emittedChunks.get()
                if (emitted > 0) {
                    sendAudioComplete(session)
                }
                return emitted
            }
        }

        /**
         * 等待指定索引的分块结果，全部提交完成后返回 null
         */
        private fun waitForFuture(index: Int): CompletableFuture<ByteArray>? {
            synchronized(lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null
                    }
                    lock.wait(100)
                }
                return futures[index]
            }
        }
    }

    /**
     * 记录耗时指标（纳秒起点）
     */
    private fun recordTimerSinceNanos(metricName: String, startNanos: Long, vararg tags: String) {
        val registry = getRegistry() ?: return
        val elapsed = maxOf(0, System.nanoTime() - startNanos)
        registry.timer(metricName, *tags).record(elapsed, TimeUnit.NANOSECONDS)
    }

    /**
     * 记录耗时指标（毫秒）
     */
    private fun recordTimerMillis(metricName: String, millis: Long, vararg tags: String) {
        val registry = getRegistry() ?: return
        registry.timer(metricName, *tags).record(maxOf(0, millis), TimeUnit.MILLISECONDS)
    }

    /**
     * 递增计数器指标
     */
    private fun incrementCounter(metricName: String, vararg tags: String) {
        val registry = getRegistry() ?: return
        registry.counter(metricName, *tags).increment()
    }

    /**
     * 获取 MeterRegistry（可选）
     */
    private fun getRegistry(): MeterRegistry? {
        return meterRegistryProvider.getIfAvailable()
    }

    /**
     * Scheduled task to check for pause warnings and timeouts
     * Runs every 30 seconds
     * 定时任务：检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000L)
    fun checkPauseTimeout() {
        val now = System.currentTimeMillis()

        lastActivityTime.forEach { (sessionId, lastTime) ->
            val elapsed = now - lastTime

            // Send warning at 4:30
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId)
            }
            // Timeout at 5:00
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn(
                    "Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000
                )
                handlePauseTimeout(sessionId)
            }
        }
    }

    /**
     * 定时清理超时会话与卡住的评估任务
     */
    @Scheduled(fixedRate = 60000L)
    fun cleanupStaleSessions() {
        try {
            val cleaned = interviewService.cleanupStaleSessions()
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned)
            }
        } catch (e: Exception) {
            log.error("Error during stale session cleanup", e)
        }
    }

    /**
     * Send pause warning notification
     * 发送暂停警告通知
     */
    private fun sendPauseWarning(sessionId: String) {
        val session = sessions[sessionId]
        if (session != null && session.isOpen) {
            sendMessage(
                session,
                toJson(
                    mapOf(
                        "type" to "control",
                        "action" to "pause_timeout_warning",
                        "message" to "会话将在30秒后暂停，请继续说话或点击继续",
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            )
        }
    }

    /**
     * Handle pause timeout - save state and disconnect
     * 处理暂停超时 - 保存状态并断开连接
     */
    private fun handlePauseTimeout(sessionId: String) {
        val session = sessions[sessionId]

        try {
            if (session != null && session.isOpen) {
                sendMessage(
                    session,
                    toJson(
                        mapOf(
                            "type" to "control",
                            "action" to "pause_timeout",
                            "message" to "会话因超时已暂停,可在历史记录中恢复",
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                )
            }

            // 2. Save session state to database
            interviewService.pauseSession(sessionId, "timeout")

            // 3. Close WebSocket connection
            if (session != null && session.isOpen) {
                session.close(CloseStatus.GOING_AWAY)
            }

            // 4. Cleanup - Stop ASR session to prevent resource leak
            sttService.stopTranscription(sessionId)
            sessions.remove(sessionId)
            sessionStates.remove(sessionId)
            lastActivityTime.remove(sessionId)

            log.info("Session {} paused due to timeout", sessionId)
        } catch (e: Exception) {
            log.error("Error handling pause timeout for session {}", sessionId, e)
        }
    }

    /**
     * Extract session ID from WebSocket URI path
     * Path format: /ws/voice-interview/{sessionId}
     */
    private fun extractSessionId(session: WebSocketSession): String {
        val path = session.uri?.path ?: ""
        return path.substring(path.lastIndexOf('/') + 1)
    }

    /**
     * Get chat history for session.
     * 加载对话历史并进行上下文压缩（滑动窗口 + 可选增量摘要），再格式化为文本行。
     * 当 contextCompression.enabled=false 时行为与改前完全一致（返回全量格式化轮次）。
     */
    private fun getHistory(sessionId: String, llmProvider: String?): List<String> {
        return try {
            val history = voiceHistoryLoader.loadHistory(sessionId, llmProvider)
            log.debug("Loaded {} compressed history entries for session {}", history.size, sessionId)
            history
        } catch (e: Exception) {
            log.error("Error loading conversation history for session {}", sessionId, e)
            mutableListOf<String>()
        }
    }

    /**
     * Get session entity from database
     * 从数据库读取会话实体
     */
    private fun getSessionEntity(sessionId: String): VoiceInterviewSessionEntity? {
        return try {
            interviewService.getSession(sessionId.toLong())
        } catch (e: NumberFormatException) {
            log.error("Invalid session ID format: {}", sessionId)
            null
        }
    }

    /**
     * Save message to database
     * 保存消息到数据库（失败只记日志，不中断语音管线）
     */
    private fun saveMessage(sessionId: String, userText: String?, aiText: String?) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText)
            log.debug("Message saved to database for session: {}", sessionId)
        } catch (e: Exception) {
            log.error("Error saving message for session {}", sessionId, e)
        }
    }

    /**
     * Convert PCM audio to WAV format
     * Adds 44-byte WAV header to PCM data for browser playback
     *
     * @param pcmData Raw PCM audio data (24kHz, 16-bit, mono)
     * @return WAV formatted audio data
     */
    private fun convertPcmToWav(pcmData: ByteArray): ByteArray {
        // Use 24000Hz for Qwen TTS Realtime API
        val sampleRate = 24000
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = dataSize + 36

        val wavData = ByteArray(dataSize + 44)

        // Write WAV header directly to avoid stream allocation overhead
        var pos = 0

        // RIFF header
        wavData[pos++] = 'R'.code.toByte()
        wavData[pos++] = 'I'.code.toByte()
        wavData[pos++] = 'F'.code.toByte()
        wavData[pos++] = 'F'.code.toByte()
        writeIntLE(wavData, pos, fileSize)
        pos += 4
        wavData[pos++] = 'W'.code.toByte()
        wavData[pos++] = 'A'.code.toByte()
        wavData[pos++] = 'V'.code.toByte()
        wavData[pos++] = 'E'.code.toByte()

        // fmt chunk
        wavData[pos++] = 'f'.code.toByte()
        wavData[pos++] = 'm'.code.toByte()
        wavData[pos++] = 't'.code.toByte()
        wavData[pos++] = ' '.code.toByte()
        writeIntLE(wavData, pos, 16) // Chunk size
        pos += 4
        writeShortLE(wavData, pos, 1.toShort()) // Audio format (1 = PCM)
        pos += 2
        writeShortLE(wavData, pos, numChannels.toShort())
        pos += 2
        writeIntLE(wavData, pos, sampleRate)
        pos += 4
        writeIntLE(wavData, pos, byteRate)
        pos += 4
        writeShortLE(wavData, pos, blockAlign.toShort())
        pos += 2
        writeShortLE(wavData, pos, bitsPerSample.toShort())
        pos += 2

        // data chunk
        wavData[pos++] = 'd'.code.toByte()
        wavData[pos++] = 'a'.code.toByte()
        wavData[pos++] = 't'.code.toByte()
        wavData[pos++] = 'a'.code.toByte()
        writeIntLE(wavData, pos, dataSize)
        pos += 4

        // Copy PCM data
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.size)

        return wavData
    }

    /**
     * Write 32-bit integer in little-endian format
     * 以小端序写入 32 位整数
     */
    private fun writeIntLE(buf: ByteArray, pos: Int, value: Int) {
        buf[pos] = (value and 0xFF).toByte()
        buf[pos + 1] = ((value shr 8) and 0xFF).toByte()
        buf[pos + 2] = ((value shr 16) and 0xFF).toByte()
        buf[pos + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Write 16-bit short in little-endian format
     * 以小端序写入 16 位整数
     */
    private fun writeShortLE(buf: ByteArray, pos: Int, value: Short) {
        buf[pos] = (value.toInt() and 0xFF).toByte()
        buf[pos + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    /**
     * Internal class to hold session state
     * 会话运行态（线程安全）
     */
    private class SessionState {
        private val accumulatedText = AtomicReference("") // 本轮用户累计文本
        private val processing = AtomicBoolean(false) // 是否正在执行 LLM+TTS 管线
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        val aiSpeaking = AtomicBoolean(false)
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        val aiSpeakEndAt = AtomicLong(0)
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private val mergeBuffer = AtomicReference("")
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private val mergeStartedAt = AtomicLong(0)
        /** 最近一次 STT 活动时间（partial/final） */
        private val lastSttActivityAt = AtomicLong(System.currentTimeMillis())
        /** 当前正在执行 LLM+TTS 管线的线程，断连时可中断 */
        @Volatile
        private var processingThread: Thread? = null

        /**
         * 追加一段 STT 定稿文本
         */
        fun appendFinalSttSegment(segment: String?) {
            val s = segment?.trim() ?: ""
            if (s.isEmpty()) {
                return
            }
            mergeBuffer.updateAndGet { prev ->
                if (prev.isNullOrEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis())
                    s
                } else {
                    joinSegments(prev, s)
                }
            }
            markSttActivity()
        }

        /**
         * 合并两段识别文本，避免重复与不当标点
         */
        private fun joinSegments(previous: String, next: String): String {
            val trimmedPrevious = previous.trim()
            val trimmedNext = next.trim()
            if (trimmedNext == trimmedPrevious || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext
            }
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious
            }
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！") ||
                trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".") ||
                trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")
            ) {
                return "$trimmedPrevious $trimmedNext"
            }
            return "$trimmedPrevious，$trimmedNext"
        }

        /**
         * 当前合并缓冲预览
         */
        fun getMergeBufferPreview(): String {
            return mergeBuffer.get() ?: ""
        }

        /**
         * 直接设置合并缓冲（前端手动 submit 时使用）
         */
        fun setMergeBufferDirectly(text: String?) {
            val s = text?.trim() ?: ""
            if (s.isEmpty()) {
                return
            }
            mergeBuffer.set(s)
            if (mergeStartedAt.get() == 0L) {
                mergeStartedAt.set(System.currentTimeMillis())
            }
        }

        /**
         * 合并缓冲预览 + 当前 partial 文本
         */
        fun getMergeBufferPreviewWithPartial(partial: String?): String {
            val current = partial?.trim() ?: ""
            if (current.isEmpty()) {
                return getMergeBufferPreview()
            }

            val confirmed = getMergeBufferPreview()
            if (confirmed.isBlank()) {
                return current
            }
            return joinSegments(confirmed, current)
        }

        /**
         * 取出合并缓冲并清空
         */
        fun takeMergeBufferAndClear(): String {
            mergeStartedAt.set(0)
            return mergeBuffer.getAndSet("")
        }

        /**
         * 标记 STT 活动时间
         */
        fun markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis())
        }

        /**
         * 获取合并开始时间（未开始则返回当前时间）
         */
        fun getMergeStartedAt(): Long {
            val value = mergeStartedAt.get()
            return if (value > 0) value else System.currentTimeMillis()
        }

        /**
         * 最近一次 STT 活动时间
         */
        fun getLastSttActivityAt(): Long {
            return lastSttActivityAt.get()
        }

        /**
         * 当前累计用户文本
         */
        fun getAccumulatedText(): String {
            return accumulatedText.get()
        }

        /**
         * 设置累计用户文本
         */
        fun setAccumulatedText(text: String) {
            accumulatedText.set(text)
        }

        /**
         * 处理中标记（AtomicBoolean，供 compareAndSet 使用）
         */
        fun isProcessing(): AtomicBoolean {
            return processing
        }

        /**
         * 记录当前管线线程
         */
        fun setProcessingThread(t: Thread?) {
            processingThread = t
        }

        /**
         * 获取当前管线线程
         */
        fun getProcessingThread(): Thread? {
            return processingThread
        }

        /**
         * AI 正在说话或处于回声冷却期
         */
        fun isAiSpeakingOrCooldown(): Boolean {
            if (aiSpeaking.get()) {
                return true
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get()
        }
    }
}

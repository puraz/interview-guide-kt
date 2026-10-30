package interview.guide.modules.voiceinterview.service

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Qwen3 Realtime ASR Service
 *
 * Provides real-time speech recognition using Alibaba Cloud DashScope's qwen3-asr-flash-realtime model.
 * This service manages WebSocket connections for multiple concurrent sessions and handles
 * audio transcription with server-side Voice Activity Detection (VAD).
 *
 * Key Features:
 * - Multi-session management with thread-safe concurrent map
 * - Server-side VAD with 400ms silence duration for automatic sentence detection
 * - Callback-based result handling for real-time transcription updates
 * - Automatic resource cleanup on session termination
 *
 * Configuration:
 * - Model: qwen3-asr-flash-realtime
 * - Audio format: PCM, 16kHz sample rate
 * - Language: Chinese (zh)
 * - VAD: Enabled with server_vad type
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Service
class QwenAsrService(
    voiceInterviewProperties: VoiceInterviewProperties // 语音面试配置
) {

    private val log = LoggerFactory.getLogger(QwenAsrService::class.java)

    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    var url: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime" // WebSocket 地址

    var model: String = "qwen3-asr-flash-realtime" // 模型名称

    var apiKey: String? = null // DashScope API Key

    var language: String = "zh" // 识别语言

    var format: String = "pcm" // 音频格式

    var sampleRate: Int = 16000 // 采样率

    var enableTurnDetection: Boolean = true // 是否启用 VAD

    var turnDetectionType: String = "server_vad" // VAD 类型

    var turnDetectionThreshold: Float = 0.0f // VAD 阈值

    var turnDetectionSilenceDurationMs: Int = 1000 // 静音判定时长（毫秒）

    init {
        applyAsrConfig(voiceInterviewProperties.qwen.asr)
    }

    /**
     * 重新加载 ASR 配置
     */
    fun reload(voiceInterviewProperties: VoiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.qwen.asr)
        log.info("QwenAsrService reloaded: model={}, url={}", model, url)
    }

    /**
     * 应用 ASR 配置
     */
    private fun applyAsrConfig(asr: VoiceInterviewProperties.AsrConfig) {
        url = asr.url
        model = asr.model
        apiKey = asr.apiKey
        language = asr.language
        format = asr.format
        sampleRate = asr.sampleRate
        enableTurnDetection = asr.enableTurnDetection
        turnDetectionType = asr.turnDetectionType
        turnDetectionThreshold = asr.turnDetectionThreshold
        turnDetectionSilenceDurationMs = asr.turnDetectionSilenceDurationMs
    }

    /**
     * Active ASR sessions map.
     * Key: session ID (user-provided identifier)
     * Value: AsrSession containing the OmniRealtimeConversation instance and callbacks
     */
    private val sessions: ConcurrentHashMap<String, AsrSession> = ConcurrentHashMap() // 活跃 ASR 会话

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时与 [sessionLocks] 配合 */
    private val sessionLocks: ConcurrentHashMap<String, Any> = ConcurrentHashMap() // 每会话互斥锁

    /**
     * 获取会话锁对象
     */
    private fun lockForSession(sessionId: String): Any {
        return sessionLocks.computeIfAbsent(sessionId) { Any() }
    }

    /**
     * Initialize the ASR service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    fun init() {
        val key = apiKey
        if (key == null || key.trim().isEmpty()) {
            throw IllegalStateException("API key must be configured before initializing QwenAsrService")
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url)
    }

    /**
     * Start a new transcription session.
     *
     * This method creates a new WebSocket connection to the DashScope ASR service
     * and sets up callbacks for handling transcription results and errors.
     *
     * The session uses server-side VAD (Voice Activity Detection) to automatically
     * detect sentence boundaries. When speech is detected and transcribed, the
     * onResult callback will be invoked with the transcribed text.
     *
     * @param sessionId Unique identifier for this session
     * @param onFinal Callback when a sentence/segment is finalized (`completed` event)
     * @param onError Callback invoked when errors occur
     * @throws IllegalStateException if session already exists or service not initialized
     */
    fun startTranscription(
        sessionId: String,
        onFinal: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        startTranscription(sessionId, onFinal, null, onError)
    }

    /**
     * Same as [startTranscription] but forwards partial transcripts
     * (`conversation.item.input_audio_transcription.text`) for live subtitles.
     *
     * @param onPartial May be null if partials are not needed
     */
    fun startTranscription(
        sessionId: String,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        startTranscription(sessionId, onFinal, onPartial, null, onError)
    }

    /**
     * 启动 ASR（带 ready 回调）
     */
    fun startTranscription(
        sessionId: String,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onReady: (() -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        synchronized(lockForSession(sessionId)) {
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError)
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     */
    fun restartTranscription(
        sessionId: String,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        restartTranscription(sessionId, onFinal, onPartial, null, onError)
    }

    /**
     * 停止旧连接并重新建立（带 ready 回调）
     */
    fun restartTranscription(
        sessionId: String,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onReady: (() -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        synchronized(lockForSession(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId)
            stopTranscription(sessionId)
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError)

            // Verify reconnection succeeded
            for (attempt in 0 until 10) {
                try {
                    Thread.sleep(100)
                    val newSession = sessions[sessionId]
                    if (newSession != null && newSession.isReady) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId)
                        return
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId)
                    return
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId)
        }
    }

    /**
     * 在会话锁内创建 ASR 连接
     */
    private fun startTranscriptionLocked(
        sessionId: String,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onReady: (() -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        if (sessions.containsKey(sessionId)) {
            throw IllegalStateException("Session already exists: $sessionId")
        }

        try {
            // Build OmniRealtimeParam with connection settings
            val param = OmniRealtimeParam.builder()
                .model(model)
                .url(url)
                .apikey(apiKey)
                .build()

            val conversationRef = AtomicReference<OmniRealtimeConversation?>()

            // Create callback handler for WebSocket events
            val callback = object : OmniRealtimeCallback() {
                override fun onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId)
                }

                override fun onEvent(message: JsonObject) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError)
                }

                override fun onClose(code: Int, reason: String?) {
                    val closed = conversationRef.get()
                    log.warn(
                        "[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                        sessionId, code, reason
                    )
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）
                    sessions.compute(sessionId) { _, existing ->
                        if (existing != null && closed != null && existing.conversation === closed) {
                            null
                        } else {
                            existing
                        }
                    }
                }
            }

            // Create OmniRealtimeConversation instance
            val conversation = OmniRealtimeConversation(param, callback)
            conversationRef.set(conversation)
            val asrSession = AsrSession(conversation, onFinal, onPartial, onError)

            // Store session in map BEFORE connecting to ensure hasActiveSession() returns true
            sessions[sessionId] = asrSession

            // Connect to server asynchronously (non-blocking)
            val connectionThread = Thread({
                try {
                    conversation.connect()

                    // Configure session with transcription parameters
                    val transcriptionParam = OmniRealtimeTranscriptionParam()
                    transcriptionParam.language = language
                    transcriptionParam.inputSampleRate = sampleRate
                    transcriptionParam.inputAudioFormat = format

                    val config = OmniRealtimeConfig.builder()
                        .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                        .enableTurnDetection(enableTurnDetection)
                        .turnDetectionType(turnDetectionType)
                        .turnDetectionThreshold(turnDetectionThreshold)
                        .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs)
                        .transcriptionConfig(transcriptionParam)
                        .build()

                    // Update session with configuration
                    conversation.updateSession(config)
                    if (sessions[sessionId] !== asrSession) {
                        log.debug("[Session: {}] Ignoring stale ASR connection ready callback", sessionId)
                    } else {
                        asrSession.markReady()
                        onReady?.invoke()

                        log.info("[Session: {}] Transcription session started successfully", sessionId)
                    }
                } catch (e: Exception) {
                    log.error("[Session: {}] Failed to establish connection", sessionId, e)
                    sessions.compute(sessionId) { _, existing ->
                        if (existing != null && existing.conversation === conversation) null else existing
                    }
                    onError(e)
                }
            }, "ASR-Connection-$sessionId")
            connectionThread.isDaemon = true
            connectionThread.start()
        } catch (e: Exception) {
            val errorMsg = "Failed to create transcription session: $sessionId"
            log.error(errorMsg, e)
            sessions.remove(sessionId)
            onError(IllegalStateException(errorMsg, e))
            throw IllegalStateException(errorMsg, e)
        }
    }

    /**
     * Send audio data to the ASR service for transcription.
     *
     * The audio data should be in PCM format at 16kHz sample rate.
     * The data is Base64-encoded before being sent to the DashScope service.
     *
     * With server-side VAD enabled, the service will automatically detect
     * speech segments and trigger transcription when silence is detected.
     *
     * @param sessionId Session identifier
     * @param audioData Raw PCM audio bytes
     * @throws IllegalStateException if session does not exist
     */
    fun sendAudio(sessionId: String, audioData: ByteArray) {
        val session = sessions[sessionId]
            ?: throw IllegalStateException("No active session found: $sessionId")

        try {
            if (!session.awaitReady(1200)) {
                throw IllegalStateException("ASR session not ready: $sessionId")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("ASR session ready wait interrupted: $sessionId", e)
        }

        try {
            // Convert audio data to Base64
            val audioBase64 = Base64.getEncoder().encodeToString(audioData)

            // Send to ASR service
            session.conversation.appendAudio(audioBase64)

            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.size)
        } catch (e: Exception) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e)
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw IllegalStateException("ASR append failed: $sessionId", e)
        }
    }

    /**
     * Stop transcription and close the session.
     *
     * This method notifies the ASR service to complete any pending transcription,
     * waits for the final results, and then closes the WebSocket connection.
     *
     * @param sessionId Session identifier
     */
    fun stopTranscription(sessionId: String) {
        synchronized(lockForSession(sessionId)) {
            val session = sessions.remove(sessionId)
            // Clean up the session lock to prevent memory leak
            sessionLocks.remove(sessionId)
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId)
                return
            }

            try {
                session.conversation.endSession()
                log.info("[Session: {}] Transcription session stopped", sessionId)
            } catch (e: InterruptedException) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e)
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                log.warn(
                    "[Session: {}] Error while ending session (may already be closed): {}",
                    sessionId, e.message
                )
            }

            try {
                session.conversation.close()
            } catch (e: Exception) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.message)
            }
        }
    }

    /**
     * Check if a session with the given ID is currently active.
     *
     * @param sessionId Session identifier
     * @return true if session exists and is active, false otherwise
     */
    fun hasActiveSession(sessionId: String): Boolean {
        return sessions.containsKey(sessionId)
    }

    /**
     * ASR 连接是否已就绪
     */
    fun isReady(sessionId: String): Boolean {
        val session = sessions[sessionId]
        return session != null && session.isReady
    }

    /**
     * Destroy the service and cleanup all active sessions.
     *
     * This method is called automatically when the Spring container shuts down.
     * It stops all active sessions and releases resources.
     */
    @PreDestroy
    fun destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size)

        // Stop all active sessions
        sessions.keys.forEach { sessionId ->
            try {
                stopTranscription(sessionId)
            } catch (e: Exception) {
                log.error("[Session: {}] Error during cleanup", sessionId, e)
            }
        }

        sessions.clear()
        log.info("QwenAsrService destroyed successfully")
    }

    /**
     * Handle server events from the DashScope ASR service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - conversation.item.input_audio_transcription.completed: Final transcription result
     * - conversation.item.input_audio_transcription.text / .delta: Partial transcription (live subtitles)
     * - error: Error occurred
     *
     * @param sessionId Session identifier
     * @param message JSON event message from server
     * @param onFinal Callback for finalized segment text
     * @param onPartial Callback for streaming partial text (optional)
     * @param onError Callback for errors
     */
    private fun handleServerEvent(
        sessionId: String,
        message: JsonObject,
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)?,
        onError: (Throwable) -> Unit
    ) {
        try {
            val eventType = message.get("type").asString

            log.trace("[Session: {}] Received event: {}", sessionId, eventType)

            when (eventType) {
                "session.created" -> log.debug("[Session: {}] Session created on server", sessionId)

                "session.updated" -> log.debug("[Session: {}] Session configuration updated", sessionId)

                "conversation.item.input_audio_transcription.completed" -> {
                    // Final transcription result
                    val transcriptObj = message.asJsonObject
                    val transcript = transcriptObj.get("transcript").asString
                    val transcriptLanguage = if (transcriptObj.has("language")) {
                        transcriptObj.get("language").asString
                    } else {
                        "unknown"
                    }
                    val emotion = if (transcriptObj.has("emotion")) {
                        transcriptObj.get("emotion").asString
                    } else {
                        "neutral"
                    }

                    log.debug(
                        "[Session: {}] Transcription completed - language: {}, emotion: {}, textLength: {}",
                        sessionId, transcriptLanguage, emotion, transcript.length
                    )

                    onFinal(transcript)
                }

                "conversation.item.input_audio_transcription.text",
                "conversation.item.input_audio_transcription.delta" ->
                        dispatchPartialTranscript(sessionId, message, onPartial)

                "error" -> {
                    // Error event
                    val errorObj = message.getAsJsonObject("error")
                    val errorType = if (errorObj.has("type")) errorObj.get("type").asString else "unknown"
                    val errorCode = if (errorObj.has("code")) errorObj.get("code").asString else "unknown"
                    val errorMessage = if (errorObj.has("message")) {
                        errorObj.get("message").asString
                    } else {
                        "Unknown error"
                    }

                    val fullErrorMessage = String.format(
                        "ASR Error [%s/%s]: %s",
                        errorType, errorCode, ErrorLogSanitizer.summarize(errorMessage)
                    )
                    log.error("[Session: {}] {}", sessionId, fullErrorMessage)

                    onError(IllegalStateException(fullErrorMessage))
                }

                "session.finished" -> log.debug("[Session: {}] Session finished on server", sessionId)

                "conversation.item.input_audio_transcription.failed" ->
                        log.error("[Session: {}] ASR transcription failed (single utterance)", sessionId)

                else -> {
                    if (eventType.contains("transcription")) {
                        log.debug("[Session: {}] Unhandled transcription-related event: type={}", sessionId, eventType)
                    } else {
                        log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType)
                    }
                }
            }
        } catch (e: Exception) {
            log.error(
                "[Session: {}] Error processing server event: {}",
                sessionId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            onError(e)
        }
    }

    /**
     * Forward partial / streaming ASR text for real-time UI (VAD alone does not imply visible STT).
     */
    private fun dispatchPartialTranscript(
        sessionId: String,
        message: JsonObject,
        onPartial: ((String) -> Unit)?
    ) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId)
            return
        }
        val text = extractTranscriptPayload(message)
        if (!text.isNullOrBlank()) {
            onPartial(text)
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text", sessionId)
        }
    }

    companion object {
        /**
         * Extract displayable text from ASR JSON events.
         *
         * For `conversation.item.input_audio_transcription.text`, the official preview is
         * `text` (confirmed prefix) + `stash` (draft suffix); either may be empty.
         */
        @JvmStatic
        fun extractTranscriptPayload(message: JsonObject): String? {
            if (message.has("transcript") && !message.get("transcript").isJsonNull) {
                val el: JsonElement = message.get("transcript")
                if (el.isJsonPrimitive) {
                    return el.asString
                }
            }
            // Real-time partial: text + stash (see Alibaba qwen-asr-realtime server events doc)
            if (message.has("text") || message.has("stash")) {
                var prefix = ""
                var suffix = ""
                if (message.has("text") && !message.get("text").isJsonNull && message.get("text").isJsonPrimitive) {
                    prefix = message.get("text").asString
                }
                if (message.has("stash") && !message.get("stash").isJsonNull && message.get("stash").isJsonPrimitive) {
                    suffix = message.get("stash").asString
                }
                val combined = prefix + suffix
                if (combined.isNotBlank()) {
                    return combined
                }
            }
            if (message.has("delta")) {
                val d = message.get("delta")
                if (d.isJsonPrimitive) {
                    return d.asString
                }
                if (d.isJsonObject) {
                    val o = d.asJsonObject
                    if (o.has("text") && !o.get("text").isJsonNull) {
                        return o.get("text").asString
                    }
                    if (o.has("transcript") && !o.get("transcript").isJsonNull) {
                        return o.get("transcript").asString
                    }
                }
            }
            if (message.has("item") && message.get("item").isJsonObject) {
                val item = message.getAsJsonObject("item")
                if (item.has("transcript") && !item.get("transcript").isJsonNull) {
                    return item.get("transcript").asString
                }
            }
            return null
        }
    }

    /**
     * Internal class to hold session data.
     */
    private class AsrSession(
        val conversation: OmniRealtimeConversation, // DashScope ASR 连接
        val onFinal: (String) -> Unit, // 定稿回调
        val onPartial: ((String) -> Unit)?, // 中间结果回调
        val onError: (Throwable) -> Unit // 错误回调
    ) {
        private val readyLatch = CountDownLatch(1) // 连接就绪闩锁

        /**
         * 标记连接就绪
         */
        fun markReady() {
            readyLatch.countDown()
        }

        /**
         * 连接是否已就绪
         */
        val isReady: Boolean
            get() = readyLatch.count == 0L

        /**
         * 等待连接就绪
         */
        fun awaitReady(timeoutMs: Long): Boolean {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}

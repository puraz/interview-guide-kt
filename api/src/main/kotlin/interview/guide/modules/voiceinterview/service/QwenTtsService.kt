package interview.guide.modules.voiceinterview.service

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam
import com.google.gson.JsonObject
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import okhttp3.Response
import okhttp3.WebSocket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Qwen TTS Realtime Service (WebSocket-based)
 *
 * Provides real-time text-to-speech synthesis using Alibaba Cloud DashScope's
 * qwen-tts-realtime model via WebSocket API.
 *
 * Key Features:
 * - WebSocket-based real-time TTS synthesis
 * - User-commit mode for manual control
 * - Synchronous synthesis API with 30-second timeout protection
 * - Automatic audio chunk collection via response.audio.delta events
 * - Support for Chinese language with configurable voice, speech rate, and volume
 *
 * Configuration:
 * - Model: qwen-tts-realtime
 * - Voice: Configurable (Cherry, Serena, Ethan, etc.)
 * - Audio format: PCM, 24kHz sample rate
 * - Mode: commit (user-controlled)
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Service
class QwenTtsService(
    voiceInterviewProperties: VoiceInterviewProperties // 语音面试配置
) {

    private val log = LoggerFactory.getLogger(QwenTtsService::class.java)

    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    var model: String = "qwen3-tts-flash-realtime" // 模型名称

    var apiKey: String? = null // DashScope API Key

    var voice: String = "Cherry" // 音色

    var format: String? = null // 音频格式

    var sampleRate: Int? = null // 采样率

    var mode: String? = null // 合成模式

    var languageType: String? = null // 语言类型

    var speechRate: Float? = null // 语速

    var volume: Int? = null // 音量

    var connectTimeoutSeconds: Int = 0 // WebSocket 建连超时（秒）

    init {
        applyTtsConfig(voiceInterviewProperties)
    }

    /**
     * 重新加载 TTS 配置
     */
    fun reload(voiceInterviewProperties: VoiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties)
        log.info(
            "QwenTtsService reloaded: model={}, voice={}, connectTimeoutSeconds={}",
            model, voice, connectTimeoutSeconds
        )
    }

    /**
     * 应用 TTS 配置
     */
    private fun applyTtsConfig(voiceInterviewProperties: VoiceInterviewProperties) {
        val tts = voiceInterviewProperties.qwen.tts
        model = tts.model
        apiKey = tts.apiKey
        voice = tts.voice
        format = tts.format
        sampleRate = tts.sampleRate
        mode = tts.mode
        languageType = tts.languageType
        speechRate = tts.speechRate
        volume = tts.volume
        connectTimeoutSeconds = maxOf(1, voiceInterviewProperties.ttsConnectTimeoutSeconds)
    }

    /**
     * Initialize the TTS service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    fun init() {
        val key = apiKey
        if (key == null || key.trim().isEmpty()) {
            throw IllegalStateException("API key must be configured before initializing QwenTtsService")
        }
        log.info(
            "QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
            model, voice, sampleRate
        )
    }

    /**
     * Synthesize text to speech audio.
     *
     * This method synchronously converts text to PCM audio data using the DashScope
     * WebSocket-based TTS API. It establishes a WebSocket connection, sends the text for
     * synthesis, collects audio chunks, and returns the complete audio data.
     *
     * The method uses CountDownLatch to wait for synthesis completion with a 30-second
     * timeout to prevent indefinite blocking.
     *
     * @param text Text to synthesize (null, empty, or whitespace-only text returns empty array)
     * @return PCM audio data at configured sample rate, or empty array if synthesis fails
     */
    fun synthesize(text: String?): ByteArray {
        // Handle null, empty, or whitespace-only text
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array")
            return ByteArray(0)
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length)

        // Latch for synchronous waiting
        val synthesisLatch = CountDownLatch(1)

        // Container for collected audio data
        val audioContainer = ByteArrayContainer()

        // Error container
        val errorRef = AtomicReference<Throwable?>()

        // Response ID container for tracking
        val responseIdRef = AtomicReference<String?>()

        try {
            // Build QwenTtsRealtimeParam with connection settings
            val param = QwenTtsRealtimeParam.builder()
                .model(model)
                .apikey(apiKey)
                .build()

            // Create callback handler for WebSocket events
            val callback = object : QwenTtsRealtimeCallback() {
                override fun onOpen() {
                    log.debug("TTS WebSocket connection established")
                }

                override fun onEvent(message: JsonObject) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef)
                }

                override fun onClose(code: Int, reason: String?) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason)
                    synthesisLatch.countDown()
                }
            }

            val connectionFailureRef = AtomicReference<Throwable?>()
            val connectionFinishedLatch = CountDownLatch(1)
            val qwenTtsRealtime = createClient(
                param, callback, connectionFailureRef, connectionFinishedLatch
            )

            try {
                // Connect to server (blocking)
                connectWithTimeout(qwenTtsRealtime, connectionFailureRef, connectionFinishedLatch)

                // Configure session with TTS parameters
                val config = QwenTtsRealtimeConfig.builder()
                    .voice(voice)
                    .responseFormat(getAudioFormat())
                    .mode(mode) // "commit" mode
                    .languageType(languageType)
                    .speechRate(speechRate)
                    .volume(volume)
                    .build()

                // Update session with configuration
                qwenTtsRealtime.updateSession(config)

                log.info(
                    "[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                    voice, text.length
                )

                // Send text for synthesis using commit mode
                qwenTtsRealtime.appendText(text)
                qwenTtsRealtime.commit()

                log.info("[TTS] Text sent to TTS service, waiting for audio response...")

                // Wait for synthesis completion with timeout
                val completed = synthesisLatch.await(30, TimeUnit.SECONDS)

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds")
                    return ByteArray(0)
                }

                // Check if error occurred
                val error = errorRef.get()
                if (error != null) {
                    log.error("TTS synthesis failed", error)
                    return ByteArray(0)
                }

                // Return collected audio data
                val audioData = audioContainer.toByteArray()
                log.info(
                    "[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                    audioData.size, responseIdRef.get()
                )

                return audioData
            } finally {
                // Ensure connection is closed
                try {
                    qwenTtsRealtime.close()
                } catch (e: Exception) {
                    log.error("Error closing TTS connection", e)
                }
            }
        } catch (e: InterruptedException) {
            log.error("TTS synthesis interrupted", e)
            Thread.currentThread().interrupt()
            return ByteArray(0)
        } catch (e: Exception) {
            log.error("Failed to synthesize text", e)
            return ByteArray(0)
        }
    }

    /**
     * 创建 TTS 客户端（覆盖 onFailure 以捕获握手失败）
     */
    private fun createClient(
        param: QwenTtsRealtimeParam,
        callback: QwenTtsRealtimeCallback,
        connectionFailureRef: AtomicReference<Throwable?>,
        connectionFinishedLatch: CountDownLatch
    ): QwenTtsRealtime {
        return object : QwenTtsRealtime(param, callback) {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = if (response == null) {
                    "no HTTP response"
                } else {
                    "HTTP ${response.code} ${response.message}"
                }
                connectionFailureRef.compareAndSet(
                    null,
                    IllegalStateException("TTS WebSocket handshake failed: $status", t)
                )
                connectionFinishedLatch.countDown()
            }
        }
    }

    /**
     * 带超时的 WebSocket 建连（Java 25 虚拟线程改为守护平台线程，超时与中断语义不变）
     */
    private fun connectWithTimeout(
        qwenTtsRealtime: QwenTtsRealtime,
        connectionFailureRef: AtomicReference<Throwable?>,
        connectionFinishedLatch: CountDownLatch
    ) {
        val connectThread = Thread({
            try {
                qwenTtsRealtime.connect()
            } catch (throwable: Throwable) {
                connectionFailureRef.compareAndSet(null, throwable)
            } finally {
                connectionFinishedLatch.countDown()
            }
        }, "qwen-tts-connect")
        connectThread.isDaemon = true
        connectThread.start()

        try {
            val completed = connectionFinishedLatch.await(connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!completed) {
                throw TimeoutException(
                    "TTS WebSocket connection timed out after $connectTimeoutSeconds seconds"
                )
            }
        } finally {
            if (connectThread.isAlive) {
                connectThread.interrupt()
            }
        }

        val failure = connectionFailureRef.get()
        if (failure is Exception) {
            throw failure
        }
        if (failure != null) {
            throw IllegalStateException("TTS WebSocket connection failed", failure)
        }
    }

    /**
     * Get audio format for Qwen TTS Realtime.
     * Currently supports 24kHz PCM format.
     *
     * @return QwenTtsRealtimeAudioFormat enum value
     */
    private fun getAudioFormat(): QwenTtsRealtimeAudioFormat {
        // Qwen TTS Realtime uses 24kHz by default
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT
    }

    /**
     * Destroy the service and cleanup resources.
     *
     * This method is called automatically when the Spring container shuts down.
     * Currently, no persistent resources need cleanup as each synthesis creates
     * its own temporary connection.
     */
    @PreDestroy
    fun destroy() {
        log.info("QwenTtsService destroyed successfully")
    }

    /**
     * Handle server events from the DashScope TTS service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - response.audio.delta: Audio chunk received
     * - response.done: Response completed
     * - error: Error occurred
     *
     * @param message JSON event message from server
     * @param audioContainer Container for collecting audio chunks
     * @param synthesisLatch Latch to signal completion
     * @param errorRef Container for error tracking
     * @param responseIdRef Container for response ID tracking
     */
    private fun handleServerEvent(
        message: JsonObject,
        audioContainer: ByteArrayContainer,
        synthesisLatch: CountDownLatch,
        errorRef: AtomicReference<Throwable?>,
        responseIdRef: AtomicReference<String?>
    ) {
        try {
            val eventType = message.get("type").asString

            if (log.isTraceEnabled) {
                log.trace("Received TTS event: {}, full message: {}", eventType, message)
            } else {
                log.debug("Received TTS event: {}", eventType)
            }

            when (eventType) {
                "session.created" -> {
                    val sessionId = if (message.has("session") && message.get("session").isJsonObject) {
                        message.get("session").asJsonObject.get("id").asString
                    } else {
                        "unknown"
                    }
                    log.debug("TTS session created: {}", sessionId)
                }

                "session.updated" -> log.debug("TTS session configuration updated")

                "response.audio.delta" -> {
                    // Audio chunk received - delta is a base64 string directly
                    if (message.has("delta")) {
                        val audioBase64 = message.get("delta").asString
                        if (audioBase64.isNotEmpty()) {
                            val audioChunk = Base64.getDecoder().decode(audioBase64)
                            audioContainer.append(audioChunk)
                            log.trace("Received audio chunk - {} bytes", audioChunk.size)
                        }
                    }
                }

                "response.done" -> {
                    // Response completed - this is the final event in Qwen TTS API
                    val responseId = responseIdRef.get()
                    log.debug("TTS response completed - responseId: {}", responseId)
                    synthesisLatch.countDown()
                }

                "error" -> {
                    // Error event
                    if (message.has("error")) {
                        val errorElement = message.get("error")
                        var errorType = "unknown"
                        var errorCode = "unknown"
                        var errorMessage = "Unknown error"

                        if (errorElement.isJsonObject) {
                            val errorObj = errorElement.asJsonObject
                            errorType = if (errorObj.has("type")) errorObj.get("type").asString else "unknown"
                            errorCode = if (errorObj.has("code")) errorObj.get("code").asString else "unknown"
                            errorMessage = if (errorObj.has("message")) {
                                errorObj.get("message").asString
                            } else {
                                "Unknown error"
                            }
                        } else {
                            errorMessage = errorElement.toString()
                        }

                        val fullErrorMessage = String.format("TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage)
                        log.error("{}", fullErrorMessage)

                        errorRef.set(IllegalStateException(fullErrorMessage))
                        synthesisLatch.countDown()
                    }
                }

                else -> log.trace("Unhandled TTS event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Error processing TTS server event", e)
            errorRef.set(e)
            synthesisLatch.countDown()
        }
    }

    /**
     * Internal class for efficiently collecting audio chunks.
     * Uses ByteArrayOutputStream for amortized O(1) append performance
     * instead of O(n²) copying with manual array growth.
     */
    private class ByteArrayContainer {
        private val baos = ByteArrayOutputStream() // 音频字节缓冲

        /**
         * 追加音频分块
         */
        @Synchronized
        fun append(chunk: ByteArray) {
            baos.write(chunk, 0, chunk.size)
        }

        /**
         * 输出完整音频
         */
        @Synchronized
        fun toByteArray(): ByteArray {
            return baos.toByteArray()
        }
    }
}

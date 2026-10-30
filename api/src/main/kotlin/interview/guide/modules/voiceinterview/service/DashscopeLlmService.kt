package interview.guide.modules.voiceinterview.service

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.resume.repository.ResumeRepository
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DashScope LLM 对话服务
 * 负责语音面试的 LLM 调用、流式句子切分与回复长度优化
 */
@Service
class DashscopeLlmService(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val promptService: VoiceInterviewPromptService, // Prompt 构建服务
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val voiceInterviewProperties: VoiceInterviewProperties, // 语音面试配置
    private val promptSanitizer: PromptSanitizer // Prompt 注入净化工具
) {

    private val log = LoggerFactory.getLogger(DashscopeLlmService::class.java)

    companion object {
        private const val TERMINAL_PUNCTUATION = "。！？；!?;." // 句子终止标点
    }

    /**
     * 非流式对话
     *
     * @param userInput 用户输入文本 // 语音识别结果
     * @param session 当前会话 // 用于读取 Provider/技能/简历
     * @param conversationHistory 历史对话行 // 已压缩格式化
     * @return 优化后的面试官回复 // 面向语音播报的短文本
     */
    fun chat(
        userInput: String,
        session: VoiceInterviewSessionEntity,
        conversationHistory: List<String>?
    ): String {
        return try {
            val promptContext = buildPromptContext(userInput, session, conversationHistory)

            val provider = session.llmProvider
            log.info("[VoiceInterview] Session {} using LLM provider: {}", session.id, provider)

            val chatClient = llmProviderRegistry.getVoiceChatClient(provider)

            val content = chatClient.prompt()
                .system(promptContext.systemPrompt)
                .user(promptContext.userPrompt)
                .call()
                .content() ?: ""
            val optimized = optimizeForVoice(content)

            log.info(
                "LLM response generated for session {}: replyLength={}",
                session.id, optimized.length
            )

            optimized
        } catch (e: Exception) {
            log.error(
                "LLM chat error for session {}: {}",
                session.id, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            mapLlmErrorToUserMessage(e)
        }
    }

    /**
     * 流式对话（仅推送增量文本）
     *
     * @param userInput 用户输入文本
     * @param onToken 增量文本回调 // 收到增量后推送前端
     * @param session 当前会话
     * @param conversationHistory 历史对话行
     * @return 完整优化后的回复
     */
    fun chatStream(
        userInput: String,
        onToken: ((String) -> Unit)?,
        session: VoiceInterviewSessionEntity,
        conversationHistory: List<String>?
    ): String {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory)
    }

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     *
     * @param userInput 用户输入文本
     * @param onToken 实时文本回调 // 可为 null
     * @param onSentence 完整句子回调 // 可为 null
     * @param session 当前会话
     * @param conversationHistory 历史对话行
     * @return 优化后的完整回复
     */
    fun chatStreamSentences(
        userInput: String,
        onToken: ((String) -> Unit)?,
        onSentence: ((String) -> Unit)?,
        session: VoiceInterviewSessionEntity,
        conversationHistory: List<String>?
    ): String {
        return try {
            val promptContext = buildPromptContext(userInput, session, conversationHistory)
            val provider = session.llmProvider
            log.info(
                "[VoiceInterview] Session {} using LLM provider (sentence stream): {}",
                session.id, provider
            )

            val chatClient = llmProviderRegistry.getVoiceChatClient(provider)
            val raw = StringBuilder()
            val lastEmitNanos = AtomicLong(System.nanoTime())
            val lastEmitLength = AtomicInteger(0)
            val lastSentenceEnd = AtomicInteger(0)
            val emitIntervalMs = maxOf(80, voiceInterviewProperties.aiStreamPushIntervalMs)
            val minCharsDelta = maxOf(4, voiceInterviewProperties.aiStreamMinCharsDelta)

            chatClient.prompt()
                .system(promptContext.systemPrompt)
                .user(promptContext.userPrompt)
                .stream()
                .content()
                .doOnNext { token ->
                    if (token.isNullOrEmpty()) {
                        return@doOnNext
                    }
                    raw.append(token)

                    // 检测句子边界，回调 onSentence
                    if (onSentence != null && hasTerminalSince(token)) {
                        val normalized = normalizeRealtimeText(raw.toString())
                        val currentEnd = normalized.length
                        if (currentEnd > lastSentenceEnd.get()) {
                            val sentence = normalized.substring(lastSentenceEnd.get()).trim()
                            if (sentence.isNotEmpty()) {
                                onSentence.invoke(sentence)
                            }
                            lastSentenceEnd.set(currentEnd)
                        }
                    }

                    // 实时文本推送
                    if (onToken == null) {
                        return@doOnNext
                    }
                    val now = System.nanoTime()
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get())
                    val currentLength = raw.length
                    val shouldEmit =
                        elapsedMs >= emitIntervalMs && currentLength - lastEmitLength.get() >= minCharsDelta
                    if (!shouldEmit) {
                        return@doOnNext
                    }
                    val normalized = normalizeRealtimeText(raw.toString())
                    if (normalized.isBlank()) {
                        return@doOnNext
                    }
                    onToken.invoke(normalized)
                    lastEmitNanos.set(now)
                    lastEmitLength.set(normalized.length)
                }
                .blockLast()

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                val normalized = normalizeRealtimeText(raw.toString())
                if (normalized.length > lastSentenceEnd.get()) {
                    val remaining = normalized.substring(lastSentenceEnd.get()).trim()
                    if (remaining.isNotEmpty()) {
                        onSentence.invoke(remaining)
                    }
                }
            }

            val optimized = optimizeForVoice(raw.toString())
            if (onToken != null && optimized.isNotBlank()) {
                onToken.invoke(optimized)
            }

            log.info(
                "LLM sentence stream response for session {}: replyLength={}",
                session.id, optimized.length
            )
            optimized
        } catch (e: Exception) {
            log.error(
                "LLM sentence stream error for session {}: {}",
                session.id, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            mapLlmErrorToUserMessage(e)
        }
    }

    /**
     * 构建 system/user prompt 上下文
     */
    private fun buildPromptContext(
        userInput: String,
        session: VoiceInterviewSessionEntity,
        conversationHistory: List<String>?
    ): PromptContext {
        var resumeText: String? = null
        val resumeId = session.resumeId
        if (resumeId != null) {
            val resume = resumeRepository.findById(resumeId).orElse(null)
            if (resume != null) {
                resumeText = resume.resumeText
            }
        }

        val systemPrompt = promptService.generateSystemPromptWithContext(session.skillId, resumeText)

        val promptBuilder = StringBuilder()
        if (!conversationHistory.isNullOrEmpty()) {
            promptBuilder.append("【之前的对话】\n")
            for (message in conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n")
            }
            promptBuilder.append("\n【当前对话】\n")
        }
        promptBuilder.append("用户：").append(
            promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput))
        )
        return PromptContext(systemPrompt, promptBuilder.toString())
    }

    /**
     * 将 LLM 异常映射为用户可读提示
     */
    private fun mapLlmErrorToUserMessage(e: Exception): String {
        val errorMessage = e.message
        if (errorMessage != null) {
            if (errorMessage.contains("403") || errorMessage.contains("ACCESS_DENIED") ||
                errorMessage.contains("Authentication")
            ) {
                return "AI 服务认证失败，请检查 API Key 配置"
            } else if (errorMessage.contains("timeout") || errorMessage.contains("Timeout")) {
                return "AI 服务响应超时，请稍后重试"
            } else if (errorMessage.contains("429") || errorMessage.contains("rate limit") ||
                errorMessage.contains("quota")
            ) {
                return "AI 服务调用频率超限，请稍后重试"
            } else if (errorMessage.contains("connection") || errorMessage.contains("network")) {
                return "AI 服务网络连接失败，请检查网络"
            }
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试"
    }

    /**
     * 优化回复以适配语音播报：去除 Markdown、压缩空白、按字符上限截断到句子边界
     */
    private fun optimizeForVoice(content: String?): String {
        val normalized = normalizeRealtimeText(content)
        if (normalized.isBlank()) {
            return "请继续。"
        }

        val maxChars = maxOf(80, voiceInterviewProperties.aiQuestionMaxChars)
        if (normalized.length <= maxChars) {
            return normalized
        }

        val truncated = normalized.substring(0, maxChars)
        var lastTerminal = -1
        for (i in truncated.length - 1 downTo 0) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated[i]) >= 0) {
                lastTerminal = i
                break
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1)
        }

        return "$truncated…"
    }

    /**
     * 归一化实时文本：去掉 Markdown 标记、列表前缀，合并空白
     */
    private fun normalizeRealtimeText(content: String?): String {
        if (content == null || content.isBlank()) {
            return ""
        }
        return content
            .replace("**", "")
            .replace("```", "")
            .replace("`", "")
            .replace(Regex("(?m)^\\s*[-*+]\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 判断 token 内是否包含句子终止标点
     */
    private fun hasTerminalSince(token: String): Boolean {
        for (i in token.indices) {
            if (TERMINAL_PUNCTUATION.indexOf(token[i]) >= 0) {
                return true
            }
        }
        return false
    }

    /**
     * Prompt 上下文（system + user）
     */
    private data class PromptContext(
        val systemPrompt: String, // system prompt
        val userPrompt: String // user prompt
    )
}

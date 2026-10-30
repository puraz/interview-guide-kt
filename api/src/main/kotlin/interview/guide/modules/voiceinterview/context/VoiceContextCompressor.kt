package interview.guide.modules.voiceinterview.context

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 语音面试上下文压缩器。
 *
 * 将全量对话历史压缩为「最近窗口原文 + 早期轮次滚动摘要」，避免长会话下
 * 每轮把完整转录重发给 LLM 导致的 prompt token 无界增长与上下文溢出风险。
 *
 * 设计要点（详见 docs/语音面试上下文压缩_技术方案设计.md）：
 * - enabled=false：完全关闭（唯一关闭方式，仅用于本地排查），返回全部历史
 * - mode=NONE：不摘要、不裁窗口，但仍执行字符硬预算
 * - mode=WINDOW：仅保留最近 windowSize 轮原文 + 字符硬预算，不调用 LLM
 * - mode=SUMMARY：最近窗口原文 + 早期轮次增量摘要（按 summaryBatchSize 触发）+ 字符硬预算
 */
@Component
class VoiceContextCompressor(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val properties: VoiceInterviewProperties, // 语音面试配置
    resourceLoader: ResourceLoader // 摘要模板资源加载器
) {

    private val log = LoggerFactory.getLogger(VoiceContextCompressor::class.java)

    private val summaryPromptTemplate: PromptTemplate = loadTemplate(resourceLoader) // 摘要 Prompt 模板

    /**
     * 压缩对话历史。
     *
     * @param turns         全部对话轮次（不含 SUMMARY 行），按 sequenceNum 升序
     * @param cachedSummary 持久化已有的摘要（可能为 null）
     * @param coveredTurns  已被摘要覆盖的轮次数（用于增量合并，避免重复摘要）
     * @return 压缩结果：summary（可能为 null）、recent（保留的近期轮次）、coveredTurns、changed
     */
    fun compress(
        turns: List<VoiceInterviewMessageEntity>,
        cachedSummary: String?,
        coveredTurns: Int
    ): CompressedHistory {
        return compress(turns, cachedSummary, coveredTurns, null)
    }

    /**
     * 使用会话指定的 LLM 提供商压缩对话历史。
     */
    fun compress(
        turns: List<VoiceInterviewMessageEntity>,
        cachedSummary: String?,
        coveredTurns: Int,
        llmProvider: String?
    ): CompressedHistory {
        val cfg = properties.contextCompression
        // enabled=false 是唯一关闭方式：不压缩、不做字符硬预算，返回全量（仅用于本地排查）
        if (!cfg.enabled) {
            return CompressedHistory(null, turns, turns.size, false)
        }

        // NONE 模式不摘要、不裁窗口，但仍执行字符硬预算。
        if (cfg.mode == VoiceInterviewProperties.Mode.NONE) {
            val budgeted = applyCharBudget(null, turns)
            return CompressedHistory(null, budgeted, 0, false)
        }

        if (cfg.mode == VoiceInterviewProperties.Mode.WINDOW) {
            val start = maxOf(0, turns.size - cfg.windowSize)
            return CompressedHistory(null, applyCharBudget(null, turns.subList(start, turns.size)), 0, false)
        }

        // Loader 已排除摘要覆盖过的消息；局部列表在窗口内不代表没有旧摘要。
        if (turns.size <= cfg.windowSize) {
            val summary = truncateSummary(VoiceInterviewMessageEntity.trimToNull(cachedSummary))
            return CompressedHistory(summary, applyCharBudget(summary, turns), 0, false)
        }

        val total = turns.size
        val window = cfg.windowSize
        val earlyCount = total - window
        var summary = cachedSummary
        var changed = false
        var summaryFailed = false
        var effectiveCoveredTurns = if (VoiceInterviewMessageEntity.trimToNull(cachedSummary) == null) {
            0
        } else {
            minOf(maxOf(coveredTurns, 0), earlyCount)
        }

        if (cfg.mode == VoiceInterviewProperties.Mode.SUMMARY &&
            earlyCount > effectiveCoveredTurns &&
            earlyCount - effectiveCoveredTurns >= cfg.summaryBatchSize
        ) {
            // 仅对「尚未覆盖的早期轮次」做增量摘要合并，避免每轮都调用 LLM
            // earlyCount > coveredTurns 防御上游脏数据（如被损坏的 SUMMARY 行），避免 subList(from > to) 抛异常
            val earlyTurns = formatRecent(turns.subList(effectiveCoveredTurns, earlyCount))
            val newSummary = summarize(cachedSummary, earlyTurns, llmProvider)
            if (newSummary == null) {
                // 摘要生成失败：有旧摘要保留旧摘要，无旧摘要降级 WINDOW；不允许恢复为全量历史
                summaryFailed = true
            } else {
                // 正文相同也表示本批已处理；changed 同时表示覆盖边界需要持久化。
                summary = newSummary
                effectiveCoveredTurns = earlyCount
                changed = true
            }
        }

        val recentStart = if (cfg.mode == VoiceInterviewProperties.Mode.SUMMARY && !summaryFailed) {
            effectiveCoveredTurns
        } else {
            earlyCount
        }
        val budgetedSummary = truncateSummary(summary)
        val recent = applyCharBudget(budgetedSummary, turns.subList(recentStart, total))
        return CompressedHistory(budgetedSummary, recent, effectiveCoveredTurns, changed)
    }

    /**
     * 摘要超过 maxSummaryChars 时从尾部截断并附加标记。
     */
    private fun truncateSummary(summary: String?): String? {
        if (summary == null) {
            return null
        }
        val max = properties.contextCompression.maxSummaryChars
        if (max <= 0 || summary.length <= max) {
            return summary
        }
        val marker = "(摘要已截断)"
        val keep = maxOf(0, max - marker.length)
        return summary.substring(0, keep) + marker
    }

    /**
     * 字符硬预算：摘要字符数 + 近期历史格式化后字符数 <= maxHistoryChars。
     * 超出时从最早的近期消息开始删除，始终保留最近消息。
     */
    private fun applyCharBudget(
        summary: String?,
        recent: List<VoiceInterviewMessageEntity>
    ): List<VoiceInterviewMessageEntity> {
        val max = properties.contextCompression.maxHistoryChars
        if (max <= 0 || recent.isEmpty()) {
            return recent
        }
        var used = summary?.length ?: 0
        var start = recent.size
        while (start > 0) {
            val cost = formattedCharCost(recent[start - 1])
            if (used + cost > max) {
                break
            }
            used += cost
            start--
        }
        if (start == 0) {
            return recent
        }
        val summaryChars = summary?.length ?: 0
        if (start == recent.size) {
            // 连最近一条都放不下：保留最近消息的身份，按剩余预算截断其文本，硬上限不被绕过
            val truncated = truncateMessageTail(recent[recent.size - 1], max - summaryChars)
            log.info(
                "最近消息超出字符预算已截断: summaryChars={}, budget={}, inputTurns={}, outputTurns=1",
                summaryChars, max, recent.size
            )
            return listOf(truncated)
        }
        log.info(
            "上下文超出字符预算，丢弃最早近期消息: inputTurns={}, outputTurns={}, summaryChars={}, budget={}",
            recent.size, recent.size - start, summaryChars, max
        )
        return recent.subList(start, recent.size)
    }

    /**
     * 截断单条消息文本以适配剩余预算，保留尾部语义（候选人最后的表述更接近当前语境）。
     * 返回副本，不修改原实体。
     */
    private fun truncateMessageTail(
        msg: VoiceInterviewMessageEntity,
        remaining: Int
    ): VoiceInterviewMessageEntity {
        val aiText = VoiceInterviewMessageEntity.trimToNull(msg.aiGeneratedText)
        val userText = VoiceInterviewMessageEntity.trimToNull(msg.userRecognizedText)
        val aiLen = if (aiText != null) "面试官：".length + aiText.length else 0
        val userLen = if (userText != null) "候选人：".length + userText.length else 0
        val budget = maxOf(remaining, 0)

        // 优先保留候选人回答（含前缀），再给面试官问题分配剩余空间，两者都从尾部保留
        val userKeep = minOf(userLen, budget)
        val aiKeep = minOf(aiLen, budget - userKeep)

        val copy = VoiceInterviewMessageEntity().apply {
            sequenceNum = msg.sequenceNum
            messageType = msg.messageType
        }
        if (aiText != null) {
            val keepText = maxOf(aiKeep - "面试官：".length, 0)
            copy.aiGeneratedText = keepTail(aiText, keepText)
        }
        if (userText != null) {
            val keepText = maxOf(userKeep - "候选人：".length, 0)
            copy.userRecognizedText = keepTail(userText, keepText)
        }
        return copy
    }

    /**
     * 保留文本尾部
     */
    private fun keepTail(text: String, keepChars: Int): String {
        if (keepChars >= text.length) {
            return text
        }
        return if (keepChars <= 0) "" else text.substring(text.length - keepChars)
    }

    /**
     * 单条轮次格式化后的字符成本，与 formatRecent 的输出一致：
     * 每个非空文本都会以「面试官：/候选人：」前缀各占一行。
     */
    private fun formattedCharCost(msg: VoiceInterviewMessageEntity): Int {
        var cost = 0
        val aiText = VoiceInterviewMessageEntity.trimToNull(msg.aiGeneratedText)
        val userText = VoiceInterviewMessageEntity.trimToNull(msg.userRecognizedText)
        if (aiText != null) {
            cost += "面试官：".length + aiText.length
        }
        if (userText != null) {
            cost += "候选人：".length + userText.length
        }
        return cost
    }

    /**
     * 将实体轮次格式化为「面试官：/候选人：」文本行，与原 getHistory 的格式化逻辑保持一致。
     */
    fun formatRecent(turns: List<VoiceInterviewMessageEntity>): List<String> {
        val history = mutableListOf<String>()
        var pendingAiQuestion: String? = null
        for (msg in turns) {
            val aiText = VoiceInterviewMessageEntity.trimToNull(msg.aiGeneratedText)
            val userText = VoiceInterviewMessageEntity.trimToNull(msg.userRecognizedText)
            if (pendingAiQuestion != null) {
                history.add("面试官：$pendingAiQuestion")
                pendingAiQuestion = null
                if (userText != null) {
                    history.add("候选人：$userText")
                }
                if (aiText != null) {
                    pendingAiQuestion = aiText
                }
                continue
            }
            if (aiText != null && userText != null) {
                history.add("面试官：$aiText")
                history.add("候选人：$userText")
            } else if (aiText != null) {
                pendingAiQuestion = aiText
            } else if (userText != null) {
                history.add("候选人：$userText")
            }
        }
        if (pendingAiQuestion != null) {
            history.add("面试官：$pendingAiQuestion")
        }
        return history
    }

    /**
     * 先应用字符硬预算，再格式化最近窗口；用于加载失败时的有界降级路径。
     */
    fun formatRecentWithinBudget(turns: List<VoiceInterviewMessageEntity>): List<String> {
        return formatRecent(applyCharBudget(null, turns))
    }

    /**
     * 将早期轮次增量合并进已有摘要。生成失败返回 null，由调用方降级为最近窗口，不阻塞主链路。
     */
    private fun summarize(
        prevSummary: String?,
        earlyTurns: List<String>?,
        llmProvider: String?
    ): String? {
        if (earlyTurns.isNullOrEmpty()) {
            return prevSummary
        }
        return try {
            val prompt = summaryPromptTemplate.render(
                mapOf(
                    "previousSummary" to (prevSummary ?: "(空)"),
                    "newTurns" to earlyTurns.joinToString("\n")
                )
            )
            // 使用不带 SkillsTool / MemoryAdvisor 的 plain client：摘要是纯文本压缩，
            // 不应混入面试素材工具，也不应让 MemoryAdvisor 重新注入完整历史（否则抵消压缩收益）
            val client = if (llmProvider == null) {
                llmProviderRegistry.getPlainChatClient()
            } else {
                llmProviderRegistry.getPlainChatClient(llmProvider)
            }
            val result = client.prompt().user(prompt).call().content()
            if (result.isNullOrBlank()) null else result.trim()
        } catch (e: Exception) {
            log.warn(
                "上下文摘要生成失败: error={}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            null
        }
    }

    /**
     * 压缩结果。
     *
     * @param summary     早期轮次的滚动摘要（mode=SUMMARY 且已触发时非 null）
     * @param recent      保留的近期轮次实体（窗口内）
     * @param coveredTurns 已被摘要覆盖的轮次数
     * @param changed     摘要是否较缓存发生变化（需持久化）
     */
    data class CompressedHistory(
        val summary: String?, // 滚动摘要
        val recent: List<VoiceInterviewMessageEntity>, // 保留的近期轮次
        val coveredTurns: Int, // 已覆盖轮次数
        val changed: Boolean // 摘要是否变化
    )

    companion object {
        /**
         * 加载摘要 Prompt 模板
         */
        private fun loadTemplate(resourceLoader: ResourceLoader): PromptTemplate {
            return try {
                val template = resourceLoader
                    .getResource("classpath:prompts/voice-interview-context-summary.st")
                    .getContentAsString(StandardCharsets.UTF_8)
                PromptTemplate(template)
            } catch (e: IOException) {
                throw IllegalStateException("加载语音面试上下文摘要模板失败", e)
            }
        }
    }
}

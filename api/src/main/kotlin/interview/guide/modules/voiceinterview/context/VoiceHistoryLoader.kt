package interview.guide.modules.voiceinterview.context

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository
import interview.guide.modules.voiceinterview.service.VoiceInterviewService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * 语音面试上下文有界加载器（P1-06）。
 *
 * 把"数据库加载 + 压缩编排"从 WebSocket Handler 移出，并保证数据库侧读取量有界：
 * 单次最多读取「最近窗口 + 一个摘要批次」，不再全量读取长会话历史。
 *
 * 与 [VoiceContextCompressor] 的契约：传入的 turns 是「pending 段 + 最近窗口」，
 * coveredTurns 恒为 0（pending 已在覆盖边界之后）；压缩成功后的新覆盖边界
 * = turns.get(compressed.coveredTurns() - 1).sequenceNum，锁内校验不回退。
 *
 * 旧 SUMMARY 行（新边界字段为空）：只用 OFFSET/LIMIT 1 定位第 N 条原始消息迁移边界，
 * 不做全量读取与重新摘要。contextCompression.enabled=false 时显式保留全量分支（仅本地排查）。
 */
@Component
class VoiceHistoryLoader(
    private val messageRepository: VoiceInterviewMessageRepository, // 消息仓库
    private val interviewService: VoiceInterviewService, // 语音面试服务
    private val compressor: VoiceContextCompressor, // 上下文压缩器
    private val properties: VoiceInterviewProperties // 语音面试配置
) {

    private val log = LoggerFactory.getLogger(VoiceHistoryLoader::class.java)

    /**
     * 加载并压缩会话历史，返回格式化后的文本行（含可选的摘要行）。
     *
     * @param sessionId 会话ID // 字符串格式
     * @param llmProvider 会话指定的 LLM Provider // 可为 null
     * @return 格式化后的历史文本行 // 供 Prompt 拼接
     */
    fun loadHistory(sessionId: String, llmProvider: String?): List<String> {
        val cfg = properties.contextCompression
        if (!cfg.enabled) {
            // 唯一的全量分支：enabled=false 仅用于本地排查，生产默认不得走进
            log.warn("上下文压缩已关闭，语音历史走全量读取（仅限本地排查）: sessionId={}", sessionId)
            return loadUncompressed(sessionId)
        }
        return try {
            loadBounded(sessionId, llmProvider, cfg)
        } catch (e: Exception) {
            log.warn(
                "语音历史有界加载失败，降级为最近窗口: sessionId={}, error={}",
                sessionId, e.javaClass.simpleName, e
            )
            loadWindowOnly(sessionId)
        }
    }

    /**
     * 有界加载：摘要行 + 最近窗口 + 一个待摘要批次
     */
    private fun loadBounded(
        sessionId: String,
        llmProvider: String?,
        cfg: VoiceInterviewProperties.ContextCompressionConfig
    ): List<String> {
        val sessionIdLong = sessionId.toLong()
        val windowSize = cfg.windowSize
        val batchSize = cfg.summaryBatchSize

        // 1. 读取 SUMMARY 行（单行查询）
        val summaryRow = interviewService.loadSummaryRow(sessionId)
        val cachedSummary = summaryRow?.let {
            VoiceInterviewMessageEntity.trimToNull(it.aiGeneratedText)
        }
        var coveredSequenceNum = summaryRow?.summaryCoveredSequenceNum
        if (summaryRow != null && coveredSequenceNum == null) {
            // 2. 旧数据迁移：只用 OFFSET/LIMIT 1 定位第 N 条，不全量读取、不重新摘要
            coveredSequenceNum = migrateLegacyBoundary(sessionIdLong, summaryRow.sequenceNum)
            interviewService.saveSummaryRow(sessionId, cachedSummary, coveredSequenceNum)
        }

        // 3. 倒序取最近窗口，内存恢复升序
        val recentDesc = messageRepository
            .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
                PageRequest.of(0, windowSize)
            )
        val recent = ArrayList(recentDesc)
        recent.reverse()

        // 4. 边界之后、最近窗口之前的待摘要段（升序，最多一个批次）。
        //    无 SUMMARY 行时 covered 取 0；每轮只推进一个批次，大量历史积压不能靠一次加载补齐。
        val windowMin = recent.firstOrNull()?.sequenceNum
        var pending: List<VoiceInterviewMessageEntity> = emptyList()
        val after = coveredSequenceNum ?: 0
        // 窗口未填满时必然没有窗口外的未摘要消息，直接跳过 pending 查询
        if (windowMin != null && recent.size >= windowSize && windowMin > after) {
            pending = messageRepository
                .findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
                    sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
                    after, windowMin, PageRequest.of(0, batchSize)
                )
        }

        // 5. 压缩：turns = pending + 窗口，coveredTurns 恒为 0（换算在 Loader 完成）
        val turns = ArrayList<VoiceInterviewMessageEntity>(pending)
        turns.addAll(recent)
        val compressed = compressor.compress(turns, cachedSummary, 0, llmProvider)

        // 6. 持久化推进后的边界（LLM 已在 compress 内完成，事务在 saveSummaryRow 内）
        val compressedSummary = compressed.summary
        if (compressed.changed && compressedSummary != null && compressedSummary.isNotBlank() &&
            compressed.coveredTurns > 0
        ) {
            val newBoundary = turns[compressed.coveredTurns - 1].sequenceNum ?: 0
            try {
                interviewService.saveSummaryRow(sessionId, compressedSummary, newBoundary)
            } catch (e: Exception) {
                log.warn("持久化上下文摘要失败（不影响本次应答），session {}", sessionId, e)
            }
        }

        val history = mutableListOf<String>()
        if (!compressedSummary.isNullOrBlank()) {
            history.add("【对话摘要】$compressedSummary")
        }
        history.addAll(compressor.formatRecent(compressed.recent))
        return history
    }

    /**
     * 旧 SUMMARY 行迁移：coveredTurns = -oldSequenceNum - 1，
     * 用 OFFSET/LIMIT 1 定位第 N 条原始消息的 sequenceNum 作为边界并写回。
     */
    private fun migrateLegacyBoundary(sessionIdLong: Long, legacySequenceNum: Int?): Int {
        val coveredTurns = if (legacySequenceNum != null) maxOf(0, -legacySequenceNum - 1) else 0
        if (coveredTurns <= 0) {
            return 0
        }
        val nth = messageRepository
            .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
                PageRequest.of(coveredTurns - 1, 1)
            )
            .firstOrNull()
        if (nth?.sequenceNum == null) {
            log.warn("旧摘要边界迁移定位失败，回退边界 0: sessionId={}", sessionIdLong)
            return 0
        }
        return nth.sequenceNum ?: 0
    }

    /**
     * 摘要失败/加载异常时的降级：只取最近窗口（查询量仍有界），不恢复全量。
     */
    private fun loadWindowOnly(sessionId: String): List<String> {
        val sessionIdLong = sessionId.toLong()
        val windowSize = properties.contextCompression.windowSize
        val recentDesc = messageRepository
            .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
                PageRequest.of(0, windowSize)
            )
        val recent = ArrayList(recentDesc)
        recent.reverse()
        return ArrayList(compressor.formatRecentWithinBudget(recent))
    }

    /**
     * enabled=false 的全量分支（仅本地排查）：行为与旧 getHistory 全量路径一致。
     */
    private fun loadUncompressed(sessionId: String): List<String> {
        val turns = interviewService.getConversationHistory(sessionId)
        return ArrayList(compressor.formatRecent(turns))
    }
}

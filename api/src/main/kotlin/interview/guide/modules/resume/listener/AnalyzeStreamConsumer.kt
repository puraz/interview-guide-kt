package interview.guide.modules.resume.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.resume.repository.ResumeRepository
import interview.guide.modules.resume.service.ResumeGradingService
import interview.guide.modules.resume.service.ResumeParseService
import interview.guide.modules.resume.service.ResumePersistenceService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * 简历分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 分析
 */
@Component
class AnalyzeStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val gradingService: ResumeGradingService, // 简历评分服务
    private val persistenceService: ResumePersistenceService, // 简历持久化服务
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val parseService: ResumeParseService // 简历解析服务
) : AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload>(redisService) {

    companion object {
        private val log = LoggerFactory.getLogger(AnalyzeStreamConsumer::class.java)
    }

    /**
     * 分析任务载荷
     *
     * @param resumeId  简历ID // 任务目标
     * @param attemptId 执行代次 // 条件领取后写入，用于终态写入校验
     */
    class AnalyzePayload(val resumeId: Long, var attemptId: String? = null)

    override fun taskDisplayName(): String = "简历分析" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME // 消费者组名

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "analyze-consumer" // 消费线程名

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): AnalyzePayload? {
        val resumeIdStr = data[AsyncTaskStreamConstants.FIELD_RESUME_ID]
        if (resumeIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        // 兼容旧格式消息：其中的 content 字段直接忽略，正文以数据库实体为事实来源
        return AnalyzePayload(resumeIdStr.toLong())
    }

    override fun payloadIdentifier(payload: AnalyzePayload): String {
        return "resumeId=" + payload.resumeId
    }

    override fun shouldSkip(payload: AnalyzePayload): Boolean {
        return resumeRepository.findById(payload.resumeId)
            .map { resume -> resume.analyzeStatus == AsyncTaskStatus.COMPLETED }
            .orElse(true)
    }

    /**
     * 条件领取模式下 markProcessing 不再使用。
     */
    override fun markProcessing(payload: AnalyzePayload) {
        // tryMarkProcessing 原子领取
    }

    /**
     * 条件领取：只有 PENDING → PROCESSING 成功的消费者才执行任务（多实例防重复）。
     */
    override fun tryMarkProcessing(payload: AnalyzePayload): Boolean {
        val attemptId = UUID.randomUUID().toString()
        val claimed = resumeRepository.tryMarkAnalyzeProcessing(
            payload.resumeId, attemptId, LocalDateTime.now()
        ) == 1
        if (claimed) {
            payload.attemptId = attemptId
        }
        return claimed
    }

    override fun processBusiness(payload: AnalyzePayload) {
        val resumeId = payload.resumeId
        val resume = resumeRepository.findById(resumeId).orElse(null)
        if (resume == null) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId)
            return
        }

        val storedText = resume.resumeText
        // 历史数据正文为空时，从 RustFS 恢复文本并以独立短事务回填后再分析
        val resumeText: String = if (storedText.isNullOrBlank()) {
            val downloadedText = parseService.downloadAndParseContent(
                resume.storageKey, resume.originalFilename
            )
            // 下载 + 解析完成心跳
            heartbeat(payload)
            if (downloadedText.isNullOrBlank()) {
                throw BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容")
            }
            persistenceService.updateResumeText(resumeId, downloadedText)
            downloadedText
        } else {
            storedText
        }

        val analysis = gradingService.analyzeResume(resumeText)
        // LLM 分析完成心跳（单次外部调用，无循环；分析超时必须小于 PROCESSING 阈值）
        heartbeat(payload)
        val latestResume = resumeRepository.findById(resumeId).orElse(null)
        if (latestResume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId)
            return
        }
        persistenceService.saveAnalysisIfOwned(resumeId, payload.attemptId, analysis)
    }

    /**
     * 发送业务心跳，确认当前执行代次仍持有执行权
     *
     * @param payload 分析载荷 // 含 resumeId 与 attemptId
     */
    private fun heartbeat(payload: AnalyzePayload) {
        val updated = resumeRepository.heartbeatAnalyzeProcessing(
            payload.resumeId, payload.attemptId, LocalDateTime.now()
        )
        if (updated != 1) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "简历分析任务执行权已失效")
        }
    }

    override fun markCompleted(payload: AnalyzePayload) {
        // 条件写入：状态已不是 PROCESSING 时 no-op，防止覆盖他人结果
        resumeRepository.completeAnalyzeIfProcessing(
            payload.resumeId, payload.attemptId, LocalDateTime.now()
        )
    }

    override fun markFailed(payload: AnalyzePayload, error: String?) {
        resumeRepository.failAnalyzeIfProcessing(
            payload.resumeId, payload.attemptId, truncateError(error),
            LocalDateTime.now()
        )
    }

    override fun retryMessage(payload: AnalyzePayload, retryCount: Int) {
        val resumeId = payload.resumeId
        // 条件领取的配套重置：PROCESSING → PENDING 成功才重新投递，否则只记录并 ACK 丢弃
        val reset = resumeRepository.resetAnalyzeToPending(
            resumeId, payload.attemptId, LocalDateTime.now()
        )
        if (reset != 1) {
            log.warn("重试重置失败（任务已完成或状态不再匹配），ACK 丢弃: resumeId={}, retryCount={}", resumeId, retryCount)
            return
        }
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_RESUME_ID to resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount)
        } catch (e: Exception) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e))
            // 重投失败标 FAILED，避免停在 PENDING 被恢复调度器反复补投
            resumeRepository.failAnalyzeIfPending(
                resumeId, "重试入队失败", LocalDateTime.now()
            )
        }
    }
}

package interview.guide.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.ResumeRepository
import interview.guide.service.ResumeGradingService
import interview.guide.service.ResumePersistenceService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 简历分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 分析
 */
@Component
class AnalyzeStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val gradingService: ResumeGradingService, // 简历评分服务
    private val persistenceService: ResumePersistenceService, // 简历持久化服务
    private val resumeRepository: ResumeRepository // 简历仓库
) : AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload>(redisService) {

    private val log = LoggerFactory.getLogger(AnalyzeStreamConsumer::class.java)

    data class AnalyzePayload(
        val resumeId: Long, // 简历ID
        val content: String // 简历内容
    )

    override fun taskDisplayName(): String = "简历分析" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME // 消费者组名

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "analyze-consumer" // 消费线程名

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): AnalyzePayload? {
        val resumeIdStr = data[AsyncTaskStreamConstants.FIELD_RESUME_ID]
        val content = data[AsyncTaskStreamConstants.FIELD_CONTENT]
        if (resumeIdStr.isNullOrBlank() || content.isNullOrBlank()) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        return AnalyzePayload(resumeIdStr.toLong(), content)
    }

    override fun payloadIdentifier(payload: AnalyzePayload): String {
        return "resumeId=${payload.resumeId}"
    }

    override fun markProcessing(payload: AnalyzePayload) {
        updateAnalyzeStatus(payload.resumeId, AsyncTaskStatus.PROCESSING, null)
    }

    override fun processBusiness(payload: AnalyzePayload) {
        val resumeId = payload.resumeId
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId)
            return
        }

        val analysis = gradingService.analyzeResume(payload.content)
        val resume = resumeRepository.findById(resumeId).orElse(null)
        if (resume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId)
            return
        }
        persistenceService.saveAnalysis(resume, analysis)
    }

    override fun markCompleted(payload: AnalyzePayload) {
        updateAnalyzeStatus(payload.resumeId, AsyncTaskStatus.COMPLETED, null)
    }

    override fun markFailed(payload: AnalyzePayload, error: String?) {
        updateAnalyzeStatus(payload.resumeId, AsyncTaskStatus.FAILED, error)
    }

    override fun retryMessage(payload: AnalyzePayload, retryCount: Int) {
        val resumeId = payload.resumeId
        val content = payload.content
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_RESUME_ID to resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT to content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount)
        } catch (e: Exception) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.message, e)
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: ${e.message}"))
        }
    }

    /**
     * 更新分析状态
     */
    private fun updateAnalyzeStatus(resumeId: Long, status: AsyncTaskStatus, error: String?) {
        try {
            resumeRepository.findById(resumeId).ifPresent { resume ->
                resume.analyzeStatus = status
                resume.analyzeError = error
                resumeRepository.save(resume)
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status)
            }
        } catch (e: Exception) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.message, e)
        }
    }
}

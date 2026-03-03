package interview.guide.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Component
class AnalyzeStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val resumeRepository: ResumeRepository // 简历仓库
) : AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload>(redisService) {

    private val log = LoggerFactory.getLogger(AnalyzeStreamProducer::class.java)

    data class AnalyzeTaskPayload(
        val resumeId: Long, // 简历ID
        val content: String // 简历内容
    )

    /**
     * 发送分析任务到 Redis Stream
     */
    fun sendAnalyzeTask(resumeId: Long, content: String) {
        sendTask(AnalyzeTaskPayload(resumeId, content))
    }

    override fun taskDisplayName(): String = "分析" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY // Stream Key

    override fun buildMessage(payload: AnalyzeTaskPayload): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_RESUME_ID to payload.resumeId.toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT to payload.content,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(payload: AnalyzeTaskPayload): String {
        return "resumeId=${payload.resumeId}"
    }

    override fun onSendFailed(payload: AnalyzeTaskPayload, error: String) {
        updateAnalyzeStatus(payload.resumeId, AsyncTaskStatus.FAILED, truncateError(error))
    }

    /**
     * 更新分析状态
     */
    private fun updateAnalyzeStatus(resumeId: Long, status: AsyncTaskStatus, error: String?) {
        resumeRepository.findById(resumeId).ifPresent { resume ->
            resume.analyzeStatus = status
            if (error != null) {
                resume.analyzeError = if (error.length > 500) error.substring(0, 500) else error
            }
            resumeRepository.save(resume)
            log.debug("更新分析状态: resumeId={}, status={}", resumeId, status)
        }
    }
}

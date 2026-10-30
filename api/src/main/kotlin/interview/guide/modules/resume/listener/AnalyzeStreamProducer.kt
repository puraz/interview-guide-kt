package interview.guide.modules.resume.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.transaction.TransactionalExecutor
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.resume.repository.ResumeRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Component
class AnalyzeStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val transactionalExecutor: TransactionalExecutor // 小范围事务执行器（保留原有构造依赖，与 HEAD 一致）
) : AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload>(redisService) {

    /**
     * 分析任务载荷
     *
     * @param resumeId 简历ID // 任务目标
     */
    data class AnalyzeTaskPayload(val resumeId: Long)

    /**
     * 发送分析任务到 Redis Stream
     *
     * @param resumeId 简历ID
     * @return 是否投递成功 // false 表示已按条件更新为 FAILED
     */
    fun sendAnalyzeTask(resumeId: Long): Boolean {
        return sendTask(AnalyzeTaskPayload(resumeId))
    }

    override fun taskDisplayName(): String = "分析" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY // Stream Key

    override fun buildMessage(payload: AnalyzeTaskPayload): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_RESUME_ID to payload.resumeId.toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(payload: AnalyzeTaskPayload): String {
        return "resumeId=" + payload.resumeId
    }

    override fun onSendFailed(payload: AnalyzeTaskPayload, error: String) {
        // 条件更新：不覆盖已被其他路径改写的终态（如 COMPLETED）
        resumeRepository.failAnalyzeIfPending(
            payload.resumeId, truncateError(error), LocalDateTime.now()
        )
    }
}

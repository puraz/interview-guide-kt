package interview.guide.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.InterviewSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream
 */
@Component
class EvaluateStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val sessionRepository: InterviewSessionRepository // 会话仓库
) : AbstractStreamProducer<String>(redisService) {

    private val log = LoggerFactory.getLogger(EvaluateStreamProducer::class.java)

    /**
     * 发送评估任务到 Redis Stream
     */
    fun sendEvaluateTask(sessionId: String) {
        sendTask(sessionId)
    }

    override fun taskDisplayName(): String = "评估" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY // Stream Key

    override fun buildMessage(payload: String): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_SESSION_ID to payload,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(payload: String): String {
        return "sessionId=$payload"
    }

    override fun onSendFailed(payload: String, error: String) {
        updateEvaluateStatus(payload, AsyncTaskStatus.FAILED, truncateError(error))
    }

    /**
     * 更新评估状态
     */
    private fun updateEvaluateStatus(sessionId: String, status: AsyncTaskStatus, error: String?) {
        sessionRepository.findBySessionId(sessionId)?.let { session ->
            session.evaluateStatus = status
            if (error != null) {
                session.evaluateError = if (error.length > 500) error.substring(0, 500) else error
            }
            sessionRepository.save(session)
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status)
        }
    }
}

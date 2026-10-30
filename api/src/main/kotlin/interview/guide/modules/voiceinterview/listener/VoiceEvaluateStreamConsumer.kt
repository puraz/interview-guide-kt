package interview.guide.modules.voiceinterview.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService
import interview.guide.modules.voiceinterview.service.VoiceInterviewService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 语音面试评估 Stream 消费者
 */
@Component
class VoiceEvaluateStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val voiceInterviewService: VoiceInterviewService, // 语音面试服务
    private val evaluationService: VoiceInterviewEvaluationService, // 评估服务
    private val sessionRepository: VoiceInterviewSessionRepository // 会话仓库
) : AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload>(redisService) {

    private val log = LoggerFactory.getLogger(VoiceEvaluateStreamConsumer::class.java)

    /**
     * 评估任务载荷
     */
    data class VoiceEvaluatePayload(
        val sessionId: Long // 会话ID
    )

    override fun taskDisplayName(): String = "语音面试评估" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME // 消费者组

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "voice-evaluate-consumer" // 消费线程名称

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): VoiceEvaluatePayload? {
        val sessionId = data[AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID]
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        return VoiceEvaluatePayload(sessionId.toLong())
    }

    override fun payloadIdentifier(payload: VoiceEvaluatePayload): String {
        return "voiceSessionId=${payload.sessionId}"
    }

    override fun shouldSkip(payload: VoiceEvaluatePayload): Boolean {
        return sessionRepository.findById(payload.sessionId)
            .map { session -> session.evaluateStatus == AsyncTaskStatus.COMPLETED }
            .orElse(true)
    }

    override fun markProcessing(payload: VoiceEvaluatePayload) {
        voiceInterviewService.updateEvaluateStatus(
            payload.sessionId, AsyncTaskStatus.PROCESSING, null
        )
    }

    override fun processBusiness(payload: VoiceEvaluatePayload) {
        val sessionId = payload.sessionId
        if (!sessionRepository.existsById(sessionId)) {
            log.warn("语音面试会话已被删除，跳过评估任务: sessionId={}", sessionId)
            return
        }
        evaluationService.generateEvaluation(sessionId)
        log.info("语音面试评估完成: sessionId={}", sessionId)
    }

    override fun markCompleted(payload: VoiceEvaluatePayload) {
        voiceInterviewService.updateEvaluateStatus(
            payload.sessionId, AsyncTaskStatus.COMPLETED, null
        )
    }

    override fun markFailed(payload: VoiceEvaluatePayload, error: String?) {
        voiceInterviewService.updateEvaluateStatus(
            payload.sessionId, AsyncTaskStatus.FAILED, error
        )
    }

    override fun retryMessage(payload: VoiceEvaluatePayload, retryCount: Int) {
        val sessionId = payload.sessionId
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID to sessionId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("语音面试评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount)
        } catch (e: Exception) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.message, e)
            voiceInterviewService.updateEvaluateStatus(
                sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: ${e.message}")
            )
        }
    }
}

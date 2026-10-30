package interview.guide.modules.voiceinterview.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.common.transaction.TransactionalExecutor
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.voiceinterview.service.VoiceInterviewService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * 语音面试评估任务生产者
 */
@Component
class VoiceEvaluateStreamProducer(
    redisService: RedisService, // Redis 操作封装
    @Lazy private val voiceInterviewService: VoiceInterviewService, // 语音面试服务（延迟注入打破循环依赖）
    private val transactionalExecutor: TransactionalExecutor // 独立事务执行器
) : AbstractStreamProducer<String>(redisService) {

    private val log = LoggerFactory.getLogger(VoiceEvaluateStreamProducer::class.java)

    /**
     * 投递语音面试评估任务
     *
     * @param sessionId 会话ID // 目标任务
     */
    fun sendEvaluateTask(sessionId: String) {
        sendTask(sessionId)
    }

    override fun taskDisplayName(): String = "语音面试评估" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY // Stream Key

    override fun buildMessage(sessionId: String): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID to sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(sessionId: String): String = "voiceSessionId=$sessionId" // 载荷标识

    override fun onSendFailed(sessionId: String, error: String) {
        transactionalExecutor.runRequiresNew {
            voiceInterviewService.updateEvaluateStatus(
                sessionId.toLong(), AsyncTaskStatus.FAILED, truncateError(error)
            )
        }
    }
}

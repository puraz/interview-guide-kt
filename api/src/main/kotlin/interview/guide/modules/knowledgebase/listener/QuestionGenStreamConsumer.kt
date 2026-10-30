package interview.guide.modules.knowledgebase.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionGenerationService
import interview.guide.modules.knowledgebase.service.QuestionGenerationStateService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 知识库题目生成 Stream 消费者。
 */
@Component
class QuestionGenStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val generationService: KnowledgeBaseQuestionGenerationService, // 题目生成服务
    private val stateService: QuestionGenerationStateService, // 生成状态事务服务
    private val producer: QuestionGenStreamProducer // 重试入队生产者
) : AbstractStreamConsumer<QuestionGenStreamConsumer.QuestionGenPayload>(redisService) {

    private val log = LoggerFactory.getLogger(QuestionGenStreamConsumer::class.java)

    /**
     * 题目生成消息载荷
     */
    data class QuestionGenPayload(
        val kbId: Long, // 知识库ID
        val taskId: String // 任务ID（幂等）
    )

    override fun taskDisplayName(): String = "题目生成" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_QUESTION_GEN_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.KB_QUESTION_GEN_GROUP_NAME // 消费者组名

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.KB_QUESTION_GEN_CONSUMER_PREFIX // 消费者名前缀

    override fun threadName(): String = "question-gen-consumer" // 消费线程名

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): QuestionGenPayload? {
        val kbId = data[AsyncTaskStreamConstants.FIELD_KB_ID]
        val taskId = data[AsyncTaskStreamConstants.FIELD_TASK_ID]
        if (kbId == null || taskId == null) {
            log.warn("题目生成消息格式错误，丢弃: messageId={}", messageId)
            return null
        }
        return QuestionGenPayload(kbId.toLong(), taskId)
    }

    override fun payloadIdentifier(payload: QuestionGenPayload): String {
        return "kbId=" + payload.kbId + ", taskId=" + payload.taskId
    }

    override fun markProcessing(payload: QuestionGenPayload) {
        // 题目生成使用 tryMarkProcessing 原子领取。
    }

    override fun tryMarkProcessing(payload: QuestionGenPayload): Boolean {
        return stateService.tryMarkProcessing(payload.kbId, payload.taskId)
    }

    override fun processBusiness(payload: QuestionGenPayload) {
        val config: QuestionGenerationConfig = stateService.getConfig(payload.kbId, payload.taskId)
        generationService.executeGeneration(payload.kbId, payload.taskId, config)
    }

    override fun markCompleted(payload: QuestionGenPayload) {
        // 题目替换和 COMPLETED 状态已在同一事务中提交。
    }

    override fun markFailed(payload: QuestionGenPayload, error: String?) {
        stateService.markFailed(payload.kbId, payload.taskId)
    }

    override fun retryMessage(payload: QuestionGenPayload, retryCount: Int) {
        if (!stateService.resetForRetry(payload.kbId, payload.taskId)) {
            log.info(
                "题目生成任务已失效，不再重试: kbId={}, taskId={}",
                payload.kbId, payload.taskId
            )
            return
        }
        producer.sendGenerateTask(payload.kbId, payload.taskId, retryCount)
    }
}

package interview.guide.modules.knowledgebase.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.knowledgebase.service.QuestionGenerationStateService
import org.springframework.stereotype.Component

/**
 * 知识库题目生成任务生产者。
 */
@Component
class QuestionGenStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val stateService: QuestionGenerationStateService // 生成状态事务服务
) : AbstractStreamProducer<QuestionGenStreamProducer.QuestionGenTaskPayload>(redisService) {

    /**
     * 题目生成任务载荷
     */
    data class QuestionGenTaskPayload(
        val kbId: Long, // 知识库ID
        val taskId: String, // 任务ID（幂等）
        val retryCount: Int // 重试次数
    )

    /**
     * 发送题目生成任务（首次投递）
     */
    fun sendGenerateTask(kbId: Long, taskId: String): Boolean {
        return sendGenerateTask(kbId, taskId, 0)
    }

    /**
     * 发送题目生成任务
     *
     * @param kbId 知识库ID
     * @param taskId 任务ID
     * @param retryCount 重试次数
     * @return 是否投递成功
     */
    fun sendGenerateTask(kbId: Long, taskId: String, retryCount: Int): Boolean {
        return sendTask(QuestionGenTaskPayload(kbId, taskId, retryCount))
    }

    override fun taskDisplayName(): String = "题目生成" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_QUESTION_GEN_STREAM_KEY // Stream Key

    override fun buildMessage(payload: QuestionGenTaskPayload): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_KB_ID to payload.kbId.toString(),
            AsyncTaskStreamConstants.FIELD_TASK_ID to payload.taskId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to payload.retryCount.toString()
        )
    }

    override fun payloadIdentifier(payload: QuestionGenTaskPayload): String {
        return "kbId=" + payload.kbId + ", taskId=" + payload.taskId
    }

    override fun onSendFailed(payload: QuestionGenTaskPayload, error: String) {
        stateService.markFailed(payload.kbId, payload.taskId)
    }
}

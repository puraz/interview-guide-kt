package interview.guide.modules.knowledgebase.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Component
class VectorizeStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val knowledgeBaseRepository: KnowledgeBaseRepository // 知识库仓库
) : AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload>(redisService) {

    /**
     * 向量化任务载荷
     */
    data class VectorizeTaskPayload(
        val kbId: Long // 知识库ID
    )

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId 知识库ID
     * @return 是否投递成功
     */
    fun sendVectorizeTask(kbId: Long): Boolean {
        return sendTask(VectorizeTaskPayload(kbId))
    }

    override fun taskDisplayName(): String = "向量化" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY // Stream Key

    override fun buildMessage(payload: VectorizeTaskPayload): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_KB_ID to payload.kbId.toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(payload: VectorizeTaskPayload): String {
        return "kbId=" + payload.kbId
    }

    override fun onSendFailed(payload: VectorizeTaskPayload, error: String) {
        // 条件更新：不覆盖已被其他路径改写的终态（如 COMPLETED）
        knowledgeBaseRepository.failVectorIfPending(
            payload.kbId, truncateError(error), LocalDateTime.now()
        )
    }
}

package interview.guide.listener

import interview.guide.common.async.AbstractStreamProducer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.entity.VectorStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Component
class VectorizeStreamProducer(
    redisService: RedisService, // Redis 操作封装
    private val knowledgeBaseRepository: KnowledgeBaseRepository // 知识库仓库
) : AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload>(redisService) {

    private val log = LoggerFactory.getLogger(VectorizeStreamProducer::class.java)

    data class VectorizeTaskPayload(
        val kbId: Long, // 知识库ID
        val content: String // 文档内容
    )

    /**
     * 发送向量化任务到 Redis Stream
     */
    fun sendVectorizeTask(kbId: Long, content: String) {
        sendTask(VectorizeTaskPayload(kbId, content))
    }

    override fun taskDisplayName(): String = "向量化" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY // Stream Key

    override fun buildMessage(payload: VectorizeTaskPayload): Map<String, String> {
        return mapOf(
            AsyncTaskStreamConstants.FIELD_KB_ID to payload.kbId.toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT to payload.content,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT to "0"
        )
    }

    override fun payloadIdentifier(payload: VectorizeTaskPayload): String {
        return "kbId=${payload.kbId}"
    }

    override fun onSendFailed(payload: VectorizeTaskPayload, error: String) {
        updateVectorStatus(payload.kbId, VectorStatus.FAILED, truncateError(error))
    }

    /**
     * 更新向量化状态
     */
    private fun updateVectorStatus(kbId: Long, status: VectorStatus, error: String?) {
        knowledgeBaseRepository.findById(kbId).ifPresent { kb ->
            kb.vectorStatus = status
            if (error != null) {
                kb.vectorError = if (error.length > 500) error.substring(0, 500) else error
            }
            knowledgeBaseRepository.save(kb)
            log.debug("向量化状态已更新: kbId={}, status={}", kbId, status)
        }
    }
}

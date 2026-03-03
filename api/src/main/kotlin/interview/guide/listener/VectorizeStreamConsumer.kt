package interview.guide.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.entity.VectorStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.KnowledgeBaseRepository
import interview.guide.service.KnowledgeBaseVectorService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Component
class VectorizeStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val vectorService: KnowledgeBaseVectorService, // 向量化服务
    private val knowledgeBaseRepository: KnowledgeBaseRepository // 知识库仓库
) : AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload>(redisService) {

    private val log = LoggerFactory.getLogger(VectorizeStreamConsumer::class.java)

    data class VectorizePayload(
        val kbId: Long, // 知识库ID
        val content: String // 文档内容
    )

    override fun taskDisplayName(): String = "向量化" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME // 消费者组名

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "vectorize-consumer" // 消费线程名

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): VectorizePayload? {
        val kbIdStr = data[AsyncTaskStreamConstants.FIELD_KB_ID]
        val content = data[AsyncTaskStreamConstants.FIELD_CONTENT]
        if (kbIdStr.isNullOrBlank() || content.isNullOrBlank()) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        return VectorizePayload(kbIdStr.toLong(), content)
    }

    override fun payloadIdentifier(payload: VectorizePayload): String {
        return "kbId=${payload.kbId}"
    }

    override fun markProcessing(payload: VectorizePayload) {
        updateVectorStatus(payload.kbId, VectorStatus.PROCESSING, null)
    }

    override fun processBusiness(payload: VectorizePayload) {
        vectorService.vectorizeAndStore(payload.kbId, payload.content)
    }

    override fun markCompleted(payload: VectorizePayload) {
        updateVectorStatus(payload.kbId, VectorStatus.COMPLETED, null)
    }

    override fun markFailed(payload: VectorizePayload, error: String?) {
        updateVectorStatus(payload.kbId, VectorStatus.FAILED, error)
    }

    override fun retryMessage(payload: VectorizePayload, retryCount: Int) {
        val kbId = payload.kbId
        val content = payload.content
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_KB_ID to kbId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT to content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount)
        } catch (e: Exception) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.message, e)
            updateVectorStatus(kbId, VectorStatus.FAILED, truncateError("重试入队失败: ${e.message}"))
        }
    }

    /**
     * 更新向量化状态
     */
    private fun updateVectorStatus(kbId: Long, status: VectorStatus, error: String?) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent { kb ->
                kb.vectorStatus = status
                kb.vectorError = error
                knowledgeBaseRepository.save(kb)
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status)
            }
        } catch (e: Exception) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.message, e)
        }
    }
}

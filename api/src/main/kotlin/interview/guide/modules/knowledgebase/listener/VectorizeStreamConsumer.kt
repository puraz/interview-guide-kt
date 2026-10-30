package interview.guide.modules.knowledgebase.listener

import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.async.recovery.VectorizeRecoveryProperties
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Component
class VectorizeStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val vectorService: KnowledgeBaseVectorService, // 向量化服务
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val parseService: KnowledgeBaseParseService, // 文档解析服务
    private val recoveryProperties: VectorizeRecoveryProperties // 恢复配置（心跳节流）
) : AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload>(redisService) {

    private val log = LoggerFactory.getLogger(VectorizeStreamConsumer::class.java)

    /** 当前任务的 Embedding 心跳上次写库时间（领取时清零），仅内存态 */
    @Volatile
    private var lastEmbeddingHeartbeatNanos: Long = 0

    /**
     * 向量化消息载荷（attemptId 在条件领取后回填）
     */
    class VectorizePayload(
        val kbId: Long // 知识库ID
    ) {
        var attemptId: String? = null // 执行代次
    }

    override fun taskDisplayName(): String = "向量化" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME // 消费者组名

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX // 消费者名前缀

    override fun threadName(): String = "vectorize-consumer" // 消费线程名

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): VectorizePayload? {
        val kbIdStr = data[AsyncTaskStreamConstants.FIELD_KB_ID]
        if (kbIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        // 兼容旧格式消息：其中的 content 字段直接忽略，正文以数据库实体 + RustFS 为事实来源
        return VectorizePayload(kbIdStr.toLong())
    }

    override fun payloadIdentifier(payload: VectorizePayload): String {
        return "kbId=" + payload.kbId
    }

    override fun shouldSkip(payload: VectorizePayload): Boolean {
        return knowledgeBaseRepository.findById(payload.kbId)
            .map { kb -> kb.vectorStatus == VectorStatus.COMPLETED }
            .orElse(true)
    }

    /**
     * 条件领取模式下 markProcessing 不再使用（照抄题目生成消费者的做法）。
     */
    override fun markProcessing(payload: VectorizePayload) {
        // tryMarkProcessing 原子领取
    }

    /**
     * 条件领取：只有 PENDING → PROCESSING 成功的消费者才执行任务（多实例防重复）。
     */
    override fun tryMarkProcessing(payload: VectorizePayload): Boolean {
        val attemptId = UUID.randomUUID().toString()
        val claimed = knowledgeBaseRepository.tryMarkVectorProcessing(
            payload.kbId, attemptId, LocalDateTime.now()
        ) == 1
        if (claimed) {
            payload.attemptId = attemptId
            lastEmbeddingHeartbeatNanos = 0
        }
        return claimed
    }

    /**
     * 心跳：独立短事务条件更新，只推进仍为 PROCESSING 的行。
     */
    private fun heartbeat(payload: VectorizePayload) {
        val updated = knowledgeBaseRepository.heartbeatVectorProcessing(
            payload.kbId, payload.attemptId ?: "", LocalDateTime.now()
        )
        if (updated != 1) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "向量化任务执行权已失效")
        }
    }

    /**
     * Embedding 阶段节流心跳：距上次超过阈值才写库一次。
     */
    private fun throttledEmbeddingHeartbeat(payload: VectorizePayload) {
        val now = System.nanoTime()
        val throttleNanos = recoveryProperties.heartbeatThrottle.toNanos()
        if (lastEmbeddingHeartbeatNanos != 0L && now - lastEmbeddingHeartbeatNanos < throttleNanos) {
            return
        }
        heartbeat(payload)
        lastEmbeddingHeartbeatNanos = now
    }

    override fun processBusiness(payload: VectorizePayload) {
        val kbId = payload.kbId
        val kb = knowledgeBaseRepository.findById(kbId).orElse(null)
        if (kb == null) {
            log.warn("知识库已被删除，跳过向量化任务: kbId={}", kbId)
            return
        }
        if (kb.storageKey.isNullOrBlank() || kb.originalFilename.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "知识库缺少存储信息，无法下载原文件")
        }
        val content = parseService.downloadAndParseContent(kb.storageKey, kb.originalFilename)
        // 下载 + 解析完成心跳
        heartbeat(payload)
        if (content.isBlank()) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容")
        }
        vectorService.vectorizeAndStore(
            kbId, content, payload.attemptId, {
                // 分块完成与每个 Embedding 批次后的节流心跳
                throttledEmbeddingHeartbeat(payload)
            },
            KnowledgeBaseVectorService.SourceInfo(kb.name, kb.originalFilename, kb.fileHash)
        )
    }

    override fun markCompleted(payload: VectorizePayload) {
        // 条件写入：状态已不是 PROCESSING（被恢复/重试路径改写）时 no-op，防止覆盖他人结果
        knowledgeBaseRepository.completeVectorIfProcessing(
            payload.kbId, payload.attemptId ?: "", LocalDateTime.now()
        )
    }

    override fun markFailed(payload: VectorizePayload, error: String?) {
        knowledgeBaseRepository.failVectorIfProcessing(
            payload.kbId, payload.attemptId ?: "", truncateError(error),
            LocalDateTime.now()
        )
    }

    override fun retryMessage(payload: VectorizePayload, retryCount: Int) {
        val kbId = payload.kbId
        // 条件领取的配套重置：PROCESSING → PENDING 成功才重新投递，否则任务已完成/被替代，只记录并 ACK 丢弃
        val reset = knowledgeBaseRepository.resetVectorToPending(
            kbId, payload.attemptId ?: "", LocalDateTime.now()
        )
        if (reset != 1) {
            log.warn("重试重置失败（任务已完成或状态不再匹配），ACK 丢弃: kbId={}, retryCount={}", kbId, retryCount)
            return
        }
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_KB_ID to kbId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount)
        } catch (e: Exception) {
            log.error(
                "重试入队失败: kbId={}, error={}", kbId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            knowledgeBaseRepository.failVectorIfPending(
                kbId, "重试入队失败", LocalDateTime.now()
            )
        }
    }
}

package com.example.business.listener

import com.example.business.constants.KnowledgeBaseStreamConstants
import com.example.business.enums.VectorStatus
import com.example.business.service.KnowledgeBaseVectorService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 向量化任务消费者
 *
 * 负责从 Redis Stream 拉取任务并执行向量化。
 */
@Component
class VectorizeStreamConsumer(
    private val stringRedisTemplate: StringRedisTemplate,
    private val vectorService: KnowledgeBaseVectorService,
    @Value("\${app.ai.rag.vectorize.stream-key:kb:vectorize:stream}")
    private val streamKey: String,
    @Value("\${app.ai.rag.vectorize.group:kb-vectorize-group}")
    private val groupName: String,
    @Value("\${app.ai.rag.vectorize.consumer-prefix:kb-vectorize-consumer-}")
    private val consumerPrefix: String,
    @Value("\${app.ai.rag.vectorize.poll-interval-ms:1000}")
    private val pollIntervalMs: Long,
    @Value("\${app.ai.rag.vectorize.batch-size:1}")
    private val batchSize: Long,
    @Value("\${app.ai.rag.vectorize.max-retry:3}")
    private val maxRetry: Int,
    @Value("\${app.ai.rag.vectorize.max-stream-len:2000}")
    private val maxStreamLen: Long
) {
    private val logger = LoggerFactory.getLogger(VectorizeStreamConsumer::class.java)
    private val consumerName: String = consumerPrefix + UUID.randomUUID().toString().substring(0, 8)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * 轮询消费向量化任务
     */
    @Scheduled(fixedDelayString = "\${app.ai.rag.vectorize.poll-interval-ms:1000}")
    @Transactional(rollbackFor = [Exception::class])
    fun poll() {
        ensureGroup()
        val streamOps = stringRedisTemplate.opsForStream<String, String>()
        val records = streamOps.read(
            Consumer.from(groupName, consumerName),
            StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(pollIntervalMs)),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        ) ?: return

        for (record in records) {
            val messageId = record.id
            val fields = record.value
            val kbId = fields[KnowledgeBaseStreamConstants.FIELD_KB_ID]?.toLongOrNull()
            val content = fields[KnowledgeBaseStreamConstants.FIELD_CONTENT]
            val retryCount = fields[KnowledgeBaseStreamConstants.FIELD_RETRY_COUNT]?.toIntOrNull() ?: 0

            if (kbId == null || content.isNullOrBlank()) {
                logger.warn("向量化消息格式错误: messageId={}", messageId.value)
                streamOps.acknowledge(streamKey, groupName, messageId)
                continue
            }

            try {
                updateVectorStatus(kbId, VectorStatus.PROCESSING, null, null)
                val chunkCount = vectorService.vectorizeAndStore(kbId, content)
                updateVectorStatus(kbId, VectorStatus.COMPLETED, null, chunkCount)
                streamOps.acknowledge(streamKey, groupName, messageId)
            } catch (ex: Exception) {
                logger.error("向量化处理失败: kbId={}, error={}", kbId, ex.message, ex)
                handleFailure(kbId, content, retryCount, ex.message)
                streamOps.acknowledge(streamKey, groupName, messageId)
            }
        }
    }

    private fun handleFailure(kbId: Long, content: String, retryCount: Int, error: String?) {
        val nextRetry = retryCount + 1
        if (nextRetry > maxRetry) {
            updateVectorStatus(kbId, VectorStatus.FAILED, error ?: "向量化失败", null)
            return
        }

        val record = org.springframework.data.redis.connection.stream.StreamRecords.mapBacked<String, String, String>(
            mapOf(
                KnowledgeBaseStreamConstants.FIELD_KB_ID to kbId.toString(),
                KnowledgeBaseStreamConstants.FIELD_CONTENT to content,
                KnowledgeBaseStreamConstants.FIELD_RETRY_COUNT to nextRetry.toString()
            )
        ).withStreamKey(streamKey)
        stringRedisTemplate.opsForStream<String, String>().add(record)
        stringRedisTemplate.opsForStream<String, String>().trim(streamKey, maxStreamLen)
        updateVectorStatus(kbId, VectorStatus.PENDING, error ?: "向量化失败，已重试", null)
    }

    private fun updateVectorStatus(kbId: Long, status: VectorStatus, error: String?, chunkCount: Int?) {
        val safeError = error?.let { if (it.length > 500) it.substring(0, 500) else it }
        if (chunkCount != null) {
            entityManager.createQuery(
                """
                UPDATE KnowledgeBaseEntity kb
                SET kb.vectorStatus = :status,
                    kb.vectorError = :error,
                    kb.chunkCount = :chunkCount
                WHERE kb.id = :kbId
                """.trimIndent()
            )
                .setParameter("status", status)
                .setParameter("error", safeError)
                .setParameter("chunkCount", chunkCount)
                .setParameter("kbId", kbId)
                .executeUpdate()
        } else {
            entityManager.createQuery(
                """
                UPDATE KnowledgeBaseEntity kb
                SET kb.vectorStatus = :status,
                    kb.vectorError = :error
                WHERE kb.id = :kbId
                """.trimIndent()
            )
                .setParameter("status", status)
                .setParameter("error", safeError)
                .setParameter("kbId", kbId)
                .executeUpdate()
        }
    }

    private fun ensureGroup() {
        val streamOps = stringRedisTemplate.opsForStream<String, String>()
        try {
            streamOps.createGroup(streamKey, ReadOffset.latest(), groupName)
        } catch (ex: Exception) {
            try {
                val initRecordId = streamOps.add(streamKey, mapOf("init" to "1"))
                streamOps.createGroup(streamKey, ReadOffset.latest(), groupName)
                if (initRecordId != null) {
                    streamOps.delete(streamKey, initRecordId)
                }
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }
}

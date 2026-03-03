package com.example.business.listener

import com.example.business.constants.KnowledgeBaseStreamConstants
import com.example.business.enums.VectorStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 向量化任务生产者
 *
 * 负责将向量化任务写入 Redis Stream。
 */
@Component
class VectorizeStreamProducer(
    private val stringRedisTemplate: StringRedisTemplate,
    @Value("\${app.ai.rag.vectorize.stream-key:kb:vectorize:stream}")
    private val streamKey: String
) {
    private val logger = LoggerFactory.getLogger(VectorizeStreamProducer::class.java)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * 发送向量化任务
     *
     * @param kbId 知识库ID
     * @param content 文档内容
     */
    @Transactional(rollbackFor = [Exception::class])
    fun sendVectorizeTask(kbId: Long, content: String) {
        try {
            val record = StreamRecords.mapBacked<String, String, String>(
                mapOf(
                    KnowledgeBaseStreamConstants.FIELD_KB_ID to kbId.toString(),
                    KnowledgeBaseStreamConstants.FIELD_CONTENT to content,
                    KnowledgeBaseStreamConstants.FIELD_RETRY_COUNT to "0"
                )
            ).withStreamKey(streamKey)
            val recordId = stringRedisTemplate.opsForStream<String, String>().add(record)
            logger.info("向量化任务已入队: kbId={}, recordId={}", kbId, recordId?.value)
        } catch (ex: Exception) {
            logger.error("向量化任务入队失败: kbId={}, error={}", kbId, ex.message, ex)
            updateVectorStatus(kbId, VectorStatus.FAILED, ex.message ?: "向量化任务入队失败", null)
        }
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
}

package com.example.business.service

import com.example.business.entity.KnowledgeBaseEntity
import com.example.business.entity.KnowledgeBaseVectorEntity
import com.example.business.repository.KnowledgeBaseVectorRepository
import com.example.framework.ai.embedding.OpenAiEmbeddingProperties
import com.example.framework.core.exception.BusinessException
import dev.langchain4j.model.embedding.EmbeddingModel
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.query.NativeQuery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

/**
 * 知识库向量服务
 *
 * 负责文本分块、向量化与相似度检索。
 */
@Service
class KnowledgeBaseVectorService(
    private val embeddingModel: EmbeddingModel,
    private val embeddingProperties: OpenAiEmbeddingProperties,
    private val vectorRepository: KnowledgeBaseVectorRepository,
    @Value("\${app.ai.rag.vectorize.chunk-size:800}")
    private val chunkSize: Int,
    @Value("\${app.ai.rag.vectorize.chunk-overlap:100}")
    private val chunkOverlap: Int,
    @Value("\${app.ai.rag.vectorize.embedding-batch-size:10}")
    private val embeddingBatchSize: Int
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseVectorService::class.java)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * 向量化并写入数据库
     *
     * @param knowledgeBaseId 知识库ID
     * @param content 文本内容
     * @return 分块数量
     */
    @Transactional(rollbackFor = [Exception::class])
    fun vectorizeAndStore(knowledgeBaseId: Long, content: String): Int {
        val normalized = normalizeContent(content)
        val chunks = splitIntoChunks(normalized)
        if (chunks.isEmpty()) {
            throw BusinessException("无法解析有效文本内容")
        }

        deleteByKnowledgeBaseId(knowledgeBaseId)

        val embeddings = embedChunks(chunks)
        if (embeddings.size != chunks.size) {
            throw BusinessException("向量化结果数量与分块数量不一致")
        }

        val knowledgeBaseRef = entityManager.getReference(KnowledgeBaseEntity::class.java, knowledgeBaseId)
        val now = LocalDateTime.now()
        val entities = chunks.mapIndexed { index, chunk ->
            KnowledgeBaseVectorEntity().apply {
                this.knowledgeBase = knowledgeBaseRef // 关联知识库
                this.chunkIndex = index // 分块索引
                this.content = chunk // 分块内容
                this.embedding = embeddings[index].toFloatArray() // 向量数据
                this.createdAt = now // 创建时间
            }
        }
        vectorRepository.saveAll(entities)

        logger.info("向量化完成: kbId={}, chunks={}", knowledgeBaseId, chunks.size)
        return chunks.size
    }

    /**
     * 相似度检索
     *
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表
     * @param topK 返回数量
     * @param minScore 最小相似度
     * @return 相似文档列表
     */
    fun similaritySearch(
        query: String,
        knowledgeBaseIds: List<Long>?,
        topK: Int,
        minScore: Double
    ): List<VectorSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        val embedding = embedSingle(normalizedQuery)
        val vectorParam = buildVectorLiteral(embedding)
        val safeTopK = max(topK, 1)

        val sql = StringBuilder()
        sql.append(
            """
            SELECT content,
                   1 - (embedding <=> :queryVector::vector) AS score
            FROM knowledge_base_vectors
            """.trimIndent()
        )

        if (!knowledgeBaseIds.isNullOrEmpty()) {
            sql.append(" WHERE knowledge_base_id IN (:kbIds)")
        }

        sql.append(" ORDER BY embedding <=> :queryVector::vector LIMIT :topK")

        val nativeQuery = entityManager.createNativeQuery(sql.toString()).unwrap(NativeQuery::class.java)
        nativeQuery.setParameter("queryVector", vectorParam)
        nativeQuery.setParameter("topK", safeTopK)
        if (!knowledgeBaseIds.isNullOrEmpty()) {
            nativeQuery.setParameterList("kbIds", knowledgeBaseIds)
        }

        val results = nativeQuery.resultList.map { row ->
            val columns = row as Array<*>
            VectorSearchResult(
                content = columns[0] as String, // 分块内容
                score = (columns[1] as Number).toDouble() // 相似度分值
            )
        }

        if (minScore <= 0) {
            return results
        }

        return results.filter { it.score >= minScore }
    }

    /**
     * 删除指定知识库的向量数据
     *
     * @param knowledgeBaseId 知识库ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long) {
        val deleted = entityManager.createQuery(
            "DELETE FROM KnowledgeBaseVectorEntity v WHERE v.knowledgeBase.id = :kbId"
        )
            .setParameter("kbId", knowledgeBaseId)
            .executeUpdate()
        if (deleted > 0) {
            logger.info("删除向量数据: kbId={}, deleted={}", knowledgeBaseId, deleted)
        }
    }

    /**
     * 标准化文本内容
     *
     * @param content 原始文本
     * @return 标准化后的文本
     */
    private fun normalizeContent(content: String): String {
        return content.replace("\r\n", "\n").trim()
    }

    /**
     * 文本分块
     *
     * @param content 标准化后的文本
     * @return 分块列表
     */
    private fun splitIntoChunks(content: String): List<String> {
        if (content.isBlank()) {
            return emptyList()
        }
        val safeChunkSize = max(chunkSize, 200)
        val safeOverlap = min(max(chunkOverlap, 0), safeChunkSize / 2)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val end = min(start + safeChunkSize, content.length)
            val chunk = content.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }
            if (end >= content.length) {
                break
            }
            start = end - safeOverlap
            if (start < 0) {
                start = 0
            }
        }
        return chunks
    }

    /**
     * 批量向量化分块
     *
     * @param chunks 分块列表
     * @return 向量列表
     */
    private fun embedChunks(chunks: List<String>): List<List<Float>> {
        val results = mutableListOf<List<Float>>()
        val batchSize = max(1, embeddingBatchSize)
        var index = 0
        while (index < chunks.size) {
            val end = min(index + batchSize, chunks.size)
            val batch = chunks.subList(index, end)
            results.addAll(embedBatch(batch))
            index = end
        }
        return results
    }

    /**
     * 向量化单批文本
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    private fun embedBatch(texts: List<String>): List<List<Float>> {
        return texts.map { text ->
            val response = embeddingModel.embed(text)
            val embedding = response.content()
            val vector = embedding.vector().toList()
            validateVectorSize(vector)
            vector
        }
    }

    /**
     * 向量化单条文本
     *
     * @param text 文本内容
     * @return 向量
     */
    private fun embedSingle(text: String): List<Float> {
        val response = embeddingModel.embed(text)
        val embedding = response.content()
        val vector = embedding.vector().toList()
        validateVectorSize(vector)
        return vector
    }

    /**
     * 校验向量维度
     *
     * @param vector 向量数据
     */
    private fun validateVectorSize(vector: List<Float>) {
        if (vector.size != embeddingProperties.dimensions) {
            throw BusinessException("向量维度不匹配，期望 ${embeddingProperties.dimensions}，实际 ${vector.size}")
        }
    }

    /**
     * 构建 pgvector 文本格式
     *
     * @param vector 向量数据
     * @return pgvector 字面量字符串
     */
    private fun buildVectorLiteral(vector: List<Float>): String {
        return vector.joinToString(prefix = "[", postfix = "]")
    }

    /**
     * 向量检索结果
     */
    data class VectorSearchResult(
        val content: String, // 分块内容
        val score: Double // 相似度分数
    )
}

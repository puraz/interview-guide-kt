package com.example.business.service

import com.example.framework.ai.embedding.OpenAiEmbeddingProperties
import com.example.framework.core.exception.BusinessException
import dev.langchain4j.model.embedding.EmbeddingModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
    private val jdbcTemplate: JdbcTemplate,
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate,
    @Value("\${app.ai.rag.vectorize.chunk-size:800}")
    private val chunkSize: Int,
    @Value("\${app.ai.rag.vectorize.chunk-overlap:100}")
    private val chunkOverlap: Int,
    @Value("\${app.ai.rag.vectorize.embedding-batch-size:10}")
    private val embeddingBatchSize: Int
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseVectorService::class.java)

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

        val sql = """
            INSERT INTO knowledge_base_vectors
            (knowledge_base_id, chunk_index, content, embedding, created_at)
            VALUES (?, ?, ?, ?::vector, ?)
        """.trimIndent()

        val now = LocalDateTime.now()
        jdbcTemplate.batchUpdate(
            sql,
            chunks.mapIndexed { index, chunk ->
                arrayOf(
                    knowledgeBaseId,
                    index,
                    chunk,
                    buildVectorLiteral(embeddings[index]),
                    now
                )
            }
        )

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
            """
        )

        val params = mutableMapOf<String, Any>(
            "queryVector" to vectorParam,
            "topK" to safeTopK
        )

        if (!knowledgeBaseIds.isNullOrEmpty()) {
            sql.append(" WHERE knowledge_base_id IN (:kbIds)")
            params["kbIds"] = knowledgeBaseIds
        }

        sql.append(" ORDER BY embedding <=> :queryVector::vector LIMIT :topK")

        val results = namedParameterJdbcTemplate.query(
            sql.toString(),
            params
        ) { rs, _ ->
            VectorSearchResult(
                content = rs.getString("content"), // 分块内容
                score = rs.getDouble("score") // 相似度分值
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
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long) {
        val deleted = jdbcTemplate.update(
            "DELETE FROM knowledge_base_vectors WHERE knowledge_base_id = ?",
            knowledgeBaseId
        )
        if (deleted > 0) {
            logger.info("删除向量数据: kbId={}, deleted={}", knowledgeBaseId, deleted)
        }
    }

    private fun normalizeContent(content: String): String {
        return content.replace("\r\n", "\n").trim()
    }

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

    private fun embedBatch(texts: List<String>): List<List<Float>> {
        return texts.map { text ->
            val response = embeddingModel.embed(text)
            val embedding = response.content()
            val vector = embedding.vector().toList()
            validateVectorSize(vector)
            vector
        }
    }

    private fun embedSingle(text: String): List<Float> {
        val response = embeddingModel.embed(text)
        val embedding = response.content()
        val vector = embedding.vector().toList()
        validateVectorSize(vector)
        return vector
    }

    private fun validateVectorSize(vector: List<Float>) {
        if (vector.size != embeddingProperties.dimensions) {
            throw BusinessException("向量维度不匹配，期望 ${embeddingProperties.dimensions}，实际 ${vector.size}")
        }
    }

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

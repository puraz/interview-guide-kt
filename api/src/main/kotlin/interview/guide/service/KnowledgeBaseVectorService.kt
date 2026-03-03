package interview.guide.service

import interview.guide.repository.VectorRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TextSplitter
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Service
class KnowledgeBaseVectorService(
    private val vectorStore: VectorStore, // 向量存储
    private val vectorRepository: VectorRepository // 向量操作仓库
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseVectorService::class.java)

    private val textSplitter: TextSplitter = TokenTextSplitter() // 文本分块器

    companion object {
        private const val MAX_BATCH_SIZE = 10 // Embedding 批量限制
    }

    /**
     * 将知识库内容向量化并存储
     */
    @Transactional
    fun vectorizeAndStore(knowledgeBaseId: Long, content: String) {
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, content.length)
        try {
            deleteByKnowledgeBaseId(knowledgeBaseId)

            val chunks = textSplitter.apply(listOf(Document(content)))
            log.info("文本分块完成: {} 个chunks", chunks.size)

            chunks.forEach { it.metadata["kb_id"] = knowledgeBaseId.toString() }

            val totalChunks = chunks.size
            val batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个", totalChunks, batchCount, MAX_BATCH_SIZE)

            for (i in 0 until batchCount) {
                val start = i * MAX_BATCH_SIZE
                val end = minOf(start + MAX_BATCH_SIZE, totalChunks)
                val batch = chunks.subList(start, end)
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end)
                vectorStore.add(batch)
            }

            log.info("知识库向量化完成: kbId={}, chunks={}, batches={}", knowledgeBaseId, totalChunks, batchCount)
        } catch (e: Exception) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.message, e)
            throw RuntimeException("向量化知识库失败: ${e.message}", e)
        }
    }

    /**
     * 基于多个知识库进行相似度搜索
     */
    fun similaritySearch(query: String, knowledgeBaseIds: List<Long>?, topK: Int, minScore: Double): List<Document> {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}", query, knowledgeBaseIds, topK, minScore)
        try {
            val builder = SearchRequest.builder()
                .query(query)
                .topK(maxOf(topK, 1))

            if (minScore > 0) {
                builder.similarityThreshold(minScore)
            }

            if (!knowledgeBaseIds.isNullOrEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds))
            }

            val results = vectorStore.similaritySearch(builder.build()) ?: emptyList()
            log.info("搜索完成: 找到 {} 个相关文档", results.size)
            return results
        } catch (e: Exception) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.message)
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore)
        }
    }

    private fun similaritySearchFallback(query: String, knowledgeBaseIds: List<Long>?, topK: Int, minScore: Double): List<Document> {
        try {
            val builder = SearchRequest.builder()
                .query(query)
                .topK(maxOf(topK * 3, topK))
            if (minScore > 0) {
                builder.similarityThreshold(minScore)
            }

            var allResults = vectorStore.similaritySearch(builder.build()) ?: emptyList()
            if (!knowledgeBaseIds.isNullOrEmpty()) {
                allResults = allResults.filter { isDocInKnowledgeBases(it, knowledgeBaseIds) }
            }

            val results = allResults.take(topK)
            log.info("回退检索完成: 找到 {} 个相关文档", results.size)
            return results
        } catch (e: Exception) {
            log.error("向量搜索失败: {}", e.message, e)
            throw RuntimeException("向量搜索失败: ${e.message}", e)
        }
    }

    private fun isDocInKnowledgeBases(doc: Document, knowledgeBaseIds: List<Long>): Boolean {
        val kbId = doc.metadata["kb_id"] ?: return false
        return try {
            val kbIdLong = if (kbId is Long) kbId else kbId.toString().toLong()
            knowledgeBaseIds.contains(kbIdLong)
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun buildKbFilterExpression(knowledgeBaseIds: List<Long>): String {
        val values = knowledgeBaseIds
            .filterNotNull()
            .joinToString(", ") { "'$it'" }
        return "kb_id in [$values]"
    }

    /**
     * 删除指定知识库的所有向量数据
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)
        } catch (e: Exception) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.message, e)
        }
    }
}

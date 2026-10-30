package interview.guide.modules.knowledgebase.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.common.transaction.TransactionalExecutor
import interview.guide.modules.knowledgebase.repository.VectorRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.stereotype.Service
import org.springframework.util.DigestUtils
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Service
class KnowledgeBaseVectorService(
    private val vectorStore: VectorStore, // 向量存储
    private val vectorRepository: VectorRepository, // 向量仓库（临时向量提升/清理）
    private val transactionalExecutor: TransactionalExecutor?, // 事务执行器（测试场景可为空）
    private val vectorProperties: KnowledgeBaseVectorProperties, // Chunk 策略配置
    private val persistenceService: KnowledgeBasePersistenceService? // 持久化服务（测试场景可为空）
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseVectorService::class.java)

    // Chunk 策略由 app.ai.rag.vectorization 配置驱动（仅 Spring AI 原生参数，无 overlap）
    private val textSplitter: TextSplitter = vectorProperties.createTextSplitter()

    /**
     * 文件来源信息，供 metadata 记录可复现标识。
     * 文件哈希只用于可复现标识，不用于鉴权；来源信息由已加载的业务实体提供。
     */
    data class SourceInfo(
        val name: String?, // 知识库/文档名称
        val filename: String?, // 原始文件名
        val fileHash: String? // 文件哈希
    )

    /**
     * 将知识库内容向量化并存储
     *
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    fun vectorizeAndStore(knowledgeBaseId: Long, content: String) {
        vectorizeAndStore(knowledgeBaseId, content, null, {}, SourceInfo("", "", null))
    }

    /**
     * 带进度心跳回调的向量化：分块完成与每个 Embedding 批次后回调（由调用方决定节流）。
     */
    fun vectorizeAndStore(knowledgeBaseId: Long, content: String, progressHeartbeat: () -> Unit) {
        vectorizeAndStore(knowledgeBaseId, content, null, progressHeartbeat, SourceInfo("", "", null))
    }

    /**
     * 带执行代次的向量化。临时向量仅在数据库锁内确认当前代次仍有效后才会被提升。
     */
    fun vectorizeAndStore(
        knowledgeBaseId: Long,
        content: String,
        attemptId: String?,
        progressHeartbeat: () -> Unit
    ) {
        vectorizeAndStore(knowledgeBaseId, content, attemptId, progressHeartbeat, SourceInfo("", "", null))
    }

    /**
     * 带执行代次与来源信息的向量化。
     *
     * @param knowledgeBaseId 知识库ID
     * @param content 文本内容
     * @param attemptId 执行代次（空表示直接调用，不做代次校验）
     * @param progressHeartbeat 进度心跳回调
     * @param source 来源信息
     */
    fun vectorizeAndStore(
        knowledgeBaseId: Long?,
        content: String,
        attemptId: String?,
        progressHeartbeat: () -> Unit,
        source: SourceInfo
    ) {
        var jobId: String? = null
        try {
            if (knowledgeBaseId == null) {
                throw IllegalArgumentException("knowledgeBaseId不能为空")
            }
            // 本次向量化任务ID（临时向量提升时使用）
            val currentJobId = UUID.randomUUID().toString()
            jobId = currentJobId
            log.info(
                "开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, currentJobId, content.length
            )

            // 1. 将文本分块
            val configJson = vectorConfigJson()
            val fileHash = if (source.fileHash.isNullOrBlank()) {
                DigestUtils.md5DigestAsHex(content.toByteArray(StandardCharsets.UTF_8))
            } else {
                source.fileHash
            }
            val metadata = LinkedHashMap<String, Any>()
            metadata["source_kb_id"] = knowledgeBaseId.toString()
            metadata["source_name"] = source.name ?: ""
            metadata["source_filename"] = source.filename ?: ""
            metadata["chunk_strategy"] = vectorProperties.strategyVersion
            val chunks = textSplitter.apply(listOf(Document(content, metadata)))
            for (i in chunks.indices) {
                val chunk = chunks[i]
                chunk.metadata["chunk_index"] = i
                chunk.metadata["total_chunks"] = chunks.size
                // 仅 metadata 稳定；保留随机 Document ID，避免 pending upsert 覆盖旧正式向量。
                chunk.metadata["source_id"] = DigestUtils.md5DigestAsHex(
                    ("$fileHash\n$configJson\n$i").toByteArray(StandardCharsets.UTF_8)
                )
            }

            log.info("文本分块完成: {} 个chunks", chunks.size)

            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, currentJobId)

            // 分块完成心跳
            progressHeartbeat()

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            val totalChunks = chunks.size
            val batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE // 向上取整
            log.info(
                "开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                totalChunks, batchCount, MAX_BATCH_SIZE
            )
            for (i in 0 until batchCount) {
                val start = i * MAX_BATCH_SIZE
                val end = minOf(start + MAX_BATCH_SIZE, totalChunks)
                val batch = chunks.subList(start, end)
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end)
                vectorStore.add(batch)
                // Embedding 批次心跳（调用方节流）
                progressHeartbeat()
            }
            if (attemptId != null && persistenceService != null) {
                persistenceService.activateVectorJobAndUpdateSnapshot(
                    knowledgeBaseId, attemptId, currentJobId, totalChunks, configJson
                )
            } else {
                activateVectorJob(knowledgeBaseId, currentJobId)
                // 兼容直接调用：提升成功后再以独立短事务写入快照
                updateVectorizationSnapshot(knowledgeBaseId, totalChunks, configJson)
            }
            log.info(
                "知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                knowledgeBaseId, currentJobId, totalChunks, batchCount
            )
        } catch (e: Exception) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId)
            log.error(
                "向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            throw BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量化知识库失败")
        }
    }

    private fun applyPendingMetadata(chunks: List<Document>, knowledgeBaseId: Long, jobId: String) {
        val pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId
        chunks.forEach { chunk ->
            chunk.metadata[METADATA_KB_ID] = pendingKbId
            chunk.metadata[METADATA_TARGET_KB_ID] = knowledgeBaseId.toString()
            chunk.metadata[METADATA_VECTOR_JOB_ID] = jobId
            // 在 add 前写入策略标识：promote 只改 kb_id，不会回填
            chunk.metadata[METADATA_CHUNK_STRATEGY] = vectorProperties.strategyVersion
        }
    }

    /**
     * 基于多个知识库进行相似度搜索
     *
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @param minScore 最小相似度阈值
     * @return 相关文档列表
     */
    fun similaritySearch(
        query: String,
        knowledgeBaseIds: List<Long>?,
        topK: Int,
        minScore: Double
    ): List<Document> {
        log.info(
            "向量相似度搜索: queryLength={}, kbIds={}, topK={}, minScore={}",
            query.length, knowledgeBaseIds, topK, minScore
        )

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

            val results = vectorStore.similaritySearch(builder.build())
                ?: return emptyList()

            // Apply topK limiting in case VectorStore returns more than requested
            val limitedResults = results.take(topK)

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size)
            return limitedResults
        } catch (e: Exception) {
            log.warn(
                "向量搜索前置过滤失败，回退到本地过滤: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore)
        }
    }

    private fun similaritySearchFallback(
        query: String,
        knowledgeBaseIds: List<Long>?,
        topK: Int,
        minScore: Double
    ): List<Document> {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            val builder = SearchRequest.builder()
                .query(query)
                .topK(maxOf(topK * 3, topK))
            if (minScore > 0) {
                builder.similarityThreshold(minScore)
            }

            var allResults = vectorStore.similaritySearch(builder.build())
            if (allResults.isNullOrEmpty()) {
                return emptyList()
            }

            if (!knowledgeBaseIds.isNullOrEmpty()) {
                allResults = allResults.filter { doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds) }
            }

            val results = allResults.take(topK)

            log.info("回退检索完成: 找到 {} 个相关文档", results.size)
            return results
        } catch (e: Exception) {
            log.error(
                "向量搜索失败: {}", ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            throw BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量搜索失败")
        }
    }

    private fun isDocInKnowledgeBases(doc: Document, knowledgeBaseIds: List<Long>): Boolean {
        val kbId = doc.metadata["kb_id"] ?: return false
        try {
            val kbIdLong = if (kbId is Long) kbId else kbId.toString().toLong()
            return knowledgeBaseIds.contains(kbIdLong)
        } catch (e: NumberFormatException) {
            return false
        }
    }

    private fun buildKbFilterExpression(knowledgeBaseIds: List<Long>): String {
        val values = knowledgeBaseIds
            .filterNotNull()
            .joinToString(", ") { id -> "'" + id + "'" }
        return "kb_id in [$values]"
    }

    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     *
     * @param knowledgeBaseId 知识库ID
     */
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId)
        } catch (e: Exception) {
            log.error(
                "删除向量数据失败: kbId={}, error={}", knowledgeBaseId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            // 不抛出异常，允许继续执行其他删除操作
        }
    }

    private fun deleteByKnowledgeBaseIdStrict(knowledgeBaseId: Long) {
        runVectorRepositoryMutation { vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId) }
    }

    private fun activateVectorJob(knowledgeBaseId: Long, jobId: String) {
        runVectorRepositoryMutation {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId)
        }
    }

    /**
     * 向量化成功后的配置快照：chunkCount、vectorConfig（无密钥 JSON）、vectorizedAt。
     * 失败（含清理）路径不会调用；与 Embedding 写入不在同一事务。
     */
    private fun updateVectorizationSnapshot(knowledgeBaseId: Long, chunkCount: Int, configJson: String) {
        val service = persistenceService ?: return
        val update = { service.updateVectorizationSnapshot(knowledgeBaseId, chunkCount, configJson) }
        val executor = transactionalExecutor
        if (executor == null) {
            update()
            return
        }
        executor.run(update)
    }

    private fun vectorConfigJson(): String {
        val config = LinkedHashMap<String, Any>()
        config["splitter"] = vectorProperties.splitter
        config["chunkSize"] = vectorProperties.chunkSize
        config["minChunkSizeChars"] = vectorProperties.minChunkSizeChars
        config["minChunkLengthToEmbed"] = vectorProperties.minChunkLengthToEmbed
        config["maxNumChunks"] = vectorProperties.maxNumChunks
        config["keepSeparator"] = vectorProperties.keepSeparator
        config["punctuationMarks"] = vectorProperties.punctuationMarks
        config["strategyVersion"] = vectorProperties.strategyVersion
        try {
            return ObjectMapper().writeValueAsString(config)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "向量化配置快照序列化失败")
        }
    }

    private fun cleanupPendingVectorJob(knowledgeBaseId: Long?, jobId: String?) {
        if (jobId == null) {
            return
        }
        try {
            runVectorRepositoryMutation { vectorRepository.deleteByVectorJobId(jobId) }
        } catch (cleanupError: Exception) {
            log.warn(
                "清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(cleanupError),
                ErrorLogSanitizer.forLogging(cleanupError)
            )
        }
    }

    private fun runVectorRepositoryMutation(action: () -> Unit) {
        val executor = transactionalExecutor
        if (executor == null) {
            action()
            return
        }
        executor.run(action)
    }

    companion object {
        /**
         * 阿里云 DashScope Embedding API 批量大小限制
         */
        private const val MAX_BATCH_SIZE: Int = 10
        private const val TEMP_KB_ID_PREFIX: String = "pending:"
        private const val METADATA_KB_ID: String = "kb_id"
        private const val METADATA_TARGET_KB_ID: String = "kb_target_id"
        private const val METADATA_VECTOR_JOB_ID: String = "kb_vector_job_id"
        private const val METADATA_CHUNK_STRATEGY: String = "chunk_strategy"
    }
}

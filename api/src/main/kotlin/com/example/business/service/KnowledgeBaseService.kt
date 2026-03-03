package com.example.business.service

import com.example.business.entity.KnowledgeBaseEntity
import com.example.business.entity.RagChatMessageEntity
import com.example.business.enums.VectorStatus
import com.example.business.repository.KnowledgeBaseRepository
import com.example.framework.ai.chat.AiChatClient
import com.example.framework.ai.chat.AiChatMessage
import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatRole
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.stripCodeFences
import com.example.framework.extra.file.model.FileDeleteParam
import com.example.framework.extra.file.strategy.FileContext
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * 知识库服务
 *
 * 负责知识库的上传、解析、列表、删除、统计与向量化任务投递。
 */
@Service
class KnowledgeBaseService(
    private val aiChatClient: AiChatClient,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
    private val fileContext: FileContext,
    private val vectorService: KnowledgeBaseVectorService,
    private val vectorizeStreamProducer: com.example.business.listener.VectorizeStreamProducer,
    @Value("classpath:prompts/knowledgebase-query-system.st")
    private val systemPromptResource: Resource,
    @Value("classpath:prompts/knowledgebase-query-user.st")
    private val userPromptResource: Resource,
    @Value("classpath:prompts/knowledgebase-query-rewrite.st")
    private val rewritePromptResource: Resource,
    @Value("\${app.ai.rag.rewrite.enabled:true}")
    private val rewriteEnabled: Boolean,
    @Value("\${app.ai.rag.search.short-query-length:4}")
    private val shortQueryLength: Int,
    @Value("\${app.ai.rag.search.topk-short:20}")
    private val topkShort: Int,
    @Value("\${app.ai.rag.search.topk-medium:12}")
    private val topkMedium: Int,
    @Value("\${app.ai.rag.search.topk-long:8}")
    private val topkLong: Int,
    @Value("\${app.ai.rag.search.min-score-short:0.18}")
    private val minScoreShort: Double,
    @Value("\${app.ai.rag.search.min-score-default:0.28}")
    private val minScoreDefault: Double
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseService::class.java)
    private val tika = Tika()
    private val systemPrompt: String = readResource(systemPromptResource)
    private val userPromptTemplate: String = readResource(userPromptResource)
    private val rewritePromptTemplate: String = readResource(rewritePromptResource)

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * 上传知识库
     *
     * @param file 上传文件
     * @param name 知识库名称
     * @param category 分类
     * @return 上传结果
     */
    fun uploadKnowledgeBase(file: MultipartFile, name: String?, category: String?): KnowledgeBaseUploadVo {
        validateFile(file)

        val contentType = detectContentType(file)
        validateContentType(contentType, file.originalFilename)

        val fileHash = calculateHash(file)
        val existing = findByFileHash(fileHash)
        if (existing != null) {
            return handleDuplicateKnowledgeBase(existing, fileHash)
        }

        val content = parseContent(file)
        if (content.isBlank()) {
            throw BusinessException("无法从文件中提取文本内容")
        }

        val fileInfo = fileContext.upload(file, "knowledgebase")
        val storageKey = fileInfo.path
        val storageUrl = fileInfo.url

        val saved = saveKnowledgeBase(
            file = file,
            name = name,
            category = category,
            storageKey = storageKey,
            storageUrl = storageUrl,
            fileHash = fileHash
        )

        vectorizeStreamProducer.sendVectorizeTask(saved.id, content)

        return KnowledgeBaseUploadVo(
            knowledgeBase = KnowledgeBaseUploadKnowledgeBaseVo(
                id = saved.id, // 知识库ID
                name = saved.name, // 知识库名称
                category = saved.category ?: "", // 分类
                fileSize = saved.fileSize, // 文件大小
                contentLength = content.length, // 内容长度
                vectorStatus = VectorStatus.PENDING.name // 向量化状态
            ),
            storage = KnowledgeBaseUploadStorageVo(
                fileKey = storageKey ?: "", // 存储Key
                fileUrl = storageUrl ?: "" // 存储URL
            ),
            duplicate = false // 是否重复
        )
    }

    /**
     * 重新向量化
     *
     * @param kbId 知识库ID
     */
    fun revectorize(kbId: Long) {
        val info = getEntityForDownload(kbId)
        val content = downloadAndParseContent(info.storageKey, info.storageUrl, info.originalFilename)
        if (content.isBlank()) {
            throw BusinessException("无法从文件中提取文本内容")
        }
        updateVectorStatusToPending(kbId)
        vectorizeStreamProducer.sendVectorizeTask(kbId, content)
    }

    /**
     * 删除知识库
     *
     * @param id 知识库ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteKnowledgeBase(id: Long) {
        val info = getEntityForDownload(id)

        entityManager.createNativeQuery(
            "DELETE FROM rag_session_knowledge_bases WHERE knowledge_base_id = :kbId"
        )
            .setParameter("kbId", id)
            .executeUpdate()

        try {
            vectorService.deleteByKnowledgeBaseId(id)
        } catch (ex: Exception) {
            logger.warn("删除向量数据失败: kbId={}, error={}", id, ex.message)
        }

        try {
            val storageKey = info.storageKey
            if (!storageKey.isNullOrBlank()) {
                fileContext.delete(FileDeleteParam(path = storageKey))
            }
        } catch (ex: Exception) {
            logger.warn("删除存储文件失败: kbId={}, error={}", id, ex.message)
        }

        val entity = knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException("知识库不存在")
        knowledgeBaseRepository.delete(entity)
    }

    /**
     * 获取知识库列表
     *
     * @param vectorStatus 向量化状态
     * @param sortBy 排序字段
     * @return 知识库列表
     */
    fun listKnowledgeBases(vectorStatus: VectorStatus?, sortBy: String?): List<KnowledgeBaseListItemVo> {
        val query = if (vectorStatus == null) {
            entityManager.createQuery(
                """
                SELECT kb
                FROM KnowledgeBaseEntity kb
                ORDER BY kb.uploadedAt DESC
                """.trimIndent(),
                KnowledgeBaseEntity::class.java
            )
        } else {
            entityManager.createQuery(
                """
                SELECT kb
                FROM KnowledgeBaseEntity kb
                WHERE kb.vectorStatus = :status
                ORDER BY kb.uploadedAt DESC
                """.trimIndent(),
                KnowledgeBaseEntity::class.java
            ).setParameter("status", vectorStatus)
        }
        val list = query.resultList.map { toListItemVo(it) }
        return sortIfNeeded(list, sortBy)
    }

    /**
     * 获取知识库详情
     *
     * @param id 知识库ID
     * @return 知识库详情
     */
    fun getKnowledgeBase(id: Long): KnowledgeBaseListItemVo? {
        val entity = knowledgeBaseRepository.findById(id).orElse(null) ?: return null
        return toListItemVo(entity)
    }

    /**
     * 获取知识库名称列表
     *
     * @param ids 知识库ID列表
     * @return 知识库名称列表
     */
    fun getKnowledgeBaseNames(ids: List<Long>): List<String> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val rows = entityManager.createQuery(
            """
            SELECT kb.id, kb.name
            FROM KnowledgeBaseEntity kb
            WHERE kb.id IN :ids
            """.trimIndent(),
            Array<Any>::class.java
        )
            .setParameter("ids", ids)
            .resultList
        val nameMap = rows.associate { row ->
            val columns = row as Array<*>
            (columns[0] as Number).toLong() to (columns[1] as String)
        }
        return ids.map { nameMap[it] ?: "未知知识库" }
    }

    /**
     * 根据ID列表获取知识库列表
     *
     * @param ids 知识库ID列表
     * @return 知识库列表
     */
    fun listByIds(ids: List<Long>): List<KnowledgeBaseListItemVo> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val list = entityManager.createQuery(
            """
            SELECT kb
            FROM KnowledgeBaseEntity kb
            WHERE kb.id IN :ids
            """.trimIndent(),
            KnowledgeBaseEntity::class.java
        )
            .setParameter("ids", ids)
            .resultList
        return list.map { toListItemVo(it) }
    }

    /**
     * 获取所有分类
     *
     * @return 分类列表
     */
    fun getAllCategories(): List<String> {
        return entityManager.createQuery(
            """
            SELECT DISTINCT kb.category
            FROM KnowledgeBaseEntity kb
            WHERE kb.category IS NOT NULL
            ORDER BY kb.category
            """.trimIndent(),
            String::class.java
        )
            .resultList
    }

    /**
     * 根据分类获取知识库列表
     *
     * @param category 分类
     * @return 知识库列表
     */
    fun listByCategory(category: String?): List<KnowledgeBaseListItemVo> {
        val query = if (category.isNullOrBlank()) {
            entityManager.createQuery(
                """
                SELECT kb
                FROM KnowledgeBaseEntity kb
                WHERE kb.category IS NULL
                ORDER BY kb.uploadedAt DESC
                """.trimIndent(),
                KnowledgeBaseEntity::class.java
            )
        } else {
            entityManager.createQuery(
                """
                SELECT kb
                FROM KnowledgeBaseEntity kb
                WHERE kb.category = :category
                ORDER BY kb.uploadedAt DESC
                """.trimIndent(),
                KnowledgeBaseEntity::class.java
            ).setParameter("category", category)
        }
        return query.resultList.map { toListItemVo(it) }
    }

    /**
     * 更新分类
     *
     * @param id 知识库ID
     * @param category 分类
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateCategory(id: Long, category: String?) {
        val resolvedCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val entity = knowledgeBaseRepository.findById(id).orElse(null)
        if (entity == null) {
            throw BusinessException("知识库不存在")
        }
        entity.category = resolvedCategory // 更新分类
        knowledgeBaseRepository.save(entity)
    }

    /**
     * 搜索知识库
     *
     * @param keyword 关键字
     * @return 搜索结果
     */
    fun search(keyword: String): List<KnowledgeBaseListItemVo> {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) {
            return listKnowledgeBases(null, null)
        }
        val kw = "%${trimmed.lowercase()}%"
        val list = entityManager.createQuery(
            """
            SELECT kb
            FROM KnowledgeBaseEntity kb
            WHERE LOWER(kb.name) LIKE :kw OR LOWER(kb.originalFilename) LIKE :kw
            ORDER BY kb.uploadedAt DESC
            """.trimIndent(),
            KnowledgeBaseEntity::class.java
        )
            .setParameter("kw", kw)
            .resultList
        return list.map { toListItemVo(it) }
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    fun getStatistics(): KnowledgeBaseStatsVo {
        val totalCount = (entityManager.createQuery(
            "SELECT COUNT(kb) FROM KnowledgeBaseEntity kb",
            Number::class.java
        ).singleResult ?: 0L).toLong()
        val totalQuestionCount = (entityManager.createQuery(
            "SELECT COUNT(msg) FROM RagChatMessageEntity msg WHERE msg.type = :type",
            Number::class.java
        )
            .setParameter("type", RagChatMessageEntity.MessageType.USER)
            .singleResult ?: 0L).toLong()
        val totalAccessCount = (entityManager.createQuery(
            "SELECT COALESCE(SUM(kb.accessCount), 0) FROM KnowledgeBaseEntity kb",
            Number::class.java
        ).singleResult ?: 0L).toLong()
        val completedCount = (entityManager.createQuery(
            "SELECT COUNT(kb) FROM KnowledgeBaseEntity kb WHERE kb.vectorStatus = :status",
            Number::class.java
        )
            .setParameter("status", VectorStatus.COMPLETED)
            .singleResult ?: 0L).toLong()
        val processingCount = (entityManager.createQuery(
            "SELECT COUNT(kb) FROM KnowledgeBaseEntity kb WHERE kb.vectorStatus = :status",
            Number::class.java
        )
            .setParameter("status", VectorStatus.PROCESSING)
            .singleResult ?: 0L).toLong()

        return KnowledgeBaseStatsVo(
            totalCount = totalCount, // 知识库总数
            totalQuestionCount = totalQuestionCount, // 总提问次数
            totalAccessCount = totalAccessCount, // 总访问次数
            completedCount = completedCount, // 已完成数量
            processingCount = processingCount // 处理中数量
        )
    }

    /**
     * 下载文件
     *
     * @param id 知识库ID
     * @return 文件字节
     */
    fun downloadFile(id: Long): ByteArray {
        val info = getEntityForDownload(id)
        val storageKey = info.storageKey
        val storageUrl = info.storageUrl
        val inputStream = storageKey?.let { fileContext.getFileInputStream(it) }
        if (inputStream != null) {
            return inputStream.use { it.readBytes() }
        }
        if (!storageUrl.isNullOrBlank()) {
            return cn.hutool.http.HttpUtil.downloadBytes(storageUrl)
        }
        throw BusinessException("文件存储信息不存在")
    }

    /**
     * 获取下载所需信息
     *
     * @param id 知识库ID
     * @return 下载信息
     */
    fun getEntityForDownload(id: Long): KnowledgeBaseDownloadVo {
        val entity = knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException("知识库不存在")
        return KnowledgeBaseDownloadVo(
            id = entity.id, // 知识库ID
            originalFilename = entity.originalFilename, // 原始文件名
            contentType = entity.contentType, // 内容类型
            storageKey = entity.storageKey, // 存储Key
            storageUrl = entity.storageUrl // 存储URL
        )
    }

    /**
     * 批量更新提问次数
     *
     * @param knowledgeBaseIds 知识库ID列表
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateQuestionCounts(knowledgeBaseIds: List<Long>?) {
        if (knowledgeBaseIds.isNullOrEmpty()) {
            return
        }
        val uniqueIds = knowledgeBaseIds.distinct()
        val existingIds = queryExistingIds(uniqueIds)
        if (existingIds.size != uniqueIds.size) {
            val missing = uniqueIds.filterNot { existingIds.contains(it) }
            throw BusinessException("知识库不存在: ${missing.joinToString(",")}")
        }
        entityManager.createQuery(
            """
            UPDATE KnowledgeBaseEntity kb
            SET kb.questionCount = COALESCE(kb.questionCount, 0) + 1
            WHERE kb.id IN :ids
            """.trimIndent()
        )
            .setParameter("ids", uniqueIds)
            .executeUpdate()
    }

    /**
     * 基于多知识库回答问题
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    fun answerQuestion(knowledgeBaseIds: List<Long>?, question: String): String {
        val normalizedQuestion = normalizeQuestion(question)
        if (knowledgeBaseIds.isNullOrEmpty() || normalizedQuestion.isBlank()) {
            return NO_RESULT_RESPONSE
        }

        updateQuestionCounts(knowledgeBaseIds)

        val queryContext = buildQueryContext(normalizedQuestion)
        val docs = retrieveRelevantDocs(queryContext, knowledgeBaseIds)
        if (!hasEffectiveHit(normalizedQuestion, docs)) {
            return NO_RESULT_RESPONSE
        }

        val context = docs.joinToString("\n\n---\n\n") { it.content }
        val request = AiChatRequest(
            messages = listOf(
                AiChatMessage(role = AiChatRole.SYSTEM, content = buildSystemPrompt()),
                AiChatMessage(role = AiChatRole.USER, content = buildUserPrompt(context, normalizedQuestion))
            )
        )
        val result = aiChatClient.chat(request)
        if (!result.success || result.content.isNullOrBlank()) {
            throw BusinessException("知识库查询失败：${result.errorMessage ?: "AI返回空内容"}")
        }
        return normalizeAnswer(result.content.stripCodeFences())
    }

    /**
     * 查询知识库并返回结构化响应
     *
     * @param request 查询参数
     * @return 查询响应
     */
    fun queryKnowledgeBase(request: QueryParam): QueryResponseVo {
        val answer = answerQuestion(request.knowledgeBaseIds, request.question)
        val kbNames = getKnowledgeBaseNames(request.knowledgeBaseIds)
        val kbName = kbNames.joinToString("、")
        val primaryKbId = request.knowledgeBaseIds.first()
        return QueryResponseVo(
            answer = answer, // AI回答
            knowledgeBaseId = primaryKbId, // 知识库ID
            knowledgeBaseName = kbName // 知识库名称
        )
    }

    /**
     * 流式回答
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式输出
     */
    fun answerQuestionStream(knowledgeBaseIds: List<Long>?, question: String): reactor.core.publisher.Flux<String> {
        val normalizedQuestion = normalizeQuestion(question)
        if (knowledgeBaseIds.isNullOrEmpty() || normalizedQuestion.isBlank()) {
            return reactor.core.publisher.Flux.just(NO_RESULT_RESPONSE)
        }

        return reactor.core.publisher.Flux.create { sink ->
            val probeBuffer = StringBuilder()
            val fullBuilder = StringBuilder()
            val passthrough = AtomicBoolean(false)
            val completed = AtomicBoolean(false)

            thread(start = true, name = "knowledgebase-stream") {
                try {
                    updateQuestionCounts(knowledgeBaseIds)

                    val queryContext = buildQueryContext(normalizedQuestion)
                    val docs = retrieveRelevantDocs(queryContext, knowledgeBaseIds)
                    if (!hasEffectiveHit(normalizedQuestion, docs)) {
                        sink.next(NO_RESULT_RESPONSE)
                        sink.complete()
                        return@thread
                    }

                    val context = docs.joinToString("\n\n---\n\n") { it.content }
                    val request = AiChatRequest(
                        messages = listOf(
                            AiChatMessage(role = AiChatRole.SYSTEM, content = buildSystemPrompt()),
                            AiChatMessage(role = AiChatRole.USER, content = buildUserPrompt(context, normalizedQuestion))
                        )
                    )

                    aiChatClient.streamChat(request) { chunk ->
                        if (sink.isCancelled || completed.get()) {
                            return@streamChat
                        }
                        val delta = chunk.contentDelta.orEmpty()
                        val cleanedDelta = if (delta.isNotBlank()) delta.stripCodeFences() else ""
                        if (cleanedDelta.isNotEmpty()) {
                            fullBuilder.append(cleanedDelta)
                        }

                        if (chunk.finished) {
                            if (!passthrough.get() && !completed.get()) {
                                val normalized = normalizeAnswer(fullBuilder.toString().stripCodeFences())
                                sink.next(normalized)
                            }
                            completed.set(true)
                            sink.complete()
                            return@streamChat
                        }

                        if (passthrough.get()) {
                            if (cleanedDelta.isNotBlank()) {
                                sink.next(cleanedDelta)
                            }
                            return@streamChat
                        }

                        if (cleanedDelta.isNotBlank()) {
                            probeBuffer.append(cleanedDelta)
                            val probeText = probeBuffer.toString()
                            if (isNoResultLike(probeText)) {
                                completed.set(true)
                                sink.next(NO_RESULT_RESPONSE)
                                sink.complete()
                                return@streamChat
                            }
                            if (probeBuffer.length >= STREAM_PROBE_CHARS) {
                                passthrough.set(true)
                                sink.next(probeText)
                                probeBuffer.setLength(0)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    logger.error("知识库流式查询失败: {}", ex.message, ex)
                    if (!sink.isCancelled) {
                        sink.next("【错误】知识库查询失败：${ex.message}")
                        sink.complete()
                    }
                }
            }
        }
    }

    /**
     * 更新向量化状态为 PENDING
     *
     * @param kbId 知识库ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateVectorStatusToPending(kbId: Long) {
        updateVectorStatus(kbId, VectorStatus.PENDING, null, null)
    }

    /**
     * 更新向量化状态
     *
     * @param kbId 知识库ID
     * @param status 状态
     * @param error 错误信息
     * @param chunkCountValue 分块数量
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateVectorStatus(kbId: Long, status: VectorStatus, error: String?, chunkCountValue: Int?) {
        val safeError = error?.let { if (it.length > 500) it.substring(0, 500) else it }
        if (chunkCountValue != null) {
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
                .setParameter("chunkCount", chunkCountValue)
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

    /**
     * 处理重复知识库上传
     *
     * @param existing 已存在的知识库实体
     * @param fileHash 文件哈希
     * @return 上传响应
     */
    private fun handleDuplicateKnowledgeBase(
        existing: KnowledgeBaseEntity,
        fileHash: String
    ): KnowledgeBaseUploadVo {
        val newCount = (existing.accessCount ?: 0) + 1
        existing.accessCount = newCount // 更新访问次数
        existing.lastAccessedAt = LocalDateTime.now() // 更新访问时间
        knowledgeBaseRepository.save(existing)

        logger.info("检测到重复知识库: id={}, hash={}", existing.id, fileHash)

        return KnowledgeBaseUploadVo(
            knowledgeBase = KnowledgeBaseUploadKnowledgeBaseVo(
                id = existing.id, // 知识库ID
                name = existing.name, // 知识库名称
                category = existing.category ?: "", // 分类
                fileSize = existing.fileSize, // 文件大小
                contentLength = 0, // 重复文件不再返回内容长度
                vectorStatus = existing.vectorStatus?.name // 向量化状态
            ),
            storage = KnowledgeBaseUploadStorageVo(
                fileKey = existing.storageKey ?: "", // 存储Key
                fileUrl = existing.storageUrl ?: "" // 存储URL
            ),
            duplicate = true // 是否重复
        )
    }

    /**
     * 保存知识库基本信息
     *
     * @param file 上传文件
     * @param name 知识库名称
     * @param category 分类
     * @param storageKey 存储Key
     * @param storageUrl 存储URL
     * @param fileHash 文件哈希
     * @return 保存结果
     */
    private fun saveKnowledgeBase(
        file: MultipartFile,
        name: String?,
        category: String?,
        storageKey: String?,
        storageUrl: String?,
        fileHash: String
    ): KnowledgeBaseSaved {
        val now = LocalDateTime.now()
        val resolvedName = if (!name.isNullOrBlank()) name.trim() else extractNameFromFilename(file.originalFilename)
        val resolvedCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val entity = KnowledgeBaseEntity().apply {
            this.fileHash = fileHash // 文件hash
            this.name = resolvedName // 知识库名称
            this.category = resolvedCategory // 分类
            this.originalFilename = file.originalFilename ?: resolvedName // 原始文件名
            this.fileSize = file.size // 文件大小
            this.contentType = file.contentType // 文件类型
            this.storageKey = storageKey // 存储Key
            this.storageUrl = storageUrl // 存储URL
            this.uploadedAt = now // 上传时间
            this.lastAccessedAt = null // 最后访问时间
            this.accessCount = 0 // 访问次数
            this.questionCount = 0 // 提问次数
            this.vectorStatus = VectorStatus.PENDING // 向量化状态
            this.vectorError = null // 向量化错误
            this.chunkCount = 0 // 分块数量
        }
        val saved = knowledgeBaseRepository.save(entity)

        return KnowledgeBaseSaved(
            id = saved.id, // 知识库ID
            name = resolvedName, // 知识库名称
            category = resolvedCategory, // 分类
            fileSize = file.size, // 文件大小
            storageKey = storageKey, // 存储Key
            storageUrl = storageUrl // 存储URL
        )
    }

    /**
     * 根据文件哈希查询知识库
     *
     * @param fileHash 文件哈希
     * @return 知识库实体
     */
    private fun findByFileHash(fileHash: String): KnowledgeBaseEntity? {
        return entityManager.createQuery(
            """
            SELECT kb
            FROM KnowledgeBaseEntity kb
            WHERE kb.fileHash = :fileHash
            """.trimIndent(),
            KnowledgeBaseEntity::class.java
        )
            .setParameter("fileHash", fileHash)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
    }

    /**
     * 查询存在的知识库ID集合
     *
     * @param ids 待校验的知识库ID列表
     * @return 已存在的ID集合
     */
    private fun queryExistingIds(ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) {
            return emptySet()
        }
        val results = entityManager.createQuery(
            "SELECT kb.id FROM KnowledgeBaseEntity kb WHERE kb.id IN :ids",
            java.lang.Long::class.java
        )
            .setParameter("ids", ids)
            .resultList
        return results.map { it.toLong() }.toSet()
    }

    private fun buildQueryContext(question: String): QueryContext {
        val rewritten = rewriteQuestion(question)
        val candidateQueries = linkedSetOf<String>()
        candidateQueries.add(rewritten)
        candidateQueries.add(question)
        val searchParams = resolveSearchParams(question)
        return QueryContext(question, candidateQueries.toList(), searchParams)
    }

    private fun retrieveRelevantDocs(
        queryContext: QueryContext,
        knowledgeBaseIds: List<Long>
    ): List<KnowledgeBaseVectorService.VectorSearchResult> {
        for (candidate in queryContext.candidateQueries) {
            if (candidate.isBlank()) {
                continue
            }
            val docs = vectorService.similaritySearch(
                candidate,
                knowledgeBaseIds,
                queryContext.searchParams.topK,
                queryContext.searchParams.minScore
            )
            logger.info("检索候选问题命中: query={}, hits={}", candidate, docs.size)
            if (hasEffectiveHit(candidate, docs)) {
                return docs
            }
        }
        return emptyList()
    }

    private fun resolveSearchParams(question: String): SearchParams {
        val compactLength = question.replace("\\s+".toRegex(), "").length
        return when {
            compactLength <= shortQueryLength -> SearchParams(topkShort, minScoreShort)
            compactLength <= 12 -> SearchParams(topkMedium, minScoreDefault)
            else -> SearchParams(topkLong, minScoreDefault)
        }
    }

    private fun rewriteQuestion(question: String): String {
        if (!rewriteEnabled || question.isBlank()) {
            return question
        }
        return try {
            val prompt = renderTemplate(rewritePromptTemplate, mapOf("question" to question))
            val request = AiChatRequest(
                messages = listOf(
                    AiChatMessage(role = AiChatRole.USER, content = prompt)
                )
            )
            val result = aiChatClient.chat(request)
            val rewritten = result.content?.stripCodeFences()?.trim()
            if (rewritten.isNullOrBlank()) question else rewritten
        } catch (ex: Exception) {
            logger.warn("问题重写失败，使用原问题: {}", ex.message)
            question
        }
    }

    private fun normalizeQuestion(question: String?): String {
        return question?.trim().orEmpty()
    }

    private fun buildSystemPrompt(): String {
        return systemPrompt
    }

    private fun buildUserPrompt(context: String, question: String): String {
        return renderTemplate(userPromptTemplate, mapOf("context" to context, "question" to question))
    }

    private fun renderTemplate(template: String, variables: Map<String, Any?>): String {
        var rendered = template
        for ((key, value) in variables) {
            rendered = rendered.replace("{$key}", value?.toString() ?: "")
        }
        return rendered
    }

    private fun readResource(resource: Resource): String {
        return resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun hasEffectiveHit(question: String, docs: List<KnowledgeBaseVectorService.VectorSearchResult>): Boolean {
        if (docs.isEmpty()) {
            return false
        }
        if (!isShortTokenQuery(question)) {
            return true
        }
        val lowered = question.lowercase()
        return docs.any { it.content.lowercase().contains(lowered) }
    }

    private fun isShortTokenQuery(question: String): Boolean {
        val compact = question.trim()
        return SHORT_TOKEN_PATTERN.matcher(compact).matches()
    }

    private fun normalizeAnswer(answer: String?): String {
        if (answer.isNullOrBlank()) {
            return NO_RESULT_RESPONSE
        }
        val normalized = answer.trim()
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE
        }
        return normalized
    }

    private fun isNoResultLike(text: String): Boolean {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答")
    }

    private fun parseContent(file: MultipartFile): String {
        if (file.isEmpty) {
            throw BusinessException("文件为空，无法解析")
        }
        return try {
            file.inputStream.use { input -> tika.parseToString(input) }
        } catch (ex: Exception) {
            logger.error("解析知识库文件失败: filename={}", file.originalFilename, ex)
            throw BusinessException("解析知识库文件失败")
        }
    }

    private fun parseContent(fileBytes: ByteArray, fileName: String): String {
        if (fileBytes.isEmpty()) {
            throw BusinessException("文件内容为空，无法解析")
        }
        return try {
            ByteArrayInputStream(fileBytes).use { input ->
                val metadata = Metadata()
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
                tika.parseToString(input, metadata)
            }
        } catch (ex: Exception) {
            logger.error("解析知识库字节内容失败: filename={}", fileName, ex)
            throw BusinessException("解析知识库文件失败")
        }
    }

    private fun downloadAndParseContent(storageKey: String?, storageUrl: String?, originalFilename: String): String {
        if (storageKey.isNullOrBlank() && storageUrl.isNullOrBlank()) {
            throw BusinessException("文件存储信息缺失，无法解析")
        }
        val inputStream = storageKey?.let { fileContext.getFileInputStream(it) }
        if (inputStream != null) {
            return try {
                inputStream.use {
                    val metadata = Metadata()
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFilename)
                    tika.parseToString(it, metadata)
                }
            } catch (ex: Exception) {
                logger.error("解析存储文件失败: key={}", storageKey, ex)
                throw BusinessException("解析知识库文件失败")
            }
        }
        if (storageUrl.isNullOrBlank()) {
            throw BusinessException("文件存储地址缺失，无法解析")
        }
        return try {
            val bytes = cn.hutool.http.HttpUtil.downloadBytes(storageUrl)
            parseContent(bytes, originalFilename)
        } catch (ex: Exception) {
            logger.error("下载并解析文件失败: url={}", storageUrl, ex)
            throw BusinessException("解析知识库文件失败")
        }
    }

    private fun detectContentType(file: MultipartFile): String {
        if (file.isEmpty) {
            return ""
        }
        return try {
            file.inputStream.use { input -> tika.detect(input, file.originalFilename) }
        } catch (ex: Exception) {
            logger.warn("检测文件类型失败: filename={}", file.originalFilename, ex)
            file.contentType ?: ""
        }
    }

    private fun validateFile(file: MultipartFile) {
        if (file.isEmpty) {
            throw BusinessException("上传文件不能为空")
        }
        if (file.size > MAX_FILE_SIZE) {
            throw BusinessException("文件大小不能超过 50MB")
        }
    }

    private fun validateContentType(contentType: String, fileName: String?) {
        if (contentType.isBlank() && fileName.isNullOrBlank()) {
            throw BusinessException("无法识别文件类型")
        }
        val lowerName = fileName?.lowercase() ?: ""
        val isAllowed = ALLOWED_MIME_TYPES.contains(contentType)
            || ALLOWED_EXTENSIONS.any { lowerName.endsWith(it) }
        if (!isAllowed) {
            throw BusinessException("不支持的文件类型: $contentType")
        }
    }

    private fun calculateHash(file: MultipartFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractNameFromFilename(filename: String?): String {
        if (filename.isNullOrBlank()) {
            return "未命名知识库"
        }
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0) filename.substring(0, lastDot) else filename
    }

    private fun sortIfNeeded(list: List<KnowledgeBaseListItemVo>, sortBy: String?): List<KnowledgeBaseListItemVo> {
        if (sortBy.isNullOrBlank() || sortBy.equals("time", ignoreCase = true)) {
            return list
        }
        return when (sortBy.lowercase()) {
            "size" -> list.sortedByDescending { it.fileSize ?: 0L }
            "access" -> list.sortedByDescending { it.accessCount ?: 0 }
            "question" -> list.sortedByDescending { it.questionCount ?: 0 }
            else -> list
        }
    }

    /**
     * 将实体转换为列表展示数据
     *
     * @param entity 知识库实体
     * @return 列表展示数据
     */
    private fun toListItemVo(entity: KnowledgeBaseEntity): KnowledgeBaseListItemVo {
        return KnowledgeBaseListItemVo(
            id = entity.id, // 知识库ID
            name = entity.name, // 知识库名称
            category = entity.category, // 分类
            originalFilename = entity.originalFilename, // 原始文件名
            fileSize = entity.fileSize, // 文件大小
            contentType = entity.contentType, // 内容类型
            uploadedAt = entity.uploadedAt, // 上传时间
            lastAccessedAt = entity.lastAccessedAt, // 最后访问时间
            accessCount = entity.accessCount, // 访问次数
            questionCount = entity.questionCount, // 提问次数
            vectorStatus = entity.vectorStatus, // 向量化状态
            vectorError = entity.vectorError, // 向量化错误
            chunkCount = entity.chunkCount // 分块数量
        )
    }

    /**
     * 知识库列表项
     */
    data class KnowledgeBaseListItemVo(
        val id: Long, // 知识库ID
        val name: String, // 知识库名称
        val category: String?, // 分类
        val originalFilename: String, // 原始文件名
        val fileSize: Long?, // 文件大小
        val contentType: String?, // 内容类型
        val uploadedAt: LocalDateTime?, // 上传时间
        val lastAccessedAt: LocalDateTime?, // 最后访问时间
        val accessCount: Int?, // 访问次数
        val questionCount: Int?, // 提问次数
        val vectorStatus: VectorStatus?, // 向量化状态
        val vectorError: String?, // 向量化错误
        val chunkCount: Int? // 分块数量
    )

    /**
     * 知识库统计信息
     */
    data class KnowledgeBaseStatsVo(
        val totalCount: Long, // 知识库总数
        val totalQuestionCount: Long, // 总提问次数
        val totalAccessCount: Long, // 总访问次数
        val completedCount: Long, // 已完成数量
        val processingCount: Long // 处理中数量
    )

    /**
     * 下载信息
     */
    data class KnowledgeBaseDownloadVo(
        val id: Long, // 知识库ID
        val originalFilename: String, // 原始文件名
        val contentType: String?, // 内容类型
        val storageKey: String?, // 存储Key
        val storageUrl: String? // 存储URL
    )

    private data class KnowledgeBaseSaved(
        val id: Long, // 知识库ID
        val name: String, // 知识库名称
        val category: String?, // 分类
        val fileSize: Long?, // 文件大小
        val storageKey: String?, // 存储Key
        val storageUrl: String? // 存储URL
    )

    /**
     * 上传结果
     */
    data class KnowledgeBaseUploadVo(
        val knowledgeBase: KnowledgeBaseUploadKnowledgeBaseVo, // 知识库信息
        val storage: KnowledgeBaseUploadStorageVo, // 存储信息
        val duplicate: Boolean // 是否重复
    )

    /**
     * 上传结果-知识库信息
     */
    data class KnowledgeBaseUploadKnowledgeBaseVo(
        val id: Long, // 知识库ID
        val name: String, // 知识库名称
        val category: String, // 分类
        val fileSize: Long?, // 文件大小
        val contentLength: Int, // 内容长度
        val vectorStatus: String? // 向量化状态
    )

    /**
     * 上传结果-存储信息
     */
    data class KnowledgeBaseUploadStorageVo(
        val fileKey: String, // 存储Key
        val fileUrl: String // 存储URL
    )

    /**
     * 查询参数
     */
    data class QueryParam(
        @field:NotEmpty(message = "至少选择一个知识库")
        val knowledgeBaseIds: List<Long>, // 知识库ID列表
        @field:NotBlank(message = "问题不能为空")
        val question: String // 用户问题
    )

    /**
     * 查询响应
     */
    data class QueryResponseVo(
        val answer: String, // AI回答
        val knowledgeBaseId: Long, // 知识库ID
        val knowledgeBaseName: String // 知识库名称
    )

    private data class SearchParams(
        val topK: Int, // 返回数量
        val minScore: Double // 最小相似度
    )

    private data class QueryContext(
        val originalQuestion: String, // 原始问题
        val candidateQueries: List<String>, // 候选问题
        val searchParams: SearchParams // 检索参数
    )

    companion object {
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L
        private val ALLOWED_MIME_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
        )
        private val ALLOWED_EXTENSIONS = setOf(".pdf", ".doc", ".docx", ".txt", ".md")
        private const val NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。"
        private val SHORT_TOKEN_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]{2,20}$")
        private const val STREAM_PROBE_CHARS = 120
    }
}

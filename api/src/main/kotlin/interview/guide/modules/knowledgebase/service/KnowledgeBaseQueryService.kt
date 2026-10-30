package interview.guide.modules.knowledgebase.service

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSecurityConstants
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.knowledgebase.metrics.RagMetrics
import interview.guide.modules.knowledgebase.model.QueryRequest
import interview.guide.modules.knowledgebase.model.QueryResponse
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.document.Document
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Service
class KnowledgeBaseQueryService(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM 提供商注册表
    private val ragMetrics: RagMetrics, // RAG 业务指标
    private val vectorService: KnowledgeBaseVectorService, // 向量检索服务
    private val listService: KnowledgeBaseListService, // 知识库列表服务
    private val countService: KnowledgeBaseCountService, // 知识库计数服务
    queryProperties: KnowledgeBaseQueryProperties, // RAG 查询配置
    resourceLoader: ResourceLoader // 提示词资源加载器
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseQueryService::class.java)

    private val systemPromptTemplate: PromptTemplate = PromptTemplate(
        resourceLoader.getResource(queryProperties.systemPromptPath)
            .getContentAsString(StandardCharsets.UTF_8)
    )

    private val userPromptTemplate: PromptTemplate = PromptTemplate(
        resourceLoader.getResource(queryProperties.userPromptPath)
            .getContentAsString(StandardCharsets.UTF_8)
    )

    private val rewritePromptTemplate: PromptTemplate = PromptTemplate(
        resourceLoader.getResource(queryProperties.rewritePromptPath)
            .getContentAsString(StandardCharsets.UTF_8)
    )

    private val rewriteEnabled: Boolean = queryProperties.rewrite.enabled // 是否开启 Query 改写
    private val mergeOriginalQuery: Boolean = queryProperties.search.mergeOriginalQuery // 是否开启双路召回融合
    private val shortQueryLength: Int = queryProperties.search.shortQueryLength // 短问题长度阈值
    private val topkShort: Int = queryProperties.search.topkShort // 短问题 Top K
    private val topkMedium: Int = queryProperties.search.topkMedium // 中等长度问题 Top K
    private val topkLong: Int = queryProperties.search.topkLong // 长问题 Top K
    private val minScoreShort: Double = queryProperties.search.minScoreShort // 短问题相似度阈值
    private val minScoreDefault: Double = queryProperties.search.minScoreDefault // 默认相似度阈值

    private fun getChatClient(): ChatClient {
        return llmProviderRegistry.getPlainChatClient()
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    fun answerQuestion(knowledgeBaseId: Long, question: String): String {
        return answerQuestion(listOf(knowledgeBaseId), question)
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    fun answerQuestion(knowledgeBaseIds: List<Long>?, question: String): String {
        val totalStart = System.nanoTime()
        val normalized = normalizeQuestion(question)
        log.info("收到知识库提问: kbIds={}, questionLength={}", knowledgeBaseIds, normalized.length)
        if (knowledgeBaseIds.isNullOrEmpty() || normalized.isBlank()) {
            ragMetrics.recordRequest("sync", "reject")
            ragMetrics.recordRefusal("invalid_request")
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart)
            return NO_RESULT_RESPONSE
        }

        var queryContext: QueryContext? = null
        var retrievalNanos = 0L
        try {
            countService.updateQuestionCounts(knowledgeBaseIds)

            val context = buildQueryContext(question, emptyList())
            queryContext = context
            val retrievalStart = System.nanoTime()
            val relevantDocs = retrieveRelevantDocs(context, knowledgeBaseIds)
            retrievalNanos = System.nanoTime() - retrievalStart

            if (!hasEffectiveHit(relevantDocs)) {
                ragMetrics.recordRequest("sync", "reject")
                ragMetrics.recordRefusal("no_hit")
                ragMetrics.recordStageDuration(
                    "rewrite", "reject", context.rewriteDurationMs * 1_000_000L
                )
                ragMetrics.recordStageDuration("retrieve", "reject", retrievalNanos)
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart)
                return NO_RESULT_RESPONSE
            }

            val promptContext = RagSources.context(relevantDocs)
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(promptContext, question)

            val generationStart = System.nanoTime()
            val answer = normalizeAnswer(
                getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content()
            )
            val generationNanos = System.nanoTime() - generationStart

            val rejected = NO_RESULT_RESPONSE == answer
            ragMetrics.recordRequest("sync", if (rejected) "reject" else "success")
            if (rejected) {
                ragMetrics.recordRefusal("no_hit")
            }
            ragMetrics.recordStageDuration(
                "rewrite", if (rejected) "reject" else "success",
                context.rewriteDurationMs * 1_000_000L
            )
            ragMetrics.recordStageDuration("retrieve", if (rejected) "reject" else "success", retrievalNanos)
            ragMetrics.recordStageDuration("generate", if (rejected) "reject" else "success", generationNanos)
            ragMetrics.recordStageDuration(
                "total", if (rejected) "reject" else "success",
                System.nanoTime() - totalStart
            )

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds)
            return if (rejected) answer else answer + RagSources.footer(relevantDocs)
        } catch (e: Exception) {
            log.error(
                "知识库问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e)
            )
            ragMetrics.recordRequest("sync", "error")
            queryContext?.let { context ->
                ragMetrics.recordStageDuration(
                    "rewrite", "error", context.rewriteDurationMs * 1_000_000L
                )
                ragMetrics.recordStageDuration("retrieve", "error", retrievalNanos)
            }
            ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart)
            throw BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败，请稍后重试")
        }
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return systemPromptTemplate.render() + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
    }

    /**
     * 构建用户提示词
     */
    private fun buildUserPrompt(context: String, question: String): String {
        val variables = HashMap<String, Any>()
        variables["context"] = context
        variables["question"] = question
        return userPromptTemplate.render(variables)
    }

    /**
     * 查询知识库并返回完整响应
     */
    fun queryKnowledgeBase(request: QueryRequest): QueryResponse {
        val answer = answerQuestion(request.knowledgeBaseIds, request.question)

        // 获取知识库名称（多个知识库用逗号分隔）
        val kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds)
        val kbNamesStr = kbNames.joinToString("、")

        // 使用第一个知识库ID作为主要标识（兼容前端）
        val primaryKbId = request.knowledgeBaseIds.first()

        return QueryResponse(answer, primaryKbId, kbNamesStr)
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    fun answerQuestionStream(knowledgeBaseIds: List<Long>?, question: String): Flux<String> {
        return answerQuestionStream(knowledgeBaseIds, question, emptyList(), null)
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    fun answerQuestionStream(
        knowledgeBaseIds: List<Long>?,
        question: String,
        history: List<Message>?
    ): Flux<String> {
        return answerQuestionStream(knowledgeBaseIds, question, history, null)
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文），可选地输出完整执行轨迹。
     * 轨迹收集器仅供测评与可观测性使用，不影响业务行为。
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息
     * @param trace 执行轨迹收集器（可为 null，为 null 时行为与无收集器入口完全一致）
     */
    fun answerQuestionStream(
        knowledgeBaseIds: List<Long>?,
        question: String,
        history: List<Message>?,
        trace: ((RagQueryExecution) -> Unit)?
    ): Flux<String> {
        val totalStart = System.nanoTime()
        val normalized = normalizeQuestion(question)
        log.info(
            "收到知识库流式提问: kbIds={}, questionLength={}, historySize={}", knowledgeBaseIds,
            normalized.length, history?.size ?: 0
        )
        // 一次请求只允许记录一次结果：error 路径（含 onErrorResume 吞异常后的 Flux.just）先落，complete 检查后跳过
        val resultRecorded = AtomicBoolean(false)
        if (knowledgeBaseIds.isNullOrEmpty() || normalized.isBlank()) {
            ragMetrics.recordRequest("stream", "reject")
            ragMetrics.recordRefusal("invalid_request")
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart)
            return Flux.just(NO_RESULT_RESPONSE)
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds)

            // 2. Query rewrite + 动态参数检索
            val rewriteStart = System.nanoTime()
            val effectiveHistory = sanitizeHistory(history)
            val queryContext = buildQueryContext(question, effectiveHistory)
            val attemptedQueries = ArrayList<String>()
            val relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, attemptedQueries)
            val rewriteAndRetrievalMs = (System.nanoTime() - rewriteStart) / 1_000_000

            if (!hasEffectiveHit(relevantDocs)) {
                emitTrace(
                    trace, question, queryContext, attemptedQueries, relevantDocs,
                    rewriteAndRetrievalMs, 0, NO_RESULT_RESPONSE, "NO_RESULT"
                )
                ragMetrics.recordRequest("stream", "reject")
                ragMetrics.recordRefusal("no_hit")
                ragMetrics.recordStageDuration(
                    "rewrite", "reject", queryContext.rewriteDurationMs * 1_000_000L
                )
                ragMetrics.recordStageDuration(
                    "retrieve", "reject",
                    maxOf(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs) * 1_000_000L
                )
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart)
                return Flux.just(NO_RESULT_RESPONSE)
            }

            // 3. 构建上下文
            val context = RagSources.context(relevantDocs)

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size)

            // 4. 构建提示词
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(context, question)

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt)
            if (effectiveHistory.isNotEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory)
            }
            val responseFlux = promptSpec
                .user(userPrompt)
                .stream()
                .content()

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds)
            val generationStart = System.nanoTime()
            val collectedAnswer = StringBuilder()
            return normalizeStreamOutput(responseFlux)
                .doOnNext { chunk -> collectedAnswer.append(chunk) }
                .concatWith(Flux.defer<String> {
                    if (NO_RESULT_RESPONSE == collectedAnswer.toString()) {
                        Flux.empty()
                    } else {
                        val footer = RagSources.footer(relevantDocs)
                        collectedAnswer.append(footer)
                        Flux.just(footer)
                    }
                })
                .doOnComplete {
                    // error 已记录（onErrorResume 转 Flux.just 后仍会走到 complete）时跳过
                    if (!resultRecorded.compareAndSet(false, true)) {
                        return@doOnComplete
                    }
                    val generationNanos = System.nanoTime() - generationStart
                    val rejected = NO_RESULT_RESPONSE == collectedAnswer.toString()
                    val result = if (rejected) "reject" else "success"
                    ragMetrics.recordRequest("stream", result)
                    if (rejected) {
                        ragMetrics.recordRefusal("no_hit")
                    }
                    ragMetrics.recordStageDuration(
                        "rewrite", result, queryContext.rewriteDurationMs * 1_000_000L
                    )
                    ragMetrics.recordStageDuration(
                        "retrieve", result,
                        maxOf(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs) * 1_000_000L
                    )
                    ragMetrics.recordStageDuration("generate", result, generationNanos)
                    ragMetrics.recordStageDuration("total", result, System.nanoTime() - totalStart)
                    emitTrace(
                        trace, question, queryContext, attemptedQueries, relevantDocs,
                        rewriteAndRetrievalMs, generationNanos / 1_000_000,
                        collectedAnswer.toString(),
                        if (rejected) "NO_RESULT" else "ANSWERED"
                    )
                    log.info("流式输出完成: kbIds={}", knowledgeBaseIds)
                }
                .onErrorResume { e ->
                    log.error(
                        "流式输出失败: kbIds={}, error={}", knowledgeBaseIds,
                        ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
                    )
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "error")
                        ragMetrics.recordStageDuration(
                            "generate", "error", System.nanoTime() - generationStart
                        )
                        ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart)
                    }
                    emitTrace(
                        trace, question, queryContext, attemptedQueries, emptyList(),
                        rewriteAndRetrievalMs, (System.nanoTime() - generationStart) / 1_000_000,
                        "【错误】知识库查询失败", "ERROR"
                    )
                    Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。")
                }
                .doOnCancel {
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "cancel")
                        ragMetrics.recordStageDuration(
                            "generate", "cancel", System.nanoTime() - generationStart
                        )
                        ragMetrics.recordStageDuration("total", "cancel", System.nanoTime() - totalStart)
                    }
                }
        } catch (e: Exception) {
            log.error(
                "知识库流式问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e)
            )
            // 外层 catch 返回的错误文案同样记为 error，不是 success
            if (resultRecorded.compareAndSet(false, true)) {
                ragMetrics.recordRequest("stream", "error")
                ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart)
            }
            emitTrace(
                trace, normalizeQuestion(question), null, emptyList(),
                emptyList(), 0, 0, "【错误】知识库查询失败", "ERROR"
            )
            return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。")
        }
    }

    /**
     * 输出执行轨迹（收集器为 null 时直接返回）。
     */
    private fun emitTrace(
        trace: ((RagQueryExecution) -> Unit)?,
        originalQuestion: String,
        queryContext: QueryContext?,
        attemptedQueries: List<String>,
        chosenDocs: List<Document>,
        rewriteAndRetrievalMs: Long,
        generationMs: Long,
        answer: String?,
        outcome: String
    ) {
        if (trace == null) {
            return
        }
        val docs = ArrayList<RagQueryExecution.RetrievedDoc>()
        for (i in chosenDocs.indices) {
            val doc = chosenDocs[i]
            docs.add(
                RagQueryExecution.RetrievedDoc(
                    i + 1, doc.text, extractScore(doc), doc.metadata
                )
            )
        }
        val params = queryContext?.searchParams ?: SearchParams(topkLong, minScoreDefault)
        val rewriteMs = queryContext?.rewriteDurationMs ?: 0L
        trace(
            RagQueryExecution(
                normalizeQuestion(originalQuestion),
                if (queryContext != null && queryContext.candidateQueries.isNotEmpty()) {
                    queryContext.candidateQueries.first()
                } else {
                    normalizeQuestion(originalQuestion)
                },
                attemptedQueries,
                params.topK,
                params.minScore,
                docs.toList(),
                rewriteMs,
                maxOf(0, rewriteAndRetrievalMs - rewriteMs),
                generationMs,
                answer,
                outcome
            )
        )
    }

    private fun extractScore(doc: Document): Double? {
        val score = doc.score
        if (score != null) {
            return score
        }
        val distance = doc.metadata["distance"]
        return if (distance is Number) distance.toDouble() else null
    }

    /**
     * 仅执行检索链路（改写 + 候选检索），不做 LLM 生成。
     * 供 RAG 测评复用生产检索逻辑，避免在测评代码中复制改写与分档规则。
     */
    fun retrieveOnly(
        knowledgeBaseIds: List<Long>?,
        question: String,
        history: List<Message>?
    ): RagQueryExecution {
        val normalized = normalizeQuestion(question)
        if (knowledgeBaseIds.isNullOrEmpty() || normalized.isBlank()) {
            return RagQueryExecution(
                normalized, normalized, listOf(normalized),
                topkLong, minScoreDefault, emptyList(), 0, 0, 0, NO_RESULT_RESPONSE, "NO_RESULT"
            )
        }
        val start = System.nanoTime()
        val effectiveHistory = sanitizeHistory(history)
        val queryContext = buildQueryContext(question, effectiveHistory)
        val attemptedQueries = ArrayList<String>()
        val relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, attemptedQueries)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val hit = hasEffectiveHit(relevantDocs)
        val stageResult = if (hit) "success" else "reject"
        ragMetrics.recordStageDuration(
            "rewrite", stageResult, queryContext.rewriteDurationMs * 1_000_000L
        )
        ragMetrics.recordStageDuration(
            "retrieve", stageResult,
            maxOf(0, elapsedMs - queryContext.rewriteDurationMs) * 1_000_000L
        )
        return RagQueryExecution(
            normalized,
            queryContext.candidateQueries.first(),
            attemptedQueries.toList(),
            queryContext.searchParams.topK,
            queryContext.searchParams.minScore,
            relevantDocs.map { doc ->
                RagQueryExecution.RetrievedDoc(
                    relevantDocs.indexOf(doc) + 1, doc.text, extractScore(doc), doc.metadata
                )
            },
            queryContext.rewriteDurationMs,
            maxOf(0, elapsedMs - queryContext.rewriteDurationMs),
            0,
            null,
            if (hit) "RETRIEVED" else "NO_RESULT"
        )
    }

    private fun buildQueryContext(originalQuestion: String, history: List<Message>?): QueryContext {
        val normalizedQuestion = normalizeQuestion(originalQuestion)
        val rewriteStart = System.nanoTime()
        val rewrittenQuestion = rewriteQuestion(normalizedQuestion, history)
        val rewriteDurationMs = (System.nanoTime() - rewriteStart) / 1_000_000
        val candidates = LinkedHashSet<String>()
        candidates.add(rewrittenQuestion)
        candidates.add(normalizedQuestion)

        val searchParams = resolveSearchParams(normalizedQuestion)
        return QueryContext(normalizedQuestion, ArrayList(candidates), searchParams, rewriteDurationMs)
    }

    private fun sanitizeHistory(history: List<Message>?): List<Message> {
        if (history.isNullOrEmpty()) {
            return emptyList()
        }
        return history
    }

    // 清洗
    private fun normalizeQuestion(question: String?): String {
        return question?.trim() ?: ""
    }

    // 向量检索（attemptedQueries 非空时记录实际尝试过的候选，第一个命中后即返回）
    private fun retrieveRelevantDocs(
        queryContext: QueryContext,
        knowledgeBaseIds: List<Long>
    ): List<Document> {
        return retrieveRelevantDocs(queryContext, knowledgeBaseIds, null)
    }

    private fun retrieveRelevantDocs(
        queryContext: QueryContext,
        knowledgeBaseIds: List<Long>,
        attemptedQueries: MutableList<String>?
    ): List<Document> {
        val candidates = queryContext.candidateQueries
        if (mergeOriginalQuery && candidates.size > 1 && candidates[0] != candidates[1]) {
            return retrieveAndMerge(queryContext, knowledgeBaseIds, attemptedQueries)
        }
        for (i in candidates.indices) {
            val candidateQuery = candidates[i]
            if (candidateQuery.isBlank()) {
                continue
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery)
            }
            val startNanos = System.nanoTime()
            val docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams.topK,
                queryContext.searchParams.minScore
            )
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            log.info(
                "RAG 检索完成: kbCount={}, questionLength={}, queryVariant={}, hits={}, durationMs={}",
                knowledgeBaseIds.size, candidateQuery.length,
                if (i == 0) "rewritten" else "original", docs.size, durationMs
            )
            if (hasEffectiveHit(docs)) {
                ragMetrics.recordRetrievalHits("single", docs.size)
                return docs
            }
        }
        return emptyList()
    }

    /**
     * 双路召回融合：改写 Query 与原始 Query 各自按相同 Top K/阈值检索一次，
     * 按 Document ID 去重保留高分，null 分数排后、同分保持改写路优先的稳定顺序，
     * 按分数降序截取最终 Top K。两路均来自同一向量库与相似度定义，第一版用最大分数融合。
     */
    private fun retrieveAndMerge(
        queryContext: QueryContext,
        knowledgeBaseIds: List<Long>,
        attemptedQueries: MutableList<String>?
    ): List<Document> {
        val merged = LinkedHashMap<String, Document>()
        val topK = queryContext.searchParams.topK
        var rewrittenHits = 0
        var originalHits = 0
        for (i in 0 until 2) {
            val candidateQuery = queryContext.candidateQueries[i]
            if (candidateQuery.isBlank()) {
                continue
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery)
            }
            val docs = vectorService.similaritySearch(
                candidateQuery, knowledgeBaseIds, topK, queryContext.searchParams.minScore
            )
            if (i == 0) {
                rewrittenHits = docs.size
            } else {
                originalHits = docs.size
            }
            for (doc in docs) {
                merged.merge(doc.id, doc) { existing, incoming -> higherScore(existing, incoming) }
            }
        }
        val result = merged.values
            .sortedWith(Comparator { a, b -> scoreDescendingNullsLast(a, b) })
            .take(topK)
        log.info(
            "RAG 双路融合完成: kbCount={}, rewrittenHits={}, originalHits={}, dedupedHits={}, finalHits={}, topK={}",
            knowledgeBaseIds.size, rewrittenHits, originalHits, merged.size, result.size, topK
        )
        ragMetrics.recordRetrievalHits("rewritten", rewrittenHits)
        ragMetrics.recordRetrievalHits("original", originalHits)
        ragMetrics.recordRetrievalHits("merged", result.size)
        return result
    }

    private fun higherScore(existing: Document, incoming: Document): Document {
        val existingScore = existing.score
        val incomingScore = incoming.score
        if (incomingScore == null) {
            return existing
        }
        if (existingScore == null || incomingScore > existingScore) {
            return incoming
        }
        return existing
    }

    /**
     * 分数降序且 null 分数排最后；分数相同时保持"改写 Query 结果优先"的稳定顺序（稳定排序保证）。
     */
    private fun scoreDescendingNullsLast(a: Document, b: Document): Int {
        val scoreA = a.score
        val scoreB = b.score
        if (scoreA == null && scoreB == null) {
            return 0
        }
        if (scoreA == null) {
            return 1
        }
        if (scoreB == null) {
            return -1
        }
        return scoreB.compareTo(scoreA)
    }

    private fun resolveSearchParams(question: String): SearchParams {
        val compactLength = question.replace(Regex("\\s+"), "").length
        if (compactLength <= shortQueryLength) {
            return SearchParams(topkShort, minScoreShort)
        }
        if (compactLength <= 12) {
            return SearchParams(topkMedium, minScoreDefault)
        }
        return SearchParams(topkLong, minScoreDefault)
    }

    // 改写
    private fun rewriteQuestion(question: String, history: List<Message>?): String {
        if (!rewriteEnabled) {
            ragMetrics.recordRewriteFallback("disabled")
            return question
        }
        if (question.isBlank()) {
            ragMetrics.recordRewriteFallback("blank")
            return question
        }
        try {
            val variables = HashMap<String, Any>()
            variables["question"] = question
            variables["history"] = formatHistoryForRewrite(history)
            val rewritePrompt = rewritePromptTemplate.render(variables)
            val rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content()
            if (rewritten.isNullOrBlank()) {                return question
            }
            val normalized = rewritten.trim()
            if (normalized == question) {
                ragMetrics.recordRewriteFallback("unchanged")
            }
            log.info(
                "Query rewrite 完成: originLength={}, rewrittenLength={}, changed={}, historySize={}",
                question.length, normalized.length, normalized != question, history?.size ?: 0
            )
            return normalized
        } catch (e: Exception) {
            ragMetrics.recordRewriteFallback("error")
            log.warn(
                "Query rewrite 失败，使用原问题继续检索: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            return question
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private fun formatHistoryForRewrite(history: List<Message>?): String {
        if (history.isNullOrEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (msg in history) {
            if (msg is UserMessage) {
                sb.append("用户: ").append(msg.text ?: "").append("\n")
            } else if (msg is AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                var text = msg.text ?: ""
                if (text.length > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "..."
                }
                sb.append("助手: ").append(text).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun hasEffectiveHit(docs: List<Document>?): Boolean {
        return !docs.isNullOrEmpty()
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
        return text.contains("没有找到相关信息") ||
            text.contains("未检索到相关信息") ||
            text.contains("信息不足") ||
            text.contains("超出知识库范围") ||
            text.contains("无法根据提供内容回答")
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private fun normalizeStreamOutput(rawFlux: Flux<String>): Flux<String> {
        return Flux.create<String> { sink ->
            val probeBuffer = StringBuilder()
            val passthrough = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val disposableRef = arrayOfNulls<Disposable>(1)

            val onNext = Consumer<String> { chunk ->
                if (!completed.get() && !sink.isCancelled) {
                    if (passthrough.get()) {
                        sink.next(chunk)
                    } else {
                        probeBuffer.append(chunk)
                        val probeText = probeBuffer.toString()
                        if (isNoResultLike(probeText)) {
                            completed.set(true)
                            sink.next(NO_RESULT_RESPONSE)
                            sink.complete()
                            disposableRef[0]?.dispose()
                        } else if (probeBuffer.length >= STREAM_PROBE_CHARS) {
                            passthrough.set(true)
                            sink.next(probeText)
                            probeBuffer.setLength(0)
                        }
                    }
                }
            }
            val onError = Consumer<Throwable> { error -> sink.error(error) }
            val onComplete = Runnable {
                if (!completed.get() && !sink.isCancelled) {
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()))
                    }
                    sink.complete()
                }
            }

            disposableRef[0] = rawFlux.subscribe(onNext, onError, onComplete)

            sink.onCancel(Disposable { disposableRef[0]?.dispose() })
        }
    }

    private data class SearchParams(
        val topK: Int, // Top K
        val minScore: Double // 相似度阈值
    )

    private data class QueryContext(
        val originalQuestion: String, // 原始问题
        val candidateQueries: List<String>, // 候选 Query
        val searchParams: SearchParams, // 检索参数
        val rewriteDurationMs: Long // 改写耗时
    )

    companion object {
        private const val NO_RESULT_RESPONSE: String =
            "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。"
        private const val STREAM_PROBE_CHARS: Int = 120
        private const val MAX_REWRITE_HISTORY_CHAR: Int = 200
    }
}

package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.util.stripCodeFences
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.document.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Service
class KnowledgeBaseQueryService(
    chatClientBuilder: ChatClient.Builder, // ChatClient 构建器
    private val vectorService: KnowledgeBaseVectorService, // 向量检索服务
    private val listService: KnowledgeBaseListService, // 知识库列表服务
    private val countService: KnowledgeBaseCountService, // 计数服务
    @Value("classpath:prompts/knowledgebase-query-system.st") systemPromptResource: Resource, // 系统提示词
    @Value("classpath:prompts/knowledgebase-query-user.st") userPromptResource: Resource, // 用户提示词
    @Value("classpath:prompts/knowledgebase-query-rewrite.st") rewritePromptResource: Resource, // 重写提示词
    @Value("\${app.ai.rag.rewrite.enabled:true}") private val rewriteEnabled: Boolean, // 是否启用重写
    @Value("\${app.ai.rag.search.short-query-length:4}") private val shortQueryLength: Int, // 短查询阈值
    @Value("\${app.ai.rag.search.topk-short:20}") private val topkShort: Int, // 短查询 topK
    @Value("\${app.ai.rag.search.topk-medium:12}") private val topkMedium: Int, // 中查询 topK
    @Value("\${app.ai.rag.search.topk-long:8}") private val topkLong: Int, // 长查询 topK
    @Value("\${app.ai.rag.search.min-score-short:0.18}") private val minScoreShort: Double, // 短查询最小分
    @Value("\${app.ai.rag.search.min-score-default:0.28}") private val minScoreDefault: Double // 默认最小分
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseQueryService::class.java)

    private val chatClient: ChatClient = chatClientBuilder.build() // AI 客户端
    private val systemPromptTemplate = PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 系统模板
    private val userPromptTemplate = PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 用户模板
    private val rewritePromptTemplate = PromptTemplate(rewritePromptResource.getContentAsString(StandardCharsets.UTF_8)) // 重写模板

    companion object {
        private const val NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。"
        private val SHORT_TOKEN_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]{2,20}$")
        private const val STREAM_PROBE_CHARS = 120
    }

    /**
     * 基于单个知识库回答用户问题
     */
    fun answerQuestion(knowledgeBaseId: Long, question: String): String {
        return answerQuestion(listOf(knowledgeBaseId), question)
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     */
    fun answerQuestion(knowledgeBaseIds: List<Long>?, question: String): String {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question)
        if (knowledgeBaseIds.isNullOrEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE
        }

        countService.updateQuestionCounts(knowledgeBaseIds)

        val queryContext = buildQueryContext(question)
        val relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds)

        if (!hasEffectiveHit(question, relevantDocs)) {
            return NO_RESULT_RESPONSE
        }

        val context = relevantDocs.mapNotNull { it.text }.joinToString("\n\n---\n\n")
        log.debug("检索到 {} 个相关文档片段", relevantDocs.size)

        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserPrompt(context, question)

        try {
            var answer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()
            answer = normalizeAnswer(answer)
            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds)
            return answer
        } catch (e: Exception) {
            log.error("知识库问答失败: {}", e.message, e)
            throw BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：${e.message}")
        }
    }

    /**
     * 查询知识库并返回完整响应
     */
    fun queryKnowledgeBase(request: QueryRequest): QueryResponseVo {
        val answer = answerQuestion(request.knowledgeBaseIds, request.question)
        val kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds)
        val kbNamesStr = kbNames.joinToString("、")
        val primaryKbId = request.knowledgeBaseIds.firstOrNull()
        return QueryResponseVo(answer, primaryKbId, kbNamesStr)
    }

    /**
     * 流式查询知识库（SSE）
     */
    fun answerQuestionStream(knowledgeBaseIds: List<Long>?, question: String): Flux<String> {
        log.info("收到知识库流式提问: kbIds={}, question={}", knowledgeBaseIds, question)
        if (knowledgeBaseIds.isNullOrEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE)
        }

        return try {
            countService.updateQuestionCounts(knowledgeBaseIds)

            val queryContext = buildQueryContext(question)
            val relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds)

            if (!hasEffectiveHit(question, relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE)
            }

            val context = relevantDocs.mapNotNull { it.text }.joinToString("\n\n---\n\n")
            log.debug("检索到 {} 个相关文档片段", relevantDocs.size)

            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(context, question)

            val responseFlux = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds)
            normalizeStreamOutput(responseFlux)
                .doOnComplete { log.info("流式输出完成: kbIds={}", knowledgeBaseIds) }
                .onErrorResume { e ->
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.message, e)
                    Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。")
                }
        } catch (e: Exception) {
            log.error("知识库流式问答失败: {}", e.message, e)
            Flux.just("【错误】知识库查询失败：${e.message}")
        }
    }

    private fun buildSystemPrompt(): String {
        return systemPromptTemplate.render()
    }

    private fun buildUserPrompt(context: String, question: String): String {
        val variables = mapOf(
            "context" to context,
            "question" to question
        )
        return userPromptTemplate.render(variables)
    }

    private fun buildQueryContext(originalQuestion: String): QueryContext {
        val normalizedQuestion = normalizeQuestion(originalQuestion)
        val rewrittenQuestion = rewriteQuestion(normalizedQuestion)
        val candidates = linkedSetOf(rewrittenQuestion, normalizedQuestion).filter { it.isNotBlank() }
        val searchParams = resolveSearchParams(normalizedQuestion)
        return QueryContext(normalizedQuestion, candidates.toList(), searchParams)
    }

    private fun normalizeQuestion(question: String?): String {
        return question?.trim() ?: ""
    }

    private fun retrieveRelevantDocs(queryContext: QueryContext, knowledgeBaseIds: List<Long>): List<Document> {
        for (candidateQuery in queryContext.candidateQueries) {
            if (candidateQuery.isBlank()) {
                continue
            }
            val docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams.topK,
                queryContext.searchParams.minScore
            )
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size)
            if (hasEffectiveHit(candidateQuery, docs)) {
                return docs
            }
        }
        return emptyList()
    }

    private fun resolveSearchParams(question: String): SearchParams {
        val compactLength = question.replace(Regex("\\s+"), "").length
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
            val variables = mapOf("question" to question)
            val rewritePrompt = rewritePromptTemplate.render(variables)
            val rewritten = chatClient.prompt()
                .user(rewritePrompt)
                .call()
                .content()
            val cleaned = rewritten?.stripCodeFences()?.trim().orEmpty()
            if (cleaned.isBlank()) question else cleaned
        } catch (e: Exception) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.message)
            question
        }
    }

    private fun hasEffectiveHit(question: String, docs: List<Document>): Boolean {
        if (docs.isEmpty()) {
            return false
        }
        val normalized = normalizeQuestion(question)
        if (!isShortTokenQuery(normalized)) {
            return true
        }
        val loweredToken = normalized.lowercase()
        for (doc in docs) {
            val text = doc.text
            if (text != null && text.lowercase().contains(loweredToken)) {
                return true
            }
        }
        log.info("短 query 命中确认失败，视为无有效结果: question='{}', docs={}", normalized, docs.size)
        return false
    }

    private fun isShortTokenQuery(question: String?): Boolean {
        if (question == null) {
            return false
        }
        return SHORT_TOKEN_PATTERN.matcher(question.trim()).matches()
    }

    private fun normalizeAnswer(answer: String?): String {
        if (answer.isNullOrBlank()) {
            return NO_RESULT_RESPONSE
        }
        val normalized = answer.stripCodeFences().trim()
        return if (isNoResultLike(normalized)) NO_RESULT_RESPONSE else normalized
    }

    private fun isNoResultLike(text: String): Boolean {
        return text.contains("没有找到相关信息") ||
            text.contains("未检索到相关信息") ||
            text.contains("信息不足") ||
            text.contains("超出知识库范围") ||
            text.contains("无法根据提供内容回答")
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板
     */
    private fun normalizeStreamOutput(rawFlux: Flux<String>): Flux<String> {
        return Flux.create { sink ->
            val probeBuffer = StringBuilder()
            val passthrough = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val disposableRef = arrayOfNulls<Disposable>(1)

            disposableRef[0] = rawFlux.subscribe(
                { chunk ->
                    if (completed.get() || sink.isCancelled) {
                        return@subscribe
                    }
                    if (passthrough.get()) {
                        sink.next(chunk)
                        return@subscribe
                    }

                    probeBuffer.append(chunk)
                    val probeText = probeBuffer.toString()
                    if (isNoResultLike(probeText)) {
                        completed.set(true)
                        sink.next(NO_RESULT_RESPONSE)
                        sink.complete()
                        disposableRef[0]?.dispose()
                        return@subscribe
                    }

                    if (probeBuffer.length >= STREAM_PROBE_CHARS) {
                        passthrough.set(true)
                        sink.next(probeText)
                        probeBuffer.setLength(0)
                    }
                },
                { error -> sink.error(error) },
                {
                    if (completed.get() || sink.isCancelled) {
                        return@subscribe
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()))
                    }
                    sink.complete()
                }
            )

            sink.onCancel { disposableRef[0]?.dispose() }
        }
    }

    private data class SearchParams(val topK: Int, val minScore: Double)

    private data class QueryContext(
        val originalQuestion: String,
        val candidateQueries: List<String>,
        val searchParams: SearchParams
    )
}

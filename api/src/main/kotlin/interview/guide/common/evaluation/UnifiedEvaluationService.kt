package interview.guide.common.evaluation

import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.evaluation.EvaluationReport.CategoryScore
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet

/**
 * 统一面试评估服务
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底
 */
@Service
class UnifiedEvaluationService(
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用封装
    private val resourceLoader: ResourceLoader, // classpath 资源加载器
    evaluationProperties: InterviewEvaluationProperties // 评估配置
) {

    companion object {
        private val log = LoggerFactory.getLogger(UnifiedEvaluationService::class.java)
        private const val MAX_REFERENCE_CONTEXT_CHARS: Int = 6000 // 参考基线最大长度
    }

    private val systemPromptTemplate: PromptTemplate =
        PromptTemplate(loadPrompt(evaluationProperties.systemPromptPath)) // 批次评估系统提示词
    private val userPromptTemplate: PromptTemplate =
        PromptTemplate(loadPrompt(evaluationProperties.userPromptPath)) // 批次评估用户提示词
    private val outputConverter: BeanOutputConverter<BatchReportDTO> =
        BeanOutputConverter(BatchReportDTO::class.java) // 批次评估输出转换器
    private val summarySystemPromptTemplate: PromptTemplate =
        PromptTemplate(loadPrompt(evaluationProperties.summarySystemPromptPath)) // 汇总系统提示词
    private val summaryUserPromptTemplate: PromptTemplate =
        PromptTemplate(loadPrompt(evaluationProperties.summaryUserPromptPath)) // 汇总用户提示词
    private val summaryOutputConverter: BeanOutputConverter<SummaryDTO> =
        BeanOutputConverter(SummaryDTO::class.java) // 汇总输出转换器
    private val evaluationBatchSize: Int = maxOf(1, evaluationProperties.batchSize) // 批次大小
    private val fallbackSplitEnabled: Boolean = evaluationProperties.fallbackSplitEnabled // 二分恢复开关
    private val fallbackMaxExtraCalls: Int = maxOf(0, evaluationProperties.fallbackMaxExtraCalls) // 额外调用预算
    private val fallbackMinGroups: Int = maxOf(2, evaluationProperties.fallbackMinGroups) // 最小组数

    /**
     * 批次评估结果
     */
    private data class BatchReportDTO(
        val overallScore: Int = 0, // 批次总分
        val overallFeedback: String? = null, // 批次总体评价
        val strengths: List<String>? = null, // 优势
        val improvements: List<String>? = null, // 改进建议
        val questionEvaluations: List<QuestionEvalDTO>? = null // 每题评估
    )

    /**
     * 单题评估结果
     */
    private data class QuestionEvalDTO(
        val questionIndex: Int = 0, // 题目索引
        val score: Int = 0, // 得分
        val feedback: String? = null, // 反馈
        val referenceAnswer: String? = null, // 参考答案
        val keyPoints: List<String>? = null // 评分要点
    )

    /**
     * 批次结果（记录该批次的题目索引与模型返回）
     */
    private data class BatchResult(
        val questionIndexes: List<Int>, // 批次题目索引
        val report: BatchReportDTO? // 模型返回（失败为 null）
    )

    /**
     * 问答组：主问题与其追问构成的不可拆分单元
     */
    private data class QaGroup(
        val records: MutableList<QaRecord> // 组内问答记录
    )

    /**
     * 二次汇总结果
     */
    private data class SummaryDTO(
        val overallFeedback: String? = null, // 总体评价
        val strengths: List<String>? = null, // 优势
        val improvements: List<String>? = null // 改进建议
    )

    /**
     * 加载 classpath 提示词
     */
    private fun loadPrompt(path: String): String {
        val resource = resourceLoader.getResource(path)
        return resource.getContentAsString(StandardCharsets.UTF_8)
    }

    /**
     * 评估面试问答（文字和语音通用）
     *
     * @param chatClient  LLM 客户端
     * @param sessionId   会话ID（用于日志）
     * @param qaRecords   问答记录列表
     * @param resumeText  简历摘要（可选，可为 null）
     * @return 评估报告
     */
    fun evaluate(
        chatClient: ChatClient,
        sessionId: String,
        qaRecords: List<QaRecord>,
        resumeText: String?
    ): EvaluationReport {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null)
    }

    /**
     * 评估面试问答（带参考基线）
     */
    fun evaluate(
        chatClient: ChatClient,
        sessionId: String,
        qaRecords: List<QaRecord>,
        resumeText: String?,
        referenceContext: String?
    ): EvaluationReport {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size)

        var resumeContext = resumeText ?: ""
        // 超长简历截断，保留前 3000 字符（约 1500~2000 tokens），避免极端情况下 token 消耗过大
        if (resumeContext.length > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)"
        }
        var referenceBaseline = referenceContext?.trim() ?: ""
        if (referenceBaseline.length > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS) +
                "\n...(参考基线过长，已截断)"
        }

        // 分批评估
        val batchResults = evaluateInBatches(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        )

        // 合并批次结果
        val mergedEvaluations = mergeQuestionEvaluations(batchResults)
        val fallbackFeedback = mergeOverallFeedback(batchResults)
        val fallbackStrengths = mergeListItems(batchResults, true)
        val fallbackImprovements = mergeListItems(batchResults, false)

        // 二次汇总
        val summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        )

        return buildReport(
            sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback, summary.strengths, summary.improvements
        )
    }

    /**
     * 分批评估：每批失败时按开关执行二分恢复
     */
    private fun evaluateInBatches(
        chatClient: ChatClient,
        sessionId: String,
        resumeContext: String,
        qaRecords: List<QaRecord>,
        referenceContext: String
    ): List<BatchResult> {
        val results = ArrayList<BatchResult>()
        val extraBudget = intArrayOf(fallbackMaxExtraCalls)
        for (batchGroups in packBatches(buildGroups(qaRecords))) {
            val flattened = batchGroups.flatMap { it.records }
            var report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, flattened)
            if (report == null && fallbackSplitEnabled) {
                report = recoverBatch(
                    chatClient, sessionId, resumeContext, referenceContext,
                    batchGroups, extraBudget, 0
                )
            }
            results.add(BatchResult(flattened.map { it.questionIndex }, report))
        }
        return results
    }

    /**
     * 批次失败后的按组二分恢复。不会对已失败的组集合重复调用：
     * 可拆（组数 >= fallbackMinGroups）时立即二分，左右各自先 evaluate（耗预算）再对失败侧递归；
     * 不可拆时耗 1 次预算整段加试一次，仍失败则整段降级。追问组是不可拆分单元，永不逐题拆开。
     */
    private fun recoverBatch(
        chatClient: ChatClient,
        sessionId: String,
        resumeContext: String,
        referenceContext: String,
        groups: List<QaGroup>,
        extraBudget: IntArray,
        depth: Int
    ): BatchReportDTO {
        val indexes = groups.flatMap { g -> g.records }.map { it.questionIndex }
        if (extraBudget[0] <= 0) {
            log.warn(
                "评估批次恢复预算耗尽，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size, indexes.size, depth, indexes
            )
            return degradedReport(indexes)
        }
        if (groups.size >= fallbackMinGroups) {
            val mid = groups.size / 2
            val halves = listOf(groups.subList(0, mid), groups.subList(mid, groups.size))
            // 先评价兄弟半批（健康侧先拿到预算），再对失败侧递归下钻
            val halfReports = arrayOfNulls<BatchReportDTO>(halves.size)
            val halfFailed = BooleanArray(halves.size)
            for (i in halves.indices) {
                val flattened = halves[i].flatMap { g -> g.records }
                if (extraBudget[0] <= 0) {
                    halfReports[i] = degradedReport(flattened.map { it.questionIndex })
                    continue
                }
                extraBudget[0] = extraBudget[0] - 1
                halfReports[i] = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, flattened)
                halfFailed[i] = halfReports[i] == null
            }
            val merged = ArrayList<QuestionEvalDTO>()
            for (i in halves.indices) {
                val flattened = halves[i].flatMap { g -> g.records }
                if (halfFailed[i]) {
                    halfReports[i] = recoverBatch(
                        chatClient, sessionId, resumeContext, referenceContext,
                        halves[i], extraBudget, depth + 1
                    )
                }
                val halfReport = halfReports[i]
                merged.addAll(
                    halfReport?.questionEvaluations
                        ?: degradedReport(flattened.map { it.questionIndex }).questionEvaluations
                        ?: emptyList()
                )
            }
            return BatchReportDTO(0, "", emptyList(), emptyList(), merged)
        }
        // 不可再拆：整段加试一次（耗预算），仍失败则该段降级
        val flattened = groups.flatMap { g -> g.records }
        extraBudget[0] = extraBudget[0] - 1
        val retried = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, flattened)
        if (retried == null) {
            log.warn(
                "评估批次恢复最终失败，降级: sessionId={}, groups={}, size={}, depth={}, failedIndexes={}",
                sessionId, groups.size, indexes.size, depth, indexes
            )
            return degradedReport(indexes)
        }
        return retried
    }

    /**
     * 显式降级结果：0 分 + 「模型评估失败后的系统降级」反馈。
     * 与 merge 阶段缺索引的默认兜底句区分，不伪装成真实评分。
     */
    private fun degradedReport(indexes: List<Int>): BatchReportDTO {
        val evaluations = indexes.map { index ->
            QuestionEvalDTO(
                index, 0,
                "模型评估失败后的系统降级，该分数不代表真实表现。", "", emptyList()
            )
        }
        return BatchReportDTO(0, "模型评估失败后的系统降级。", emptyList(), emptyList(), evaluations)
    }

    /**
     * 按输入顺序建立问答组：主问题为组 Key，合法追问挂到父问题所在组；
     * 父问题不存在、父索引指向未来题目等异常关系降级为独立组并告警
     */
    private fun buildGroups(qaRecords: List<QaRecord>): List<QaGroup> {
        val knownIndexes = qaRecords.map { it.questionIndex }.toSet()
        val groupByIndex = HashMap<Int, QaGroup>()
        val orderedGroups = ArrayList<QaGroup>()
        for (q in qaRecords) {
            var target: QaGroup? = null
            if (q.followUp && q.parentQuestionIndex != null) {
                val parent = q.parentQuestionIndex
                if (parent != null && knownIndexes.contains(parent) && parent < q.questionIndex) {
                    target = groupByIndex[parent]
                } else {
                    log.warn(
                        "追问父索引异常，降级为独立组: questionIndex={}, parentQuestionIndex={}",
                        q.questionIndex, parent
                    )
                }
            }
            if (target == null) {
                target = QaGroup(ArrayList())
                orderedGroups.add(target)
            }
            target.records.add(q)
            groupByIndex[q.questionIndex] = target
        }
        return orderedGroups
    }

    /**
     * 以组为不可拆分单元装批（保留组边界供失败恢复二分）；
     * 加入下一组会超过批次大小时先提交当前批次；
     * 单个组超过批次大小时允许其独占一个超限批次，优先保证上下文完整
     */
    private fun packBatches(groups: List<QaGroup>): List<List<QaGroup>> {
        val batches = ArrayList<List<QaGroup>>()
        var current = ArrayList<QaGroup>()
        var currentSize = 0
        for (group in groups) {
            if (currentSize > 0 && currentSize + group.records.size > evaluationBatchSize) {
                batches.add(current)
                current = ArrayList()
                currentSize = 0
            }
            current.add(group)
            currentSize += group.records.size
        }
        if (current.isNotEmpty()) {
            batches.add(current)
        }
        return batches
    }

    /**
     * 单批评估（失败返回 null，由上层恢复或降级）
     */
    private fun evaluateBatch(
        chatClient: ChatClient,
        sessionId: String,
        resumeContext: String,
        referenceContext: String,
        batch: List<QaRecord>
    ): BatchReportDTO? {
        val qaRecords = buildQARecords(batch)
        val systemPrompt = systemPromptTemplate.render()

        val variables = HashMap<String, Any>()
        variables["resumeText"] = resumeContext
        variables["qaRecords"] = qaRecords
        variables["referenceContext"] = if (!referenceContext.isNullOrBlank()) referenceContext else "无"
        val userPrompt = userPromptTemplate.render(variables)

        val systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.format
        try {
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            )
        } catch (e: Exception) {
            log.error(
                "批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            // 返回空报告，让合并逻辑用零分兜底
            return null
        }
    }

    /**
     * 构建批次问答文本
     */
    private fun buildQARecords(batch: List<QaRecord>): String {
        val sb = StringBuilder()
        for (q in batch) {
            val relation = if (q.followUp && q.parentQuestionIndex != null) {
                "（追问，针对 questionIndex=${q.parentQuestionIndex}）"
            } else {
                ""
            }
            sb.append(
                "问题 questionIndex=${q.questionIndex} [${q.category}]$relation: ${q.question}\n"
            )
            sb.append("回答: ${q.userAnswer ?: "(未回答)"}\n\n")
        }
        return sb.toString()
    }

    /**
     * 按模型返回的 questionIndex 显式映射回原始题目：
     * - 索引全部合法：按索引写回对应位置
     * - 返回数量与输入一致且索引完全不可用（无任何合法索引，或整批呈现一致的 1-based 偏移）：
     *   对整批按位置对齐
     * - 其余情况：合法索引按索引写回，非法或缺失的索引只降级该位置为 0 分，
     *   不做半索引半位置的混用（避免同一份评估被重复消费或错位）
     */
    private fun mergeQuestionEvaluations(batchResults: List<BatchResult>): List<QuestionEvalDTO> {
        val merged = ArrayList<QuestionEvalDTO>()
        for (result in batchResults) {
            val expectedIndexes = result.questionIndexes
            val current = result.report?.questionEvaluations ?: emptyList()

            val byIndex = HashMap<Int, QuestionEvalDTO>()
            val returnedIndexes = LinkedHashSet<Int>()
            for (dto in current) {
                returnedIndexes.add(dto.questionIndex)
                if (expectedIndexes.contains(dto.questionIndex)) {
                    byIndex.putIfAbsent(dto.questionIndex, dto)
                }
            }
            val countsMatch = current.size == expectedIndexes.size
            val allMapped = byIndex.size == expectedIndexes.size
            val oneBasedShift = !allMapped && countsMatch &&
                isConsistentOneBasedShift(returnedIndexes, expectedIndexes)
            val noUsableIndex = byIndex.isEmpty() && countsMatch
            val positionalFallback = oneBasedShift || noUsableIndex

            for (i in expectedIndexes.indices) {
                val originalIndex = expectedIndexes[i]
                var dto = if (positionalFallback) current.getOrNull(i) else byIndex[originalIndex]
                if (dto != null && dto.questionIndex != originalIndex) {
                    dto = QuestionEvalDTO(
                        originalIndex, dto.score, dto.feedback,
                        dto.referenceAnswer, dto.keyPoints
                    )
                }
                merged.add(
                    dto ?: QuestionEvalDTO(
                        originalIndex, 0, "该题未成功生成评估结果，系统按 0 分处理。", "", emptyList()
                    )
                )
            }
        }
        return merged
    }

    /**
     * 判断返回的索引是否为整批一致的 1-based 重编号（每个原始索引 +1，且数量一致）
     */
    private fun isConsistentOneBasedShift(
        returnedIndexes: Set<Int>,
        expectedIndexes: List<Int>
    ): Boolean {
        val shifted = expectedIndexes.map { it + 1 }.toSet()
        return returnedIndexes == shifted
    }

    /**
     * 合并各批次的总体评价
     */
    private fun mergeOverallFeedback(batchResults: List<BatchResult>): String {
        val feedback = batchResults
            .mapNotNull { it.report }
            .filter { !it.overallFeedback.isNullOrBlank() }
            .mapNotNull { it.overallFeedback }
            .joinToString("\n\n")
        return if (feedback.isBlank()) "本次面试已完成分批评估，但未生成有效综合评语。" else feedback
    }

    /**
     * 合并各批次的优势/改进项（去重并限制数量）
     */
    private fun mergeListItems(batchResults: List<BatchResult>, strengthsMode: Boolean): List<String> {
        val merged = LinkedHashSet<String>()
        for (result in batchResults) {
            val report = result.report ?: continue
            val items = if (strengthsMode) report.strengths else report.improvements
            if (items == null) continue
            items
                .filter { !it.isNullOrBlank() }
                .map { it.trim() }
                .forEach { merged.add(it) }
        }
        return merged.take(8)
    }

    /**
     * 二次汇总：模型总结失败时降级为批次聚合结果
     */
    private fun summarizeBatchResults(
        chatClient: ChatClient,
        sessionId: String,
        resumeContext: String,
        referenceContext: String,
        qaRecords: List<QaRecord>,
        evaluations: List<QuestionEvalDTO>,
        fallbackFeedback: String,
        fallbackStrengths: List<String>,
        fallbackImprovements: List<String>
    ): SummaryDTO {
        try {
            val summarySystem = summarySystemPromptTemplate.render()
            val vars = HashMap<String, Any>()
            vars["resumeText"] = resumeContext
            vars["referenceContext"] = if (!referenceContext.isNullOrBlank()) referenceContext else "无"
            vars["categorySummary"] = buildCategorySummary(qaRecords, evaluations)
            vars["questionHighlights"] = buildQuestionHighlights(qaRecords, evaluations)
            vars["fallbackOverallFeedback"] = fallbackFeedback
            vars["fallbackStrengths"] = fallbackStrengths.joinToString("\n")
            vars["fallbackImprovements"] = fallbackImprovements.joinToString("\n")
            val summaryUser = summaryUserPromptTemplate.render(vars)

            val systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.format
            val dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            )

            val feedback = if (!dto.overallFeedback.isNullOrBlank()) dto.overallFeedback else fallbackFeedback
            val strengths = sanitizeItems(dto.strengths, fallbackStrengths)
            val improvements = sanitizeItems(dto.improvements, fallbackImprovements)
            return SummaryDTO(feedback, strengths, improvements)
        } catch (e: Exception) {
            log.warn(
                "二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}",
                sessionId, ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            return SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements)
        }
    }

    /**
     * 清洗汇总项：优先使用模型结果，空则回退批次聚合结果
     */
    private fun sanitizeItems(primary: List<String>?, fallback: List<String>): List<String> {
        val source: List<String> = primary?.takeIf { it.isNotEmpty() } ?: fallback
        if (source.isEmpty()) return emptyList()
        return source
            .filter { !it.isNullOrBlank() }
            .map { it.trim() }
            .distinct()
            .take(8)
    }

    /**
     * 汇总评估结果，生成最终报告
     */
    private fun buildReport(
        sessionId: String,
        qaRecords: List<QaRecord>,
        evaluations: List<QuestionEvalDTO>?,
        overallFeedback: String?,
        strengths: List<String>?,
        improvements: List<String>?
    ): EvaluationReport {
        val questionDetails = ArrayList<QuestionEvaluation>()
        val referenceAnswers = ArrayList<ReferenceAnswer>()
        val categoryScoresMap = HashMap<String?, MutableList<Int>>()

        val answeredCount = qaRecords.count { !it.userAnswer.isNullOrBlank() }

        val evalSize = evaluations?.size ?: 0

        for (i in qaRecords.indices) {
            val q = qaRecords[i]
            val eval = if (i < evalSize) evaluations?.get(i) else null

            val hasAnswer = !q.userAnswer.isNullOrBlank()
            val score = if (hasAnswer && eval != null) eval.score else 0
            val feedback = eval?.feedback ?: "该题未成功生成评估反馈。"
            val refAnswer = eval?.referenceAnswer ?: ""
            val keyPoints = eval?.keyPoints ?: emptyList()

            questionDetails.add(
                QuestionEvaluation(
                    q.questionIndex, q.question, q.category, q.userAnswer, score, feedback
                )
            )
            referenceAnswers.add(
                ReferenceAnswer(q.questionIndex, q.question, refAnswer, keyPoints)
            )
            categoryScoresMap.getOrPut(q.category) { ArrayList() }.add(score)
        }

        val categoryScores = categoryScoresMap.map { (category, scores) ->
            CategoryScore(
                category,
                averageToInt(scores),
                scores.size
            )
        }

        val overallScore = if (answeredCount == 0) {
            0
        } else {
            averageToInt(questionDetails.map { it.score })
        }

        return EvaluationReport(
            sessionId, qaRecords.size, overallScore, categoryScores, questionDetails,
            overallFeedback ?: "",
            strengths ?: emptyList(),
            improvements ?: emptyList(),
            referenceAnswers
        )
    }

    /**
     * 构建分类得分摘要（供二次汇总提示词使用）
     */
    private fun buildCategorySummary(
        qaRecords: List<QaRecord>,
        evaluations: List<QuestionEvalDTO>
    ): String {
        val categoryScores = HashMap<String?, MutableList<Int>>()
        for (i in qaRecords.indices) {
            val q = qaRecords[i]
            val eval = if (i < evaluations.size) evaluations[i] else null
            var score = 0
            if (eval != null && !q.userAnswer.isNullOrBlank()) {
                score = eval.score
            }
            categoryScores.getOrPut(q.category) { ArrayList() }.add(score)
        }
        return categoryScores.map { (category, scores) ->
            val avg = averageToInt(scores)
            String.format("- %s: 平均分 %d, 题数 %d", category, avg, scores.size)
        }.sorted().joinToString("\n")
    }

    /**
     * 构建题目亮点摘要（供二次汇总提示词使用）
     */
    private fun buildQuestionHighlights(
        qaRecords: List<QaRecord>,
        evaluations: List<QuestionEvalDTO>
    ): String {
        val highlights = ArrayList<String>()
        for (i in qaRecords.indices) {
            val q = qaRecords[i]
            val eval = if (i < evaluations.size) evaluations[i] else null
            val score = eval?.score ?: 0
            val feedback = eval?.feedback ?: ""
            val shortQ = if (q.question.length > 50) q.question.substring(0, 50) + "..." else q.question
            val shortF = if (feedback.length > 80) feedback.substring(0, 80) + "..." else feedback
            highlights.add(
                String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex + 1, shortQ, score, shortF)
            )
        }
        return highlights.take(20).joinToString("\n")
    }

    /**
     * 平均分向下取整（空列表返回 0，与 Java 的 orElse(0) 一致）
     */
    private fun averageToInt(scores: List<Int>): Int {
        return if (scores.isEmpty()) 0 else scores.average().toInt()
    }
}

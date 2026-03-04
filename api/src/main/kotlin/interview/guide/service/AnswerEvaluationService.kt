package interview.guide.service

import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * 答案评估服务
 * 评估用户回答并生成面试报告
 */
@Service
class AnswerEvaluationService(
    chatClientBuilder: ChatClient.Builder, // ChatClient 构建器
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用器
    @Value("classpath:prompts/interview-evaluation-system.st") systemPromptResource: Resource, // 评估系统提示词
    @Value("classpath:prompts/interview-evaluation-user.st") userPromptResource: Resource, // 评估用户提示词
    @Value("classpath:prompts/interview-evaluation-summary-system.st") summarySystemPromptResource: Resource, // 汇总系统提示词
    @Value("classpath:prompts/interview-evaluation-summary-user.st") summaryUserPromptResource: Resource, // 汇总用户提示词
    @Value("\${app.interview.evaluation.batch-size:8}") evaluationBatchSize: Int // 批次大小
) {

    private val log = LoggerFactory.getLogger(AnswerEvaluationService::class.java)

    private val chatClient: ChatClient = chatClientBuilder.build() // AI 客户端
    private val systemPromptTemplate = PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 系统模板
    private val userPromptTemplate = PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 用户模板
    private val outputConverter = BeanOutputConverter(EvaluationReportDto::class.java) // 输出转换器
    private val summarySystemPromptTemplate = PromptTemplate(summarySystemPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 汇总系统模板
    private val summaryUserPromptTemplate = PromptTemplate(summaryUserPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 汇总用户模板
    private val summaryOutputConverter = BeanOutputConverter(FinalSummaryDto::class.java) // 汇总输出转换器
    private val evaluationBatchSize = maxOf(1, evaluationBatchSize) // 批次大小限制

    private data class EvaluationReportDto(
        val overallScore: Int, // 总分
        val overallFeedback: String?, // 总体评价
        val strengths: List<String>?, // 优势
        val improvements: List<String>?, // 改进建议
        val questionEvaluations: List<QuestionEvaluationDto>? // 问题评估
    )

    private data class QuestionEvaluationDto(
        val questionIndex: Int, // 问题索引
        val score: Int, // 得分
        val feedback: String?, // 反馈
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String>? // 要点
    )

    private data class BatchEvaluationResult(
        val startIndex: Int, // 批次起始索引
        val endIndex: Int, // 批次结束索引
        val report: EvaluationReportDto // 批次评估结果
    )

    private data class FinalSummaryDto(
        val overallFeedback: String?, // 总体评价
        val strengths: List<String>?, // 优势
        val improvements: List<String>? // 改进建议
    )

    /**
     * 评估完整面试并生成报告
     *
     * @param sessionId 会话ID // 面试会话ID
     * @param resumeText 简历文本 // 简历原文
     * @param questions 面试问题列表 // 问答内容
     * @return 面试报告 // 评估结果
     */
    fun evaluateInterview(sessionId: String, resumeText: String, questions: List<InterviewQuestionVo>): InterviewReportVo {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size)

        try {
            val resumeSummary = if (resumeText.length > 500) resumeText.substring(0, 500) + "..." else resumeText
            val batchResults = evaluateInBatches(sessionId, resumeSummary, questions)

            val mergedEvaluations = mergeQuestionEvaluations(batchResults, questions.size)
            val fallbackOverallFeedback = mergeOverallFeedback(batchResults)
            val fallbackStrengths = mergeListItems(batchResults, true)
            val fallbackImprovements = mergeListItems(batchResults, false)

            val finalSummary = summarizeBatchResults(
                sessionId,
                resumeSummary,
                questions,
                mergedEvaluations,
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            )

            return convertToReport(
                sessionId,
                mergedEvaluations,
                questions,
                finalSummary.overallFeedback ?: fallbackOverallFeedback,
                sanitizeSummaryItems(finalSummary.strengths, fallbackStrengths),
                sanitizeSummaryItems(finalSummary.improvements, fallbackImprovements)
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("面试评估失败: {}", e.message, e)
            throw BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败：${e.message}")
        }
    }

    /**
     * 构建问答记录字符串
     */
    private fun buildQaRecords(questions: List<InterviewQuestionVo>): String {
        val sb = StringBuilder()
        for (q in questions) {
            sb.append("问题${q.questionIndex + 1} [${q.category}]: ${q.question}\n")
            sb.append("回答: ${q.userAnswer ?: "(未回答)"}\n\n")
        }
        return sb.toString()
    }

    private fun evaluateInBatches(
        sessionId: String,
        resumeSummary: String,
        questions: List<InterviewQuestionVo>
    ): List<BatchEvaluationResult> {
        val results = mutableListOf<BatchEvaluationResult>()
        var start = 0
        while (start < questions.size) {
            val end = minOf(start + evaluationBatchSize, questions.size)
            val batchQuestions = questions.subList(start, end)
            val report = evaluateBatch(sessionId, resumeSummary, batchQuestions, start, end)
            results.add(BatchEvaluationResult(start, end, report))
            start = end
        }
        return results
    }

    private fun evaluateBatch(
        sessionId: String,
        resumeSummary: String,
        batchQuestions: List<InterviewQuestionVo>,
        start: Int,
        end: Int
    ): EvaluationReportDto {
        val qaRecords = buildQaRecords(batchQuestions)
        val systemPrompt = systemPromptTemplate.render()

        val variables = mapOf(
            "resumeText" to resumeSummary,
            "qaRecords" to qaRecords
        )
        val userPrompt = userPromptTemplate.render(variables)
        val systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.format

        try {
            val dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：",
                "批次评估",
                log
            )
            log.debug("批次评估完成: sessionId={}, range=[{}, {}), batchSize={}", sessionId, start, end, batchQuestions.size)
            return dto
        } catch (e: Exception) {
            log.error("批次评估失败: sessionId={}, range=[{}, {}), error={}", sessionId, start, end, e.message, e)
            throw BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败：${e.message}")
        }
    }

    private fun mergeQuestionEvaluations(batchResults: List<BatchEvaluationResult>, questionSize: Int): List<QuestionEvaluationDto> {
        val merged = mutableListOf<QuestionEvaluationDto>()
        for (result in batchResults) {
            val current = result.report.questionEvaluations ?: emptyList()
            val expectedSize = result.endIndex - result.startIndex
            for (i in 0 until expectedSize) {
                val evaluation = if (i < current.size) {
                    current[i]
                } else {
                    QuestionEvaluationDto(
                        questionIndex = result.startIndex + i,
                        score = 0,
                        feedback = "该题未成功生成评估结果，系统按 0 分处理。",
                        referenceAnswer = "",
                        keyPoints = emptyList()
                    )
                }
                merged.add(evaluation)
            }
        }
        if (merged.size < questionSize) {
            for (i in merged.size until questionSize) {
                merged.add(
                    QuestionEvaluationDto(
                        questionIndex = i,
                        score = 0,
                        feedback = "该题未成功生成评估结果，系统按 0 分处理。",
                        referenceAnswer = "",
                        keyPoints = emptyList()
                    )
                )
            }
        }
        return merged
    }

    private fun mergeOverallFeedback(batchResults: List<BatchEvaluationResult>): String {
        val feedback = batchResults
            .mapNotNull { it.report.overallFeedback?.takeIf { feedback -> feedback.isNotBlank() } }
            .joinToString("\n\n")
        return if (feedback.isNotBlank()) feedback else "本次面试已完成分批评估，但未生成有效综合评语。"
    }

    private fun mergeListItems(batchResults: List<BatchEvaluationResult>, strengthsMode: Boolean): List<String> {
        val merged = linkedSetOf<String>()
        for (result in batchResults) {
            val report = result.report
            val items = if (strengthsMode) report.strengths else report.improvements
            items?.filter { it.isNotBlank() }?.map { it.trim() }?.forEach { merged.add(it) }
        }
        return merged.take(8)
    }

    private fun summarizeBatchResults(
        sessionId: String,
        resumeSummary: String,
        questions: List<InterviewQuestionVo>,
        evaluations: List<QuestionEvaluationDto>,
        fallbackOverallFeedback: String,
        fallbackStrengths: List<String>,
        fallbackImprovements: List<String>
    ): FinalSummaryDto {
        return try {
            val summarySystemPrompt = summarySystemPromptTemplate.render()
            val variables = mapOf(
                "resumeText" to resumeSummary,
                "categorySummary" to buildCategorySummary(questions, evaluations),
                "questionHighlights" to buildQuestionHighlights(questions, evaluations),
                "fallbackOverallFeedback" to fallbackOverallFeedback,
                "fallbackStrengths" to fallbackStrengths.joinToString("\n"),
                "fallbackImprovements" to fallbackImprovements.joinToString("\n")
            )
            val summaryUserPrompt = summaryUserPromptTemplate.render(variables)
            val systemPromptWithFormat = summarySystemPrompt + "\n\n" + summaryOutputConverter.format

            val dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                summaryUserPrompt,
                summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试总结失败：",
                "总结评估",
                log
            )
            val overallFeedback = if (!dto.overallFeedback.isNullOrBlank()) {
                dto.overallFeedback
            } else {
                fallbackOverallFeedback
            }
            val strengths = sanitizeSummaryItems(dto.strengths, fallbackStrengths)
            val improvements = sanitizeSummaryItems(dto.improvements, fallbackImprovements)
            log.debug("二次汇总评估完成: sessionId={}", sessionId)
            FinalSummaryDto(overallFeedback, strengths, improvements)
        } catch (e: Exception) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.message)
            FinalSummaryDto(
                fallbackOverallFeedback,
                fallbackStrengths,
                fallbackImprovements
            )
        }
    }

    private fun sanitizeSummaryItems(primary: List<String>?, fallback: List<String>): List<String> {
        val source = if (!primary.isNullOrEmpty()) primary else fallback
        return source.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .take(8)
    }

    private fun buildCategorySummary(
        questions: List<InterviewQuestionVo>,
        evaluations: List<QuestionEvaluationDto>
    ): String {
        val categoryScores = linkedMapOf<String?, MutableList<Int>>()
        for (i in questions.indices) {
            val q = questions[i]
            val eval = if (i < evaluations.size) evaluations[i] else null
            val score = if (eval != null && !q.userAnswer.isNullOrBlank()) eval.score else 0
            categoryScores.computeIfAbsent(q.category) { mutableListOf() }.add(score)
        }

        return categoryScores.entries
            .map { (category, scores) ->
                val avg = scores.average().toInt()
                "- $category: 平均分 $avg, 题数 ${scores.size}"
            }
            .sorted()
            .joinToString("\n")
    }

    private fun buildQuestionHighlights(
        questions: List<InterviewQuestionVo>,
        evaluations: List<QuestionEvaluationDto>
    ): String {
        val highlights = mutableListOf<String>()
        for (i in questions.indices) {
            val q = questions[i]
            val eval = if (i < evaluations.size) evaluations[i] else null
            val score = eval?.score ?: 0
            val feedback = eval?.feedback ?: ""
            val questionText = q.question
            val shortQuestion = if (questionText.length > 50) questionText.substring(0, 50) + "..." else questionText
            val shortFeedback = if (feedback.length > 80) feedback.substring(0, 80) + "..." else feedback
            highlights.add("- Q${q.questionIndex + 1} | $shortQuestion | 分数:$score | 反馈:$shortFeedback")
        }
        return highlights.take(20).joinToString("\n")
    }

    /**
     * 转换 DTO 为业务对象
     */
    private fun convertToReport(
        sessionId: String,
        evaluations: List<QuestionEvaluationDto>,
        questions: List<InterviewQuestionVo>,
        overallFeedback: String,
        strengths: List<String>,
        improvements: List<String>
    ): InterviewReportVo {
        val questionDetails = mutableListOf<InterviewReportVo.QuestionEvaluationVo>()
        val referenceAnswers = mutableListOf<InterviewReportVo.ReferenceAnswerVo>()
        val categoryScoresMap = linkedMapOf<String?, MutableList<Int>>() // 分类可能为空，需保持与Java一致

        val answeredCount = questions.count { !it.userAnswer.isNullOrBlank() }

        for (i in questions.indices) {
            val eval = evaluations.getOrNull(i)
            val q = questions[i]
            val hasAnswer = !q.userAnswer.isNullOrBlank()
            val score = if (hasAnswer) eval?.score ?: 0 else 0

            questionDetails.add(
                InterviewReportVo.QuestionEvaluationVo(
                    q.questionIndex,
                    q.question,
                    q.category,
                    q.userAnswer,
                    score,
                    eval?.feedback ?: "该题未成功生成评估反馈。"
                )
            )

            referenceAnswers.add(
                InterviewReportVo.ReferenceAnswerVo(
                    q.questionIndex,
                    q.question,
                    eval?.referenceAnswer ?: "",
                    eval?.keyPoints
                )
            )

            categoryScoresMap.computeIfAbsent(q.category) { mutableListOf() }.add(score)
        }

        val categoryScores = categoryScoresMap.entries.map { (category, scores) ->
            InterviewReportVo.CategoryScoreVo(
                category,
                scores.average().toInt(),
                scores.size
            )
        }

        val overallScore = if (answeredCount == 0) {
            0
        } else {
            questionDetails.map { it.score }.average().toInt()
        }

        return InterviewReportVo(
            sessionId,
            questions.size,
            overallScore,
            categoryScores,
            questionDetails,
            overallFeedback,
            strengths,
            improvements,
            referenceAnswers
        )
    }
}

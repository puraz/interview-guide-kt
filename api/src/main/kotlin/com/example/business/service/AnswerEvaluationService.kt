package com.example.business.service

import com.example.framework.ai.chat.AiChatClient
import com.example.framework.ai.chat.AiChatMessage
import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatRole
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.example.framework.core.utils.stripCodeFences
import com.fasterxml.jackson.core.type.TypeReference
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

/**
 * 答案评估服务
 *
 * 负责调用AI生成面试评估报告。
 */
@Service
class AnswerEvaluationService(
    private val aiChatClient: AiChatClient,
    @Value("classpath:prompts/interview-evaluation-system.st")
    private val evaluationSystemPromptResource: Resource,
    @Value("classpath:prompts/interview-evaluation-user.st")
    private val evaluationUserPromptResource: Resource,
    @Value("classpath:prompts/interview-evaluation-summary-system.st")
    private val summarySystemPromptResource: Resource,
    @Value("classpath:prompts/interview-evaluation-summary-user.st")
    private val summaryUserPromptResource: Resource,
    @Value("\${app.interview.evaluation.batch-size:8}")
    private val evaluationBatchSize: Int
) {
    private val logger = LoggerFactory.getLogger(AnswerEvaluationService::class.java)
    private val evaluationSystemPrompt: String = readResource(evaluationSystemPromptResource)
    private val evaluationUserPrompt: String = readResource(evaluationUserPromptResource)
    private val summarySystemPrompt: String = readResource(summarySystemPromptResource)
    private val summaryUserPrompt: String = readResource(summaryUserPromptResource)
    private val safeBatchSize = evaluationBatchSize.coerceAtLeast(1)

    /**
     * 评估完整面试并生成报告
     *
     * @param sessionId 会话ID
     * @param resumeText 简历文本
     * @param questions 面试问题列表
     * @return 面试报告
     */
    fun evaluateInterview(
        sessionId: String,
        resumeText: String,
        questions: List<InterviewSessionService.InterviewQuestionVo>
    ): InterviewReportVo {
        if (questions.isEmpty()) {
            throw BusinessException("面试问题为空，无法评估")
        }

        val resumeSummary = if (resumeText.length > 500) {
            resumeText.substring(0, 500) + "..."
        } else {
            resumeText
        }

        val batchResults = evaluateInBatches(sessionId, resumeSummary, questions)
        val evaluationMap = mergeQuestionEvaluations(batchResults, questions)
        val questionDetails = buildQuestionDetails(questions, evaluationMap)
        val referenceAnswers = buildReferenceAnswers(questions, evaluationMap)
        val categoryScores = buildCategoryScores(questionDetails)
        val overallScore = calculateOverallScore(questionDetails)

        val fallbackOverallFeedback = batchResults.mapNotNull { it.report.overallFeedback }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val fallbackStrengths = mergeListItems(batchResults, true)
        val fallbackImprovements = mergeListItems(batchResults, false)

        val summary = summarizeResults(
            resumeSummary,
            categoryScores,
            questionDetails,
            fallbackOverallFeedback,
            fallbackStrengths,
            fallbackImprovements
        )

        return InterviewReportVo(
            sessionId = sessionId, // 会话ID
            totalQuestions = questions.size, // 总题数
            overallScore = overallScore, // 总分
            categoryScores = categoryScores, // 类别得分
            questionDetails = questionDetails, // 题目详情
            overallFeedback = summary.overallFeedback, // 综合评价
            strengths = summary.strengths, // 优势列表
            improvements = summary.improvements, // 改进建议
            referenceAnswers = referenceAnswers // 参考答案
        )
    }

    private fun evaluateInBatches(
        sessionId: String,
        resumeSummary: String,
        questions: List<InterviewSessionService.InterviewQuestionVo>
    ): List<BatchEvaluationResult> {
        val results = mutableListOf<BatchEvaluationResult>()
        var start = 0
        while (start < questions.size) {
            val end = minOf(start + safeBatchSize, questions.size)
            val batchQuestions = questions.subList(start, end)
            val report = evaluateBatch(sessionId, resumeSummary, batchQuestions)
            results.add(BatchEvaluationResult(startIndex = start, endIndex = end, report = report))
            start = end
        }
        return results
    }

    private fun evaluateBatch(
        sessionId: String,
        resumeSummary: String,
        batchQuestions: List<InterviewSessionService.InterviewQuestionVo>
    ): EvaluationReportDto {
        val qaRecords = buildQARecords(batchQuestions)
        val userPrompt = renderTemplate(
            evaluationUserPrompt,
            mapOf(
                "resumeText" to resumeSummary,
                "qaRecords" to qaRecords
            )
        )

        val request = AiChatRequest(
            messages = listOf(
                AiChatMessage(role = AiChatRole.SYSTEM, content = evaluationSystemPrompt), // 系统提示词
                AiChatMessage(role = AiChatRole.USER, content = userPrompt) // 用户提示词
            )
        )

        val result = aiChatClient.chat(request)
        if (!result.success || result.content.isNullOrBlank()) {
            throw BusinessException("面试评估失败：${result.errorMessage ?: "AI返回空内容"}")
        }

        val cleaned = result.content.stripCodeFences() // 去除代码块标记，避免JSON解析失败
        val dto = JSONUtil.parseObject(cleaned, object : TypeReference<EvaluationReportDto>() {})
            ?: throw BusinessException("面试评估失败：结果解析失败")

        logger.info("批次评估完成: sessionId={}, batchSize={}", sessionId, batchQuestions.size)
        return dto
    }

    private fun mergeQuestionEvaluations(
        batchResults: List<BatchEvaluationResult>,
        questions: List<InterviewSessionService.InterviewQuestionVo>
    ): Map<Int, QuestionEvaluationDto> {
        val map = mutableMapOf<Int, QuestionEvaluationDto>()
        for (result in batchResults) {
            val evaluations = result.report.questionEvaluations ?: emptyList()
            for (evaluation in evaluations) {
                map[evaluation.questionIndex] = evaluation
            }
        }
        for (question in questions) {
            if (!map.containsKey(question.questionIndex)) {
                throw BusinessException("面试评估失败：问题${question.questionIndex}缺少评估结果")
            }
        }
        return map
    }

    private fun buildQuestionDetails(
        questions: List<InterviewSessionService.InterviewQuestionVo>,
        evaluationMap: Map<Int, QuestionEvaluationDto>
    ): List<InterviewReportVo.QuestionEvaluationVo> {
        return questions.map { question ->
            val evaluation = evaluationMap[question.questionIndex]
                ?: throw BusinessException("面试评估失败：问题${question.questionIndex}缺少评估结果")
            InterviewReportVo.QuestionEvaluationVo(
                questionIndex = question.questionIndex, // 问题索引
                question = question.question, // 问题内容
                category = question.category, // 问题类别
                userAnswer = question.userAnswer ?: "", // 用户回答
                score = evaluation.score, // 得分
                feedback = evaluation.feedback ?: "" // 反馈
            )
        }
    }

    private fun buildReferenceAnswers(
        questions: List<InterviewSessionService.InterviewQuestionVo>,
        evaluationMap: Map<Int, QuestionEvaluationDto>
    ): List<InterviewReportVo.ReferenceAnswerVo> {
        return questions.map { question ->
            val evaluation = evaluationMap[question.questionIndex]
                ?: throw BusinessException("面试评估失败：问题${question.questionIndex}缺少评估结果")
            InterviewReportVo.ReferenceAnswerVo(
                questionIndex = question.questionIndex, // 问题索引
                question = question.question, // 问题内容
                referenceAnswer = evaluation.referenceAnswer ?: "", // 参考答案
                keyPoints = evaluation.keyPoints ?: emptyList() // 关键点
            )
        }
    }

    private fun buildCategoryScores(
        questionDetails: List<InterviewReportVo.QuestionEvaluationVo>
    ): List<InterviewReportVo.CategoryScoreVo> {
        val map = questionDetails.groupBy { it.category }
        return map.map { (category, details) ->
            val avgScore = details.map { it.score }.average().toInt()
            InterviewReportVo.CategoryScoreVo(
                category = category, // 类别名称
                score = avgScore, // 类别得分
                questionCount = details.size // 问题数量
            )
        }
    }

    private fun calculateOverallScore(details: List<InterviewReportVo.QuestionEvaluationVo>): Int {
        return details.map { it.score }.average().toInt()
    }

    private fun summarizeResults(
        resumeSummary: String,
        categoryScores: List<InterviewReportVo.CategoryScoreVo>,
        questionDetails: List<InterviewReportVo.QuestionEvaluationVo>,
        fallbackOverallFeedback: String,
        fallbackStrengths: List<String>,
        fallbackImprovements: List<String>
    ): FinalSummaryDto {
        val categorySummary = categoryScores.joinToString("\n") {
            "${it.category}：${it.score}分，${it.questionCount}题"
        }
        val questionHighlights = buildQuestionHighlights(questionDetails)
        val userPrompt = renderTemplate(
            summaryUserPrompt,
            mapOf(
                "resumeText" to resumeSummary,
                "categorySummary" to categorySummary,
                "questionHighlights" to questionHighlights,
                "fallbackOverallFeedback" to fallbackOverallFeedback,
                "fallbackStrengths" to fallbackStrengths.joinToString("；"),
                "fallbackImprovements" to fallbackImprovements.joinToString("；")
            )
        )

        val request = AiChatRequest(
            messages = listOf(
                AiChatMessage(role = AiChatRole.SYSTEM, content = summarySystemPrompt), // 系统提示词
                AiChatMessage(role = AiChatRole.USER, content = userPrompt) // 用户提示词
            )
        )

        val result = aiChatClient.chat(request)
        if (!result.success || result.content.isNullOrBlank()) {
            logger.warn("面试评估汇总失败，使用回退结果: {}", result.errorMessage ?: "AI返回空内容")
            return FinalSummaryDto(
                overallFeedback = fallbackOverallFeedback,
                strengths = fallbackStrengths,
                improvements = fallbackImprovements
            )
        }

        val cleaned = result.content.stripCodeFences() // 去除代码块标记，避免JSON解析失败
        val summary = JSONUtil.parseObject(cleaned, object : TypeReference<FinalSummaryDto>() {})
        return summary ?: FinalSummaryDto(
            overallFeedback = fallbackOverallFeedback,
            strengths = fallbackStrengths,
            improvements = fallbackImprovements
        )
    }

    private fun buildQARecords(questions: List<InterviewSessionService.InterviewQuestionVo>): String {
        val builder = StringBuilder()
        for (question in questions) {
            builder.append("问题${question.questionIndex + 1} [${question.category}]: ${question.question}\n")
            val answer = if (question.userAnswer.isNullOrBlank()) "(未回答)" else question.userAnswer
            builder.append("回答: $answer\n\n")
        }
        return builder.toString()
    }

    private fun buildQuestionHighlights(details: List<InterviewReportVo.QuestionEvaluationVo>): String {
        return details.take(20).joinToString("\n") {
            val shortQuestion = if (it.question.length > 50) it.question.substring(0, 50) + "..." else it.question
            val shortFeedback = if (it.feedback.length > 80) it.feedback.substring(0, 80) + "..." else it.feedback
            "- Q${it.questionIndex + 1} | $shortQuestion | 分数:${it.score} | 反馈:$shortFeedback"
        }
    }

    private fun mergeListItems(
        batchResults: List<BatchEvaluationResult>,
        isStrengths: Boolean
    ): List<String> {
        val items = mutableListOf<String>()
        for (result in batchResults) {
            val list = if (isStrengths) result.report.strengths else result.report.improvements
            list?.filter { it.isNotBlank() }?.let { items.addAll(it) }
        }
        return items.distinct().take(6)
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

    private data class EvaluationReportDto(
        val overallScore: Int, // 总分
        val overallFeedback: String?, // 总体评价
        val strengths: List<String>?, // 优势
        val improvements: List<String>?, // 改进建议
        val questionEvaluations: List<QuestionEvaluationDto>? // 题目评估
    )

    private data class QuestionEvaluationDto(
        val questionIndex: Int, // 问题索引
        val score: Int, // 得分
        val feedback: String?, // 反馈
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String>? // 关键点
    )

    private data class BatchEvaluationResult(
        val startIndex: Int, // 起始索引
        val endIndex: Int, // 结束索引
        val report: EvaluationReportDto // 评估结果
    )

    private data class FinalSummaryDto(
        val overallFeedback: String, // 综合评价
        val strengths: List<String>, // 优势列表
        val improvements: List<String> // 改进建议
    )

    /**
     * 面试评估报告返回对象
     */
    data class InterviewReportVo(
        val sessionId: String, // 会话ID
        val totalQuestions: Int, // 总题数
        val overallScore: Int, // 总分
        val categoryScores: List<CategoryScoreVo>, // 类别得分
        val questionDetails: List<QuestionEvaluationVo>, // 题目详情
        val overallFeedback: String, // 综合评价
        val strengths: List<String>, // 优势
        val improvements: List<String>, // 改进建议
        val referenceAnswers: List<ReferenceAnswerVo> // 参考答案
    ) {
        /**
         * 类别得分
         */
        data class CategoryScoreVo(
            val category: String, // 类别名称
            val score: Int, // 类别得分
            val questionCount: Int // 题目数量
        )

        /**
         * 单题评估
         */
        data class QuestionEvaluationVo(
            val questionIndex: Int, // 问题索引
            val question: String, // 问题内容
            val category: String, // 问题类别
            val userAnswer: String, // 用户回答
            val score: Int, // 得分
            val feedback: String // 反馈
        )

        /**
         * 参考答案
         */
        data class ReferenceAnswerVo(
            val questionIndex: Int, // 问题索引
            val question: String, // 问题内容
            val referenceAnswer: String, // 参考答案
            val keyPoints: List<String> // 关键点
        )
    }
}

package interview.guide.modules.knowledgebase.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.ai.PromptSecurityConstants
import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.ai.document.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 知识库问题异步生成服务
 * 由 QuestionGenStreamConsumer 调用，负责实际的 LLM 生成和结果持久化。
 * LLM 调用不在事务内；删除旧问题和保存新问题在同一个最小事务中完成。
 */
@Service
class KnowledgeBaseQuestionGenerationService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val questionRepository: KnowledgeBaseQuestionRepository, // 题目仓库
    private val vectorService: KnowledgeBaseVectorService, // 向量检索服务
    private val llmProviderRegistry: LlmProviderRegistry, // LLM 提供商注册表
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用器
    private val promptSanitizer: PromptSanitizer, // Prompt 注入净化工具
    private val stateService: QuestionGenerationStateService, // 生成状态事务服务
    private val objectMapper: ObjectMapper, // JSON 序列化工具
    @Value("classpath:prompts/knowledgebase-question-generation-system.st")
    private val systemPromptResource: Resource, // 系统提示词资源
    @Value("classpath:prompts/knowledgebase-question-generation-user.st")
    private val userPromptResource: Resource // 用户提示词资源
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseQuestionGenerationService::class.java)

    private val outputConverter: BeanOutputConverter<QuestionListDTO> =
        BeanOutputConverter(QuestionListDTO::class.java)

    /**
     * LLM 输出的题目集合
     */
    data class QuestionListDTO(
        val questions: List<QuestionDTO>? // 题目列表
    )

    /**
     * LLM 输出的单道题目
     */
    data class QuestionDTO(
        val category: String?, // 面试方向
        val type: String?, // 题目类型
        val question: String?, // 题干
        val topicSummary: String?, // 知识点摘要
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String>?, // 要点列表
        val scoringRubric: String?, // 评分标准
        val followUps: List<KnowledgeBaseQuestionFollowUpDTO>? // 追问列表
    )

    /**
     * 构建后的实体批次
     */
    private data class GenerationBatch(
        val questions: List<KnowledgeBaseQuestionEntity>, // 有效题目实体
        val skippedCount: Int // 跳过数量
    )

    /**
     * 执行问题生成（由 Consumer 调用，不在事务中）
     * 流程：校验 → 检索上下文 → 调用 LLM → 替换旧问题并保存新问题（小事务）
     *
     * @param kbId 知识库ID
     * @param taskId 任务ID（幂等判断）
     * @param config 生成参数快照
     */
    fun executeGeneration(kbId: Long, taskId: String, config: QuestionGenerationConfig) {
        val kb = knowledgeBaseRepository.findById(kbId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)

        // 再次确认任务ID匹配
        if (taskId != kb.questionGenTaskId) {
            log.info(
                "任务ID不匹配，放弃生成: kbId={}, msgTaskId={}, currentTaskId={}",
                kbId, taskId, kb.questionGenTaskId
            )
            return
        }

        val normalizedDifficulty = normalizeDifficulty(config.difficulty)
        val normalizedFollowUp = maxOf(0, minOf(config.followUpCount, 5))
        val normalizedCategoryLimit = maxOf(1, minOf(config.categoryLimit, 5))

        // 1. 检索上下文（不在事务中）
        val context = buildGenerationContext(kb)

        // 2. 调用 LLM（不在事务中）
        val chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider)
        val generated = callLlm(
            kb, chatClient, normalizedDifficulty,
            maxOf(1, config.questionCount), normalizedFollowUp, normalizedCategoryLimit, context
        )

        // 3. 校验生成结果
        if (generated.questions.isNullOrEmpty()) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果为空")
        }

        // 4. 构建实体列表（不在事务中）
        val batch = buildEntities(kb, normalizedDifficulty, context, normalizedFollowUp, generated)
        if (batch.questions.isEmpty()) {
            throw BusinessException(
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果无有效题干"
            )
        }

        // 5. 在一个小事务中校验当前任务、替换题目并更新完成状态
        val completed = stateService.replaceQuestionsAndComplete(
            kbId, taskId, batch.questions, batch.skippedCount
        )
        if (!completed) {
            log.info("题目生成任务已被替换，丢弃旧结果: kbId={}, taskId={}", kbId, taskId)
            return
        }

        log.info(
            "知识库问题异步生成完成: kbId={}, taskId={}, count={}",
            kbId, taskId, batch.questions.size
        )
    }

    private fun callLlm(
        kb: KnowledgeBaseEntity,
        chatClient: ChatClient,
        difficulty: String,
        questionCount: Int,
        followUpCount: Int,
        categoryLimit: Int,
        context: String
    ): QuestionListDTO {
        try {
            val systemPrompt = loadTemplate(systemPromptResource).render() +
                "\n\n" +
                outputConverter.format
            val userPrompt = loadTemplate(userPromptResource)
                .render(
                    mapOf(
                        "knowledgeBaseName" to (promptSanitizer.sanitize(kb.name ?: "") ?: ""),
                        "difficulty" to difficulty,
                        "questionCount" to questionCount,
                        "followUpCount" to followUpCount,
                        "categoryLimit" to categoryLimit,
                        "existingCategories" to promptSanitizer.sanitize(
                            buildExistingCategorySection(kb.id ?: 0L)
                        ).orEmpty(),
                        "existingQuestions" to promptSanitizer.sanitize(
                            buildExistingQuestionSection(kb.id ?: 0L, difficulty)
                        ).orEmpty(),
                        "context" to (
                            PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
                                promptSanitizer.wrapWithDelimiters(
                                    "knowledge-base",
                                    promptSanitizer.sanitize(context) ?: ""
                                )
                            )
                    )
                )

            return structuredOutputInvoker.invoke(
                chatClient,
                systemPrompt,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "知识库题库生成失败：",
                "知识库题库生成",
                log
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "知识库题库生成LLM调用失败: kbId={}, error={}", kb.id,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成失败")
        }
    }

    private fun buildEntities(
        kb: KnowledgeBaseEntity,
        difficulty: String,
        sourceContext: String,
        followUpCount: Int,
        generated: QuestionListDTO
    ): GenerationBatch {
        val entities = ArrayList<KnowledgeBaseQuestionEntity>()
        val batchKeys = LinkedHashSet<String>()
        var skippedCount = 0
        val questionDtos: List<QuestionDTO?> = generated.questions.orEmpty()
        for (dto in questionDtos) {
            if (dto == null) {
                skippedCount += 1
                continue
            }
            val rawQuestionValue = dto.question
            if (rawQuestionValue.isNullOrBlank()) {
                skippedCount += 1
                continue
            }
            val rawQuestion = rawQuestionValue.trim()
            val key = normalizeQuestionKey(rawQuestion)
            if (!batchKeys.add(key)) {
                skippedCount += 1
                continue
            }
            val category = normalizeCategory(dto.category, kb.name)
            val entity = KnowledgeBaseQuestionEntity()
            entity.knowledgeBase = kb
            entity.skillId = KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID
            entity.difficulty = difficulty
            entity.type = trimToNull(dto.type)
            entity.category = category
            entity.question = rawQuestion
            entity.topicSummary = trimToNull(dto.topicSummary)
            entity.referenceAnswer = trimToNull(dto.referenceAnswer)
            entity.keyPointsJson = writeStringList(dto.keyPoints)
            entity.scoringRubric = trimToNull(dto.scoringRubric)
            entity.followUpsJson = writeFollowUps(dto.followUps, followUpCount)
            entity.sourceContext = sourceContext
            entity.kbContentHash = kb.fileHash
            entity.status = KnowledgeBaseQuestionStatus.DRAFT
            entities.add(entity)
        }
        return GenerationBatch(entities, skippedCount)
    }

    private fun buildGenerationContext(kb: KnowledgeBaseEntity): String {
        val docs = ArrayList<Document>()
        val seenTexts = LinkedHashSet<String>()
        val kbId = kb.id ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
        for (query in buildGenerationQueries()) {
            val hits = vectorService.similaritySearch(query, listOf(kbId), RETRIEVAL_QUERY_TOP_K, 0.0)
            for (doc in hits) {
                val text = doc.text
                if (text.isNullOrBlank() || !seenTexts.add(text.trim())) {
                    continue
                }
                docs.add(doc)
                if (docs.size >= RETRIEVAL_TOP_K) {
                    break
                }
            }
            if (docs.size >= RETRIEVAL_TOP_K) {
                break
            }
        }
        if (docs.isEmpty()) {
            throw BusinessException(
                ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库未检索到可用于生成题目的内容"
            )
        }
        val context = docs.joinToString("\n\n---\n\n") { it.text ?: "" }
        return if (context.length <= MAX_CONTEXT_CHARS) {
            context
        } else {
            context.substring(0, MAX_CONTEXT_CHARS) + "\n...(知识库片段过长，已截断)"
        }
    }

    private fun buildGenerationQueries(): List<String> {
        return listOf(
            "核心概念 定义 背景 原理",
            "关键流程 步骤 方法 工作机制",
            "规则约束 条件 边界 例外 限制",
            "典型案例 常见问题 应用场景 最佳实践"
        )
    }

    private fun buildExistingCategorySection(knowledgeBaseId: Long): String {
        val categories: List<CategoryCount> = questionRepository.findCategoryCounts(knowledgeBaseId)
        if (categories.isEmpty()) {
            return "暂无已有方向"
        }
        return categories.take(10)
            .joinToString("\n") { c -> "- " + c.getCategory() + "（" + c.getCount() + " 题）" }
    }

    private fun buildExistingQuestionSection(knowledgeBaseId: Long, difficulty: String): String {
        val questions = questionRepository
            .findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(knowledgeBaseId, difficulty)
            .mapNotNull { it.question }
            .filter { it.isNotBlank() }
            .map { "- " + it.trim() }
        return if (questions.isEmpty()) "暂无已有题目" else questions.joinToString("\n")
    }

    @Throws(IOException::class)
    private fun loadTemplate(resource: Resource): PromptTemplate {
        resource.inputStream.use { input ->
            val content = String(input.readAllBytes(), StandardCharsets.UTF_8)
            return PromptTemplate(content)
        }
    }

    private fun normalizeDifficulty(difficulty: String?): String {
        if (difficulty.isNullOrBlank()) {
            return InterviewDefaults.DIFFICULTY
        }
        return difficulty.trim()
    }

    private fun normalizeCategory(category: String?, fallback: String?): String {
        if (category.isNullOrBlank()) {
            return if (!fallback.isNullOrBlank()) fallback.trim() else "未分类"
        }
        return category.trim()
    }

    private fun trimToNull(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        return value.trim()
    }

    private fun normalizeQuestionKey(question: String?): String {
        if (question == null) {
            return ""
        }
        val normalized = Normalizer.normalize(question, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun writeStringList(values: List<String>?): String {
        try {
            val sanitized = values.orEmpty()
                .filter { !it.isNullOrBlank() }
                .map { it.trim() }
            return objectMapper.writeValueAsString(sanitized)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e)
        }
    }

    private fun writeFollowUps(values: List<KnowledgeBaseQuestionFollowUpDTO>?, followUpCount: Int): String {
        try {
            val sanitized = values.orEmpty()
                .filter { it.question != null && !it.question.isNullOrBlank() }
                .map { value ->
                    KnowledgeBaseQuestionFollowUpDTO(
                        value.question?.trim(),
                        trimToNull(value.referenceAnswer),
                        value.keyPoints.orEmpty()
                            .filter { item -> !item.isNullOrBlank() }
                            .map { item -> item.trim() },
                        trimToNull(value.scoringRubric)
                    )
                }
                .take(followUpCount)
            return objectMapper.writeValueAsString(sanitized)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e)
        }
    }

    companion object {
        private const val RETRIEVAL_TOP_K: Int = 12
        private const val RETRIEVAL_QUERY_TOP_K: Int = 4
        private const val MAX_CONTEXT_CHARS: Int = 5000
    }
}

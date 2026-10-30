package interview.guide.modules.knowledgebase.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.knowledgebase.listener.QuestionGenStreamProducer
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionVo
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

/**
 * 知识库题目服务
 * 负责题目的增删改查与异步生成任务提交
 */
@Service
class KnowledgeBaseQuestionService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val questionRepository: KnowledgeBaseQuestionRepository, // 题目仓库
    private val objectMapper: ObjectMapper, // JSON 序列化工具
    private val questionGenStreamProducer: QuestionGenStreamProducer, // 题目生成任务生产者
    private val questionGenerationStateService: QuestionGenerationStateService // 生成状态事务服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseQuestionService::class.java)

    /**
     * 查询知识库题目列表（支持状态、方向、难度、关键词过滤）
     */
    @Transactional(readOnly = true)
    fun listQuestions(
        knowledgeBaseId: Long,
        status: KnowledgeBaseQuestionStatus?,
        category: String?,
        difficulty: String?,
        keyword: String?
    ): List<KnowledgeBaseQuestionVo> {
        val questions = if (status == null) {
            questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId)
        } else {
            questionRepository.findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(knowledgeBaseId, status)
        }
        val categoryFilter = trimToNull(category)
        val difficultyFilter = trimToNull(difficulty)
        val keywordFilter = trimToNull(keyword)
        return questions
            .filter { q -> categoryFilter == null || categoryFilter == q.category }
            .filter { q -> difficultyFilter == null || difficultyFilter == q.difficulty }
            .filter { q -> keywordFilter == null || containsKeyword(q, keywordFilter) }
            .map { q -> toDTO(q) }
    }

    /**
     * 列出某知识库下出现过的方向（含题目计数），按出现频次降序。
     * 用于前端筛选下拉、开始面试弹窗的方向选择。
     */
    @Transactional(readOnly = true)
    fun listCategories(knowledgeBaseId: Long): List<CategoryCount> {
        return questionRepository.findCategoryCounts(knowledgeBaseId)
    }

    /**
     * 手动创建题目
     */
    @Transactional(rollbackFor = [Exception::class])
    fun createQuestion(
        knowledgeBaseId: Long,
        request: CreateKnowledgeBaseQuestionRequest
    ): KnowledgeBaseQuestionVo {
        val kb = getKnowledgeBase(knowledgeBaseId)
        val question = KnowledgeBaseQuestionEntity()
        question.knowledgeBase = kb
        applyCreateRequest(question, request)
        question.kbContentHash = kb.fileHash
        question.status = request.status ?: KnowledgeBaseQuestionStatus.DRAFT
        return toDTO(questionRepository.save(question))
    }

    /**
     * 更新题目（仅更新请求中非空字段）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateQuestion(
        questionId: Long,
        request: UpdateKnowledgeBaseQuestionRequest
    ): KnowledgeBaseQuestionVo {
        val question = getQuestion(questionId)
        request.difficulty?.let { difficulty -> question.difficulty = normalizeDifficulty(difficulty) }
        request.type?.let { type -> question.type = trimToNull(type) }
        request.category?.let { category ->
            if (category.isBlank()) {
                throw BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空")
            }
            question.category = category.trim()
        }
        request.question?.let { rawQuestion ->
            if (rawQuestion.isBlank()) {
                throw BusinessException(ErrorCode.BAD_REQUEST, "题干不能为空")
            }
            question.question = rawQuestion.trim()
        }
        request.topicSummary?.let { summary -> question.topicSummary = trimToNull(summary) }
        request.referenceAnswer?.let { answer -> question.referenceAnswer = trimToNull(answer) }
        request.keyPoints?.let { keyPoints -> question.keyPointsJson = writeStringList(keyPoints) }
        request.scoringRubric?.let { rubric -> question.scoringRubric = trimToNull(rubric) }
        request.followUps?.let { followUps -> question.followUpsJson = writeFollowUps(followUps) }
        request.sourceContext?.let { context -> question.sourceContext = trimToNull(context) }
        request.status?.let { status -> question.status = status }
        return toDTO(questionRepository.save(question))
    }

    /**
     * 更新题目状态
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateStatus(questionId: Long, status: KnowledgeBaseQuestionStatus): KnowledgeBaseQuestionVo {
        val question = getQuestion(questionId)
        question.status = status
        return toDTO(questionRepository.save(question))
    }

    /**
     * 删除题目
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteQuestion(questionId: Long) {
        if (!questionRepository.existsById(questionId)) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND)
        }
        questionRepository.deleteById(questionId)
    }

    /**
     * 提交异步问题生成任务。
     * 校验知识库存在且状态允许，更新状态为 QUEUED，然后向 Redis Stream 投递任务。
     */
    fun submitGenerationTask(
        knowledgeBaseId: Long,
        request: GenerateKnowledgeBaseQuestionsRequest
    ): QuestionGenStatusResponse {
        val difficulty = normalizeDifficulty(request.difficulty)
        val followUpRaw = request.followUpCount
        val followUpCount = if (followUpRaw == null) {
            DEFAULT_FOLLOW_UP_COUNT
        } else {
            maxOf(0, minOf(followUpRaw, 5))
        }
        val categoryLimitRaw = request.categoryLimit
        val categoryLimit = if (categoryLimitRaw == null) {
            DEFAULT_CATEGORY_LIMIT
        } else {
            maxOf(1, minOf(categoryLimitRaw, 5))
        }
        val config = QuestionGenerationConfig(
            difficulty,
            maxOf(1, request.questionCount),
            followUpCount,
            categoryLimit,
            trimToNull(request.llmProvider)
        )
        val response = questionGenerationStateService.createTask(knowledgeBaseId, config)
        val taskId = response.questionGenTaskId
            ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "题目生成任务ID不存在")
        val sent = questionGenStreamProducer.sendGenerateTask(knowledgeBaseId, taskId)
        return if (sent) response else questionGenerationStateService.getStatus(knowledgeBaseId)
    }

    /**
     * 查询题目生成状态
     */
    @Transactional(readOnly = true)
    fun getGenerationStatus(knowledgeBaseId: Long): QuestionGenStatusResponse {
        return questionGenerationStateService.getStatus(knowledgeBaseId)
    }

    private fun applyCreateRequest(
        question: KnowledgeBaseQuestionEntity,
        request: CreateKnowledgeBaseQuestionRequest
    ) {
        question.skillId = KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID
        question.difficulty = normalizeDifficulty(request.difficulty)
        question.type = trimToNull(request.type)
        question.category = normalizeCategory(request.category, null)
        question.question = request.question.trim()
        question.topicSummary = trimToNull(request.topicSummary)
        question.referenceAnswer = trimToNull(request.referenceAnswer)
        question.keyPointsJson = writeStringList(request.keyPoints)
        question.scoringRubric = trimToNull(request.scoringRubric)
        question.followUpsJson = writeFollowUps(request.followUps)
        question.sourceContext = trimToNull(request.sourceContext)
    }

    private fun toDTO(question: KnowledgeBaseQuestionEntity): KnowledgeBaseQuestionVo {
        // FK 列声明为 insertable=false/updatable=false，save 后不一定回填；
        // 优先取关联实体的 ID（saveAll 后 KnowledgeBase 已在持久态），FK 列只做兜底
        val knowledgeBaseId = question.knowledgeBase?.id ?: question.knowledgeBaseId
        return KnowledgeBaseQuestionVo(
            question.id,
            knowledgeBaseId,
            question.knowledgeBase?.name,
            question.skillId,
            question.difficulty,
            question.type,
            question.category,
            question.question,
            question.topicSummary,
            question.referenceAnswer,
            readStringList(question.keyPointsJson),
            question.scoringRubric,
            readFollowUps(question.followUpsJson),
            question.sourceContext,
            question.status,
            question.createdAt,
            question.updatedAt
        )
    }

    private fun getKnowledgeBase(knowledgeBaseId: Long): KnowledgeBaseEntity {
        return knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
    }

    private fun getQuestion(questionId: Long): KnowledgeBaseQuestionEntity {
        return questionRepository.findById(questionId).orElse(null)
            ?: throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND)
    }

    private fun containsKeyword(question: KnowledgeBaseQuestionEntity, keyword: String): Boolean {
        val lower = keyword.lowercase(Locale.ROOT)
        return contains(question.question, lower) ||
            contains(question.referenceAnswer, lower) ||
            contains(question.scoringRubric, lower) ||
            contains(question.topicSummary, lower) ||
            contains(question.category, lower)
    }

    private fun contains(value: String?, keyword: String): Boolean {
        return value != null && value.lowercase(Locale.ROOT).contains(keyword)
    }

    private fun readStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE)
        } catch (e: JsonProcessingException) {
            log.warn("解析题目字符串列表失败: {}", e.message)
            return emptyList()
        }
    }

    private fun readFollowUps(value: String?): List<KnowledgeBaseQuestionFollowUpDTO> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE)
        } catch (e: JsonProcessingException) {
            log.warn("解析追问列表失败: {}", e.message)
            return emptyList()
        }
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

    private fun writeFollowUps(values: List<KnowledgeBaseQuestionFollowUpDTO>?): String {
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
            return objectMapper.writeValueAsString(sanitized)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e)
        }
    }

    private fun normalizeDifficulty(difficulty: String?): String {
        if (difficulty.isNullOrBlank()) {
            return InterviewDefaults.DIFFICULTY
        }
        return difficulty.trim()
    }

    /**
     * 归一化方向名：模型输出兜底。
     * - 空白时用知识库名（生成场景）或"未分类"（手动创建场景）
     * - 否则去掉首尾空白
     */
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

    companion object {
        private const val DEFAULT_FOLLOW_UP_COUNT: Int = 2
        private const val DEFAULT_CATEGORY_LIMIT: Int = 3
        private val STRING_LIST_TYPE = object : TypeReference<List<String>>() {}
        private val FOLLOW_UP_LIST_TYPE = object : TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>>() {}
    }
}

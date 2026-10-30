package interview.guide.modules.knowledgebase.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewSessionVo
import interview.guide.modules.interview.service.InterviewSessionService
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityVo
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityVo.CategoryOption
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityVo.FollowUpOption
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.LinkedHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 知识库面试服务
 * 按方向/难度从知识库题目中抽题并创建面试会话
 */
@Service
class KnowledgeBaseInterviewService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val questionRepository: KnowledgeBaseQuestionRepository, // 题目仓库
    private val interviewSessionService: InterviewSessionService, // 面试会话服务
    private val objectMapper: ObjectMapper // JSON 序列化工具
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseInterviewService::class.java)

    /**
     * 题目来源快照：题目实体 + 要点 + 可用追问池
     */
    private data class QuestionSource(
        val question: KnowledgeBaseQuestionEntity, // 题目实体
        val keyPoints: List<String>, // 要点列表
        val followUps: List<KnowledgeBaseQuestionFollowUpDTO> // 追问池
    )

    /**
     * 创建知识库面试会话
     *
     * @param request 创建请求 // 知识库、方向、难度、题量
     * @return 面试会话 // 包含按顺序组装好的主问题与追问
     */
    fun createSession(request: CreateKnowledgeBaseInterviewRequest): InterviewSessionVo {
        val knowledgeBaseId = request.knowledgeBaseId
            ?: throw BusinessException(ErrorCode.BAD_REQUEST, "知识库不能为空")
        knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)

        val category = trimToNull(request.category)
        val difficulty = normalizeDifficulty(request.difficulty)
        val mainCount = request.mainQuestionCount
        val followUpCount = request.followUpCount

        val raw = selectActiveQuestions(knowledgeBaseId, category, difficulty)

        val candidates = toQuestionSources(raw)
            .filter { source -> source.followUps.size >= followUpCount }

        if (candidates.size < mainCount) {
            throw BusinessException(
                ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
                buildInsufficientMessage(
                    mainCount, candidates.size, category, difficulty, followUpCount
                )
            )
        }

        val selected = ArrayList(candidates)
        selected.shuffle()
        val questions = buildQuestions(selected.subList(0, mainCount), followUpCount)

        log.info(
            "创建知识库面试: kbId={}, category={}, difficulty={}, mainQuestions={}, totalQuestions={}",
            knowledgeBaseId, category, difficulty, mainCount, questions.size
        )

        // skillId 已不再有业务含义，统一用默认值回填到面试会话表
        return interviewSessionService.createSessionFromQuestions(
            questions,
            request.llmProvider,
            KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID,
            difficulty,
            knowledgeBaseId,
            category
        )
    }

    /**
     * 计算指定方向、难度和主问题数下的可用容量
     *
     * @param knowledgeBaseId 知识库ID
     * @param category 面试方向（空表示覆盖所有方向）
     * @param difficulty 难度
     * @param mainQuestionCount 主问题数量
     * @return 容量信息 // 方向选项与追问数量选项
     */
    fun getCapacity(
        knowledgeBaseId: Long,
        category: String?,
        difficulty: String?,
        mainQuestionCount: Int
    ): KnowledgeBaseInterviewCapacityVo {
        knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)

        val normalizedCategory = trimToNull(category)
        val normalizedDifficulty = normalizeDifficulty(difficulty)
        val allSources = toQuestionSources(selectActiveQuestions(knowledgeBaseId, null, normalizedDifficulty))
        val scopedSources = allSources.filter { source ->
            normalizedCategory == null || normalizedCategory == source.question.category
        }

        val categories = calculateCategoryOptions(allSources)
        val followUpOptions = (0..MAX_FOLLOW_UP_COUNT).map { count ->
            val availableCount = scopedSources.count { source -> source.followUps.size >= count }
            FollowUpOption(
                count,
                availableCount,
                mainQuestionCount > 0 && availableCount >= mainQuestionCount
            )
        }

        return KnowledgeBaseInterviewCapacityVo(
            knowledgeBaseId,
            normalizedCategory,
            normalizedDifficulty,
            mainQuestionCount,
            categories,
            followUpOptions
        )
    }

    /**
     * 按 category 过滤候选题。
     * category 为空时跨所有方向筛选。
     */
    private fun selectActiveQuestions(
        knowledgeBaseId: Long,
        category: String?,
        difficulty: String
    ): List<KnowledgeBaseQuestionEntity> {
        if (category == null) {
            return questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
                knowledgeBaseId, difficulty, KnowledgeBaseQuestionStatus.ACTIVE
            )
        }
        return questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
            knowledgeBaseId, difficulty, category, KnowledgeBaseQuestionStatus.ACTIVE
        )
    }

    private fun buildQuestions(
        selected: List<QuestionSource>,
        followUpCount: Int
    ): List<InterviewQuestionVo> {
        val questions = ArrayList<InterviewQuestionVo>()
        for (source in selected) {
            val entity = source.question
            val mainIndex = questions.size
            questions.add(
                InterviewQuestionVo.fromQuestionBank(
                    mainIndex,
                    entity.question.orEmpty(),
                    defaultString(entity.type, "KNOWLEDGE_BASE"),
                    defaultString(entity.category, "知识库"),
                    entity.topicSummary,
                    entity.referenceAnswer,
                    source.keyPoints,
                    entity.scoringRubric,
                    entity.sourceContext
                )
            )

            // 在可用的追问池里随机抽 followUpCount 个，避免每次面试都问同一组追问
            val picked = pickFollowUps(source.followUps, followUpCount)
            for (followUp in picked) {
                questions.add(
                    InterviewQuestionVo(
                        questions.size,
                        followUp.question.orEmpty(),
                        defaultString(entity.type, "KNOWLEDGE_BASE"),
                        defaultString(entity.category, "知识库追问"),
                        entity.topicSummary,
                        null,
                        null,
                        null,
                        true,
                        mainIndex,
                        followUp.referenceAnswer,
                        followUp.keyPoints,
                        followUp.scoringRubric,
                        entity.sourceContext
                    )
                )
            }
        }
        return questions
    }

    /**
     * 在追问池里随机抽取严格的 count 个，池容量不足时拒绝继续组装。
     * 使用 Fisher-Yates 局部洗牌，避免改动原列表顺序。
     */
    private fun pickFollowUps(
        pool: List<KnowledgeBaseQuestionFollowUpDTO>?,
        count: Int
    ): List<KnowledgeBaseQuestionFollowUpDTO> {
        if (count <= 0) {
            return emptyList()
        }
        if (pool == null || pool.size < count) {
            throw BusinessException(
                ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
                "追问池在组装面试时发生变化，无法严格抽取 " + count + " 个追问"
            )
        }
        val n = count
        if (n == pool.size) {
            return ArrayList(pool)
        }
        val copy = ArrayList(pool)
        val random = ThreadLocalRandom.current()
        for (i in 0 until n) {
            val j = random.nextInt(i, copy.size)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy.subList(0, n)
    }

    private fun toQuestionSources(questions: List<KnowledgeBaseQuestionEntity>): List<QuestionSource> {
        return questions.map { question ->
            QuestionSource(
                question,
                readStringList(question.keyPointsJson),
                readUsableFollowUps(question.followUpsJson)
            )
        }
    }

    private fun calculateCategoryOptions(sources: List<QuestionSource>): List<CategoryOption> {
        val counts = LinkedHashMap<String, Int>()
        for (source in sources) {
            val category = trimToNull(source.question.category)
            if (category != null) {
                counts.merge(category, 1) { existing, value -> existing + value }
            }
        }
        return counts.entries
            .map { entry -> CategoryOption(entry.key, entry.value) }
            .sortedWith(
                compareByDescending<CategoryOption> { it.availableQuestionCount }
                    .thenBy { it.category ?: "" }
            )
    }

    private fun buildInsufficientMessage(
        requiredCount: Int,
        availableCount: Int,
        category: String?,
        difficulty: String,
        followUpCount: Int
    ): String {
        val direction = category ?: "全部方向"
        return "需要 " + requiredCount + " 道主问题，但只有 " + availableCount +
            " 道同时满足：方向=" + direction +
            "、难度=" + difficulty +
            "、每题至少 " + followUpCount + " 个追问"
    }

    private fun readStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE)
        } catch (e: JsonProcessingException) {
            log.warn("解析题目要点失败: {}", e.message)
            return emptyList()
        }
    }

    private fun readUsableFollowUps(value: String?): List<KnowledgeBaseQuestionFollowUpDTO> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        try {
            val followUps: List<KnowledgeBaseQuestionFollowUpDTO>? =
                objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE)
            if (followUps == null) {
                return emptyList()
            }
            return followUps
                .filter { followUp -> !followUp.question.isNullOrBlank() }
                .map { followUp ->
                    KnowledgeBaseQuestionFollowUpDTO(
                        followUp.question?.trim(),
                        followUp.referenceAnswer,
                        followUp.keyPoints,
                        followUp.scoringRubric
                    )
                }
        } catch (e: JsonProcessingException) {
            log.warn("解析追问失败: {}", e.message)
            return emptyList()
        }
    }

    private fun normalizeDifficulty(difficulty: String?): String {
        if (difficulty.isNullOrBlank()) {
            return InterviewDefaults.DIFFICULTY
        }
        return difficulty.trim()
    }

    private fun trimToNull(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        return value.trim()
    }

    private fun defaultString(value: String?, fallback: String): String {
        if (value.isNullOrBlank()) {
            return fallback
        }
        return value
    }

    companion object {
        private const val MAX_FOLLOW_UP_COUNT: Int = 5
        private val STRING_LIST_TYPE = object : TypeReference<List<String>>() {}
        private val FOLLOW_UP_LIST_TYPE = object : TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>>() {}
    }
}

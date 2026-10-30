package interview.guide.modules.interview.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.InterviewSessionCache
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.interview.listener.EvaluateStreamProducer
import interview.guide.modules.interview.model.CreateInterviewRequest
import interview.guide.modules.interview.model.HistoricalQuestion
import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewReportVo
import interview.guide.modules.interview.model.InterviewSessionEntity
import interview.guide.modules.interview.model.InterviewSessionVo
import interview.guide.modules.interview.model.SubmitAnswerRequest
import interview.guide.modules.interview.model.SubmitAnswerResponseVo
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Service
class InterviewSessionService(
    private val questionService: InterviewQuestionService, // 出题服务
    private val evaluationService: AnswerEvaluationService, // 评估服务
    private val persistenceService: InterviewPersistenceService, // 持久化服务
    private val sessionCache: InterviewSessionCache, // 会话缓存
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val evaluateStreamProducer: EvaluateStreamProducer, // 评估任务生产者
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val redisService: RedisService // Redis 操作封装
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewSessionService::class.java)

        private const val CREATE_LOCK_PREFIX: String = "interview:create:" // 创建锁前缀
        private const val CREATE_RESULT_PREFIX: String = "interview:create:result:" // 幂等结果前缀
        private val CREATE_RESULT_TTL: Duration = Duration.ofDays(1) // 幂等结果缓存时长
    }

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    fun createSession(request: CreateInterviewRequest): InterviewSessionVo {
        val requestId = normalizeRequestId(request.requestId)
        if (requestId == null) {
            return createSessionInternal(request)
        }

        return redisService.executeWithLock(
            CREATE_LOCK_PREFIX + requestId,
            185,
            600,
            TimeUnit.SECONDS
        ) {
            createIdempotentSession(request, requestId)
        }
    }

    /**
     * 幂等创建会话：优先复用内存缓存/数据库中的既有会话
     */
    private fun createIdempotentSession(request: CreateInterviewRequest, requestId: String): InterviewSessionVo {
        val resultKey = CREATE_RESULT_PREFIX + requestId
        val cachedSessionId = redisService.get<String>(resultKey)
        if (cachedSessionId != null) {
            log.info("复用缓存中的幂等创建请求: requestId={}, sessionId={}", requestId, cachedSessionId)
            return getSession(cachedSessionId)
        }

        val existing = persistenceService.findByRequestId(requestId)
        if (existing != null) {
            val existingSessionId = existing.sessionId ?: ""
            log.info("从数据库恢复幂等创建请求: requestId={}, sessionId={}", requestId, existingSessionId)
            redisService.set(resultKey, existingSessionId, CREATE_RESULT_TTL)
            return getSession(existingSessionId)
        }

        val created = createSessionInternal(request, requestId)
        redisService.set(resultKey, created.sessionId, CREATE_RESULT_TTL)
        return created
    }

    /**
     * 创建会话内部实现（无幂等键）
     */
    private fun createSessionInternal(request: CreateInterviewRequest): InterviewSessionVo {
        return createSessionInternal(request, null)
    }

    /**
     * 创建会话内部实现
     */
    private fun createSessionInternal(request: CreateInterviewRequest, requestId: String?): InterviewSessionVo {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId != null && request.forceCreate != true) {
            val unfinishedOpt = findUnfinishedSession(request.resumeId)
            if (unfinishedOpt != null) {
                log.info(
                    "检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId, unfinishedOpt.sessionId
                )
                return unfinishedOpt
            }
        }

        val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val skillId = request.skillId.ifEmpty { InterviewDefaults.SKILL_ID }
        val difficulty = request.difficulty ?: InterviewDefaults.DIFFICULTY

        log.info(
            "创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount, request.resumeId
        )

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        val historicalQuestions: List<HistoricalQuestion> =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId)

        // 基于 Skill 生成面试问题
        val questions = questionService.generateQuestionsBySkill(
            request.llmProvider,
            skillId,
            difficulty,
            request.resumeText,
            request.questionCount,
            historicalQuestions,
            request.customCategories,
            request.jdText
        )

        if (requestId != null) {
            try {
                persistenceService.saveIdempotentSession(
                    sessionId,
                    request.resumeId,
                    questions.size,
                    questions,
                    request.llmProvider,
                    skillId,
                    difficulty,
                    requestId
                )
            } catch (e: Exception) {
                val concurrentlyCreated = persistenceService.findByRequestId(requestId)
                if (concurrentlyCreated != null) {
                    return getSession(concurrentlyCreated.sessionId ?: "")
                }
                log.error("持久化幂等面试会话失败: requestId={}", requestId, e)
                throw BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败，请重试")
            }
        } else {
            try {
                persistenceService.saveSession(
                    sessionId, request.resumeId,
                    questions.size, questions, request.llmProvider, skillId, difficulty
                )
            } catch (e: Exception) {
                log.warn("保存面试会话到数据库失败: {}", e.message)
            }
        }

        // 幂等请求必须先成功落库，再写入易失缓存，保证进程异常后可从数据库恢复。
        sessionCache.saveSession(
            sessionId,
            request.resumeText ?: "",
            request.resumeId,
            null,
            null,
            questions,
            0,
            InterviewSessionVo.SessionStatus.CREATED
        )

        return InterviewSessionVo(
            sessionId = sessionId,
            resumeText = request.resumeText ?: "",
            totalQuestions = questions.size,
            currentQuestionIndex = 0,
            questions = questions,
            status = InterviewSessionVo.SessionStatus.CREATED,
            knowledgeBaseId = null,
            interviewCategory = null
        )
    }

    /**
     * 基于已有题目创建知识库面试会话
     */
    fun createSessionFromQuestions(
        questions: List<InterviewQuestionVo>?,
        llmProvider: String?,
        skillId: String?,
        difficulty: String?,
        knowledgeBaseId: Long?,
        interviewCategory: String?
    ): InterviewSessionVo {
        if (questions.isNullOrEmpty()) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "面试题目不能为空")
        }

        val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        persistenceService.saveSession(
            sessionId, null, questions.size, questions, llmProvider, skillId, difficulty,
            "KNOWLEDGE_BASE", knowledgeBaseId, interviewCategory
        )
        sessionCache.saveSession(
            sessionId, "", null, knowledgeBaseId, interviewCategory,
            questions, 0, InterviewSessionVo.SessionStatus.CREATED
        )

        return InterviewSessionVo(
            sessionId = sessionId,
            resumeText = "",
            totalQuestions = questions.size,
            currentQuestionIndex = 0,
            questions = questions,
            status = InterviewSessionVo.SessionStatus.CREATED,
            knowledgeBaseId = knowledgeBaseId,
            interviewCategory = interviewCategory
        )
    }

    /**
     * 校验并规范化幂等键
     */
    private fun normalizeRequestId(requestId: String?): String? {
        if (requestId.isNullOrBlank()) {
            return null
        }

        val normalized = requestId.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9_-]{8,64}"))) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "requestId 格式不正确")
        }
        return normalized
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    fun getSession(sessionId: String): InterviewSessionVo {
        // 1. 尝试从 Redis 缓存获取
        val cached = sessionCache.getSession(sessionId)
        if (cached != null) {
            return toDTO(cached)
        }

        // 2. 缓存未命中，从数据库恢复
        val restoredSession = restoreSessionFromDatabase(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)

        return toDTO(restoredSession)
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    fun findUnfinishedSession(resumeId: Long): InterviewSessionVo? {
        try {
            // 1. 先从 Redis 缓存查找
            val cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId)
            if (cachedSessionIdOpt != null) {
                val sessionId = cachedSessionIdOpt
                val cachedOpt = sessionCache.getSession(sessionId)
                if (cachedOpt != null) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId)
                    return toDTO(cachedOpt)
                }
            }

            // 2. 缓存未命中，从数据库查找
            val entity = persistenceService.findUnfinishedSession(resumeId) ?: return null

            val restoredSession = restoreSessionFromEntity(entity)
            if (restoredSession != null) {
                return toDTO(restoredSession)
            }
        } catch (e: Exception) {
            log.error("恢复未完成会话失败: {}", e.message, e)
        }
        return null
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    fun findUnfinishedSessionOrThrow(resumeId: Long): InterviewSessionVo {
        return findUnfinishedSession(resumeId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话")
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private fun restoreSessionFromDatabase(sessionId: String): CachedSession? {
        return try {
            val entity = persistenceService.findBySessionId(sessionId)
            entity?.let { restoreSessionFromEntity(it) }
        } catch (e: Exception) {
            log.error("从数据库恢复会话失败: {}", e.message, e)
            null
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private fun restoreSessionFromEntity(entity: InterviewSessionEntity): CachedSession? {
        return try {
            // 解析问题列表
            val questions = objectMapper.readValue(
                entity.questionsJson,
                object : TypeReference<MutableList<InterviewQuestionVo>>() {}
            )

            // 恢复已保存的答案
            val answers = persistenceService.findAnswersBySessionId(entity.sessionId ?: "")
            for (answer in answers) {
                val index = answer.questionIndex ?: continue
                if (index >= 0 && index < questions.size) {
                    val question = questions[index]
                    questions[index] = question.withAnswer(answer.userAnswer)
                }
            }

            val status = convertStatus(entity.status)

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                entity.sessionId ?: "",
                entity.resume?.resumeText ?: "",
                entity.resume?.id,
                entity.knowledgeBaseId,
                entity.interviewCategory,
                questions,
                entity.currentQuestionIndex ?: 0,
                status
            )

            log.info(
                "从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.sessionId, entity.currentQuestionIndex, entity.status
            )

            // 返回缓存的会话
            sessionCache.getSession(entity.sessionId ?: "")
        } catch (e: Exception) {
            log.error("恢复会话失败: {}", e.message, e)
            null
        }
    }

    /**
     * 实体状态转会话 DTO 状态
     */
    private fun convertStatus(status: InterviewSessionEntity.SessionStatus?): InterviewSessionVo.SessionStatus {
        return when (status) {
            InterviewSessionEntity.SessionStatus.CREATED -> InterviewSessionVo.SessionStatus.CREATED
            InterviewSessionEntity.SessionStatus.IN_PROGRESS -> InterviewSessionVo.SessionStatus.IN_PROGRESS
            InterviewSessionEntity.SessionStatus.COMPLETED -> InterviewSessionVo.SessionStatus.COMPLETED
            InterviewSessionEntity.SessionStatus.EVALUATED -> InterviewSessionVo.SessionStatus.EVALUATED
            null -> InterviewSessionVo.SessionStatus.CREATED
        }
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    fun getCurrentQuestionResponse(sessionId: String): Map<String, Any> {
        val question = getCurrentQuestion(sessionId)
        if (question == null) {
            return mapOf(
                "completed" to true,
                "message" to "所有问题已回答完毕"
            )
        }
        return mapOf(
            "completed" to false,
            "question" to question
        )
    }

    /**
     * 获取当前问题
     */
    fun getCurrentQuestion(sessionId: String): InterviewQuestionVo? {
        val session = getOrRestoreSession(sessionId)
        val questions = session.getQuestions(objectMapper)

        if (session.currentIndex >= questions.size) {
            return null // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.status == InterviewSessionVo.SessionStatus.CREATED) {
            session.status = InterviewSessionVo.SessionStatus.IN_PROGRESS
            sessionCache.updateSessionStatus(sessionId, InterviewSessionVo.SessionStatus.IN_PROGRESS)

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(
                    sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS
                )
            } catch (e: Exception) {
                log.warn("更新会话状态失败: {}", e.message)
            }
        }

        return questions[session.currentIndex]
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    fun submitAnswer(request: SubmitAnswerRequest): SubmitAnswerResponseVo {
        val session = getOrRestoreSession(request.sessionId)
        val questions = session.getQuestions(objectMapper).toMutableList()

        val index = request.questionIndex
            ?: throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: null")
        if (index < 0 || index >= questions.size) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: $index")
        }

        // 更新问题答案
        val question = questions[index]
        val answeredQuestion = question.withAnswer(request.answer)
        questions[index] = answeredQuestion

        // 移动到下一题
        val newIndex = index + 1

        // 检查是否全部完成
        val hasNextQuestion = newIndex < questions.size
        val nextQuestion = if (hasNextQuestion) questions[newIndex] else null

        val newStatus = if (hasNextQuestion) {
            InterviewSessionVo.SessionStatus.IN_PROGRESS
        } else {
            InterviewSessionVo.SessionStatus.COMPLETED
        }

        persistSubmittedAnswer(request, index, question, newIndex, newStatus)

        // 更新 Redis 缓存。DB 已经持久化成功，缓存失败时可由后续读取从数据库恢复。
        sessionCache.updateQuestions(request.sessionId, questions)
        sessionCache.updateCurrentIndex(request.sessionId, newIndex)
        if (newStatus == InterviewSessionVo.SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId, InterviewSessionVo.SessionStatus.COMPLETED)
            enqueueEvaluationTask(request.sessionId)
        }

        log.info(
            "会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId, index, questions.size - newIndex
        )

        return SubmitAnswerResponseVo(
            hasNextQuestion = hasNextQuestion,
            nextQuestion = nextQuestion,
            currentIndex = newIndex,
            totalQuestions = questions.size
        )
    }

    /**
     * 持久化提交的答案、进度与状态
     */
    private fun persistSubmittedAnswer(
        request: SubmitAnswerRequest,
        index: Int,
        question: InterviewQuestionVo,
        newIndex: Int,
        newStatus: InterviewSessionVo.SessionStatus
    ) {
        try {
            persistenceService.saveAnswer(
                request.sessionId, index,
                question.question, question.category,
                request.answer, 0, null // 分数在报告生成时更新
            )
            persistenceService.updateCurrentQuestionIndex(request.sessionId, newIndex)
            persistenceService.updateSessionStatus(
                request.sessionId,
                if (newStatus == InterviewSessionVo.SessionStatus.COMPLETED) {
                    InterviewSessionEntity.SessionStatus.COMPLETED
                } else {
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS
                }
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "保存答案到数据库失败: sessionId={}, questionIndex={}",
                request.sessionId, index, e
            )
            throw BusinessException(
                ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "保存答案失败，请稍后重试"
            )
        }
    }

    /**
     * 标记评估状态为 PENDING 并投递异步评估任务
     */
    private fun enqueueEvaluationTask(sessionId: String) {
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
        evaluateStreamProducer.sendEvaluateTask(sessionId)
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId)
    }

    /**
     * 暂存答案（不进入下一题）
     */
    fun saveAnswer(request: SubmitAnswerRequest) {
        val session = getOrRestoreSession(request.sessionId)
        val questions = session.getQuestions(objectMapper).toMutableList()

        val index = request.questionIndex
            ?: throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: null")
        if (index < 0 || index >= questions.size) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: $index")
        }

        // 更新问题答案
        val question = questions[index]
        val answeredQuestion = question.withAnswer(request.answer)
        questions[index] = answeredQuestion

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId, questions)

        // 更新状态为进行中
        if (session.status == InterviewSessionVo.SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId, InterviewSessionVo.SessionStatus.IN_PROGRESS)
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId, index,
                question.question, question.category,
                request.answer, 0, null
            )
            persistenceService.updateSessionStatus(
                request.sessionId,
                InterviewSessionEntity.SessionStatus.IN_PROGRESS
            )
        } catch (e: Exception) {
            log.warn("暂存答案到数据库失败: {}", e.message)
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId, index)
    }

    /**
     * 提前交卷（触发异步评估）
     */
    fun completeInterview(sessionId: String) {
        val session = getOrRestoreSession(sessionId)

        if (session.status == InterviewSessionVo.SessionStatus.COMPLETED ||
            session.status == InterviewSessionVo.SessionStatus.EVALUATED
        ) {
            throw BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED)
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, InterviewSessionVo.SessionStatus.COMPLETED)

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(
                sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED
            )
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
        } catch (e: Exception) {
            log.warn("更新会话状态失败: {}", e.message)
        }

        // 发送评估任务到 Redis Stream
        evaluateStreamProducer.sendEvaluateTask(sessionId)

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId)
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private fun getOrRestoreSession(sessionId: String): CachedSession {
        // 1. 尝试从 Redis 缓存获取
        val cached = sessionCache.getSession(sessionId)
        if (cached != null) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId)
            return cached
        }

        // 2. 缓存未命中，从数据库恢复
        return restoreSessionFromDatabase(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
    }

    /**
     * 生成评估报告
     */
    fun generateReport(sessionId: String): InterviewReportVo {
        val session = getOrRestoreSession(sessionId)

        if (session.status != InterviewSessionVo.SessionStatus.COMPLETED &&
            session.status != InterviewSessionVo.SessionStatus.EVALUATED
        ) {
            throw BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告")
        }

        log.info("生成面试报告: {}", sessionId)

        val questions = session.getQuestions(objectMapper)

        // 获取 LLM 客户端
        var provider: String? = null
        val entity = persistenceService.findBySessionId(sessionId)
        if (entity != null) {
            provider = entity.llmProvider
        }
        val chatClient: ChatClient = llmProviderRegistry.getChatClientOrDefault(provider)

        val report = evaluationService.evaluateInterview(
            chatClient,
            sessionId,
            session.resumeText,
            questions
        )

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, InterviewSessionVo.SessionStatus.EVALUATED)

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report)
        } catch (e: Exception) {
            log.warn("保存报告到数据库失败: {}", e.message)
        }

        return report
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private fun toDTO(session: CachedSession): InterviewSessionVo {
        val questions = session.getQuestions(objectMapper)
        return InterviewSessionVo(
            sessionId = session.sessionId ?: "",
            resumeText = session.resumeText ?: "",
            totalQuestions = questions.size,
            currentQuestionIndex = session.currentIndex,
            questions = questions,
            status = session.status,
            knowledgeBaseId = session.knowledgeBaseId,
            interviewCategory = session.interviewCategory
        )
    }
}

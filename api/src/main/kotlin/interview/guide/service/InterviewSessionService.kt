package interview.guide.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.entity.InterviewAnswerEntity
import interview.guide.entity.InterviewSessionEntity
import interview.guide.infrastructure.redis.InterviewSessionCache
import interview.guide.listener.EvaluateStreamProducer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Service
class InterviewSessionService(
    private val questionService: InterviewQuestionService, // 面试问题生成服务
    private val evaluationService: AnswerEvaluationService, // 评估服务
    private val persistenceService: InterviewPersistenceService, // 持久化服务
    private val sessionCache: InterviewSessionCache, // Redis 缓存
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val evaluateStreamProducer: EvaluateStreamProducer // 评估任务生产者
) {

    private val log = LoggerFactory.getLogger(InterviewSessionService::class.java)

    /**
     * 创建新的面试会话
     *
     * @param request 创建请求 // 简历信息与题数
     * @return 面试会话 // 创建后的会话
     */
    fun createSession(request: CreateInterviewRequest): InterviewSessionVo {
        if (request.resumeId != null && request.forceCreate != true) {
            val unfinished = findUnfinishedSession(request.resumeId)
            if (unfinished != null) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId, unfinished.sessionId)
                return unfinished
            }
        }

        val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        log.info("创建新面试会话: {}, 题目数量: {}, resumeId: {}", sessionId, request.questionCount, request.resumeId)

        val historicalQuestions = request.resumeId?.let { persistenceService.getHistoricalQuestionsByResumeId(it) }

        val questions = questionService.generateQuestions(
            request.resumeText,
            request.questionCount,
            historicalQuestions
        )

        sessionCache.saveSession(
            sessionId,
            request.resumeText,
            request.resumeId,
            questions,
            0,
            InterviewSessionStatus.CREATED
        )

        if (request.resumeId != null) {
            try {
                persistenceService.saveSession(sessionId, request.resumeId, questions.size, questions)
            } catch (e: Exception) {
                log.warn("保存面试会话到数据库失败: {}", e.message)
            }
        }

        return InterviewSessionVo(
            sessionId,
            request.resumeText,
            questions.size,
            0,
            questions,
            InterviewSessionStatus.CREATED
        )
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    fun getSession(sessionId: String): InterviewSessionVo {
        val cached = sessionCache.getSession(sessionId)
        if (cached != null) {
            return toVo(cached)
        }

        val restored = restoreSessionFromDatabase(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
        return toVo(restored)
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    fun findUnfinishedSession(resumeId: Long): InterviewSessionVo? {
        try {
            val cachedSessionId = sessionCache.findUnfinishedSessionId(resumeId)
            if (cachedSessionId != null) {
                val cached = sessionCache.getSession(cachedSessionId)
                if (cached != null) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, cachedSessionId)
                    return toVo(cached)
                }
            }

            val entity = persistenceService.findUnfinishedSession(resumeId) ?: return null
            val restored = restoreSessionFromEntity(entity)
            if (restored != null) {
                return toVo(restored)
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
     * 获取当前问题的响应（包含完成状态）
     */
    fun getCurrentQuestionResponse(sessionId: String): Map<String, Any> {
        val question = getCurrentQuestion(sessionId)
        return if (question == null) {
            mapOf("completed" to true, "message" to "所有问题已回答完毕")
        } else {
            mapOf("completed" to false, "question" to question)
        }
    }

    /**
     * 获取当前问题
     */
    fun getCurrentQuestion(sessionId: String): InterviewQuestionVo? {
        val session = getOrRestoreSession(sessionId)
        val questions = session.getQuestions(objectMapper).toMutableList()

        if (session.currentIndex >= questions.size) {
            return null
        }

        if (session.status == InterviewSessionStatus.CREATED) {
            session.status = InterviewSessionStatus.IN_PROGRESS
            sessionCache.updateSessionStatus(sessionId, InterviewSessionStatus.IN_PROGRESS)
            try {
                persistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            } catch (e: Exception) {
                log.warn("更新会话状态失败: {}", e.message)
            }
        }

        return questions[session.currentIndex]
    }

    /**
     * 提交答案（并进入下一题）
     */
    fun submitAnswer(request: SubmitAnswerRequest): SubmitAnswerResponseVo {
        val session = getOrRestoreSession(request.sessionId)
        val questions = session.getQuestions(objectMapper).toMutableList()

        val index = request.questionIndex ?: throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "问题索引为空")
        if (index < 0 || index >= questions.size) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: $index")
        }

        val question = questions[index]
        val answeredQuestion = question.withAnswer(request.answer)
        questions[index] = answeredQuestion

        val newIndex = index + 1
        val hasNextQuestion = newIndex < questions.size
        val nextQuestion = if (hasNextQuestion) questions[newIndex] else null
        val newStatus = if (hasNextQuestion) InterviewSessionStatus.IN_PROGRESS else InterviewSessionStatus.COMPLETED

        sessionCache.updateQuestions(request.sessionId, questions)
        sessionCache.updateCurrentIndex(request.sessionId, newIndex)
        if (newStatus == InterviewSessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId, InterviewSessionStatus.COMPLETED)
        }

        try {
            persistenceService.saveAnswer(
                request.sessionId,
                index,
                question.question,
                question.category,
                request.answer,
                0,
                null
            )
            persistenceService.updateCurrentQuestionIndex(request.sessionId, newIndex)
            persistenceService.updateSessionStatus(
                request.sessionId,
                if (newStatus == InterviewSessionStatus.COMPLETED)
                    InterviewSessionEntity.SessionStatus.COMPLETED
                else
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS
            )

            if (!hasNextQuestion) {
                persistenceService.updateEvaluateStatus(request.sessionId, AsyncTaskStatus.PENDING, null)
                evaluateStreamProducer.sendEvaluateTask(request.sessionId)
                log.info("会话 {} 已完成所有问题，评估任务已入队", request.sessionId)
            }
        } catch (e: Exception) {
            log.warn("保存答案到数据库失败: {}", e.message)
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题", request.sessionId, index, questions.size - newIndex)

        return SubmitAnswerResponseVo(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size
        )
    }

    /**
     * 暂存答案（不进入下一题）
     */
    fun saveAnswer(request: SubmitAnswerRequest) {
        val session = getOrRestoreSession(request.sessionId)
        val questions = session.getQuestions(objectMapper).toMutableList()

        val index = request.questionIndex ?: throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "问题索引为空")
        if (index < 0 || index >= questions.size) {
            throw BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: $index")
        }

        val question = questions[index]
        val answeredQuestion = question.withAnswer(request.answer)
        questions[index] = answeredQuestion

        sessionCache.updateQuestions(request.sessionId, questions)
        if (session.status == InterviewSessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId, InterviewSessionStatus.IN_PROGRESS)
        }

        try {
            persistenceService.saveAnswer(
                request.sessionId,
                index,
                question.question,
                question.category,
                request.answer,
                0,
                null
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

        if (session.status == InterviewSessionStatus.COMPLETED || session.status == InterviewSessionStatus.EVALUATED) {
            throw BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED)
        }

        sessionCache.updateSessionStatus(sessionId, InterviewSessionStatus.COMPLETED)
        try {
            persistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.SessionStatus.COMPLETED)
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
        } catch (e: Exception) {
            log.warn("更新会话状态失败: {}", e.message)
        }

        evaluateStreamProducer.sendEvaluateTask(sessionId)
        log.info("会话 {} 提前交卷，评估任务已入队", sessionId)
    }

    /**
     * 生成评估报告
     */
    fun generateReport(sessionId: String): InterviewReportVo {
        val session = getOrRestoreSession(sessionId)

        if (session.status != InterviewSessionStatus.COMPLETED && session.status != InterviewSessionStatus.EVALUATED) {
            throw BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告")
        }

        log.info("生成面试报告: {}", sessionId)

        val questions = session.getQuestions(objectMapper)
        val report = evaluationService.evaluateInterview(sessionId, session.resumeText ?: "", questions)

        sessionCache.updateSessionStatus(sessionId, InterviewSessionStatus.EVALUATED)

        try {
            persistenceService.saveReport(sessionId, report)
        } catch (e: Exception) {
            log.warn("保存报告到数据库失败: {}", e.message)
        }

        return report
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private fun getOrRestoreSession(sessionId: String): InterviewSessionCache.CachedSession {
        val cached = sessionCache.getSession(sessionId)
        if (cached != null) {
            sessionCache.refreshSessionTTL(sessionId)
            return cached
        }

        val restored = restoreSessionFromDatabase(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
        return restored
    }

    private fun restoreSessionFromDatabase(sessionId: String): InterviewSessionCache.CachedSession? {
        return try {
            val entity = persistenceService.findBySessionId(sessionId)
            entity?.let { restoreSessionFromEntity(it) }
        } catch (e: Exception) {
            log.error("从数据库恢复会话失败: {}", e.message, e)
            null
        }
    }

    private fun restoreSessionFromEntity(entity: InterviewSessionEntity): InterviewSessionCache.CachedSession? {
        return try {
            val questions: MutableList<InterviewQuestionVo> = objectMapper.readValue(
                entity.questionsJson ?: "[]",
                object : TypeReference<List<InterviewQuestionVo>>() {}
            ).toMutableList()

            val answers: List<InterviewAnswerEntity> = persistenceService.findAnswersBySessionId(entity.sessionId ?: "")
            for (answer in answers) {
                val index = answer.questionIndex ?: continue
                if (index >= 0 && index < questions.size) {
                    val question = questions[index]
                    questions[index] = question.withAnswer(answer.userAnswer)
                }
            }

            val status = convertStatus(entity.status)

            sessionCache.saveSession(
                entity.sessionId ?: "",
                entity.resume?.resumeText ?: "",
                entity.resume?.id,
                questions,
                entity.currentQuestionIndex ?: 0,
                status
            )

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.sessionId, entity.currentQuestionIndex, entity.status)

            sessionCache.getSession(entity.sessionId ?: "")
        } catch (e: Exception) {
            log.error("恢复会话失败: {}", e.message, e)
            null
        }
    }

    private fun convertStatus(status: InterviewSessionEntity.SessionStatus?): InterviewSessionStatus {
        return when (status) {
            InterviewSessionEntity.SessionStatus.CREATED -> InterviewSessionStatus.CREATED
            InterviewSessionEntity.SessionStatus.IN_PROGRESS -> InterviewSessionStatus.IN_PROGRESS
            InterviewSessionEntity.SessionStatus.COMPLETED -> InterviewSessionStatus.COMPLETED
            InterviewSessionEntity.SessionStatus.EVALUATED -> InterviewSessionStatus.EVALUATED
            else -> InterviewSessionStatus.CREATED
        }
    }

    private fun toVo(session: InterviewSessionCache.CachedSession): InterviewSessionVo {
        val questions = session.getQuestions(objectMapper)
        return InterviewSessionVo(
            session.sessionId ?: "",
            session.resumeText ?: "",
            questions.size,
            session.currentIndex,
            questions,
            session.status
        )
    }
}

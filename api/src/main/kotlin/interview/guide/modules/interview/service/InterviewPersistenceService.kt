package interview.guide.modules.interview.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.interview.model.HistoricalQuestion
import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewReportVo
import interview.guide.modules.interview.model.InterviewSessionEntity
import interview.guide.modules.interview.repository.InterviewAnswerRepository
import interview.guide.modules.interview.repository.InterviewSessionRepository
import interview.guide.modules.resume.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Service
class InterviewPersistenceService(
    private val sessionRepository: InterviewSessionRepository, // 会话仓库
    private val answerRepository: InterviewAnswerRepository, // 答案仓库
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val objectMapper: ObjectMapper // JSON 序列化
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewPersistenceService::class.java)
        private const val MAX_HISTORICAL_QUESTIONS: Int = 60 // 历史题目最大数量
    }

    /**
     * 保存新的面试会话（支持可选简历）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSession(
        sessionId: String,
        resumeId: Long?,
        totalQuestions: Int,
        questions: List<InterviewQuestionVo>,
        llmProvider: String?,
        skillId: String?,
        difficulty: String?
    ): InterviewSessionEntity {
        return saveSession(
            sessionId, resumeId, totalQuestions, questions, llmProvider, skillId, difficulty,
            "NORMAL", null, null
        )
    }

    /**
     * 保存新的面试会话（带来源信息）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSession(
        sessionId: String,
        resumeId: Long?,
        totalQuestions: Int,
        questions: List<InterviewQuestionVo>,
        llmProvider: String?,
        skillId: String?,
        difficulty: String?,
        sourceType: String?,
        knowledgeBaseId: Long?,
        interviewCategory: String?
    ): InterviewSessionEntity {
        return saveSessionInternal(
            sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, sourceType, knowledgeBaseId, interviewCategory, null
        )
    }

    /**
     * 保存幂等面试会话（带创建请求幂等键）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveIdempotentSession(
        sessionId: String,
        resumeId: Long?,
        totalQuestions: Int,
        questions: List<InterviewQuestionVo>,
        llmProvider: String?,
        skillId: String?,
        difficulty: String?,
        requestId: String
    ): InterviewSessionEntity {
        return saveSessionInternal(
            sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null, null, requestId
        )
    }

    /**
     * 保存会话内部实现
     */
    private fun saveSessionInternal(
        sessionId: String,
        resumeId: Long?,
        totalQuestions: Int,
        questions: List<InterviewQuestionVo>,
        llmProvider: String?,
        skillId: String?,
        difficulty: String?,
        sourceType: String?,
        knowledgeBaseId: Long?,
        interviewCategory: String?,
        requestId: String?
    ): InterviewSessionEntity {
        try {
            val session = InterviewSessionEntity()
            session.sessionId = sessionId
            session.requestId = requestId
            session.totalQuestions = totalQuestions
            session.currentQuestionIndex = 0
            session.status = InterviewSessionEntity.SessionStatus.CREATED
            session.questionsJson = objectMapper.writeValueAsString(questions)
            session.llmProvider = llmProvider ?: "default"
            session.skillId = skillId ?: InterviewDefaults.SKILL_ID
            session.difficulty = difficulty ?: InterviewDefaults.DIFFICULTY
            session.sourceType = sourceType ?: "NORMAL"
            session.knowledgeBaseId = knowledgeBaseId
            session.interviewCategory = interviewCategory

            // 简历可选：有 resumeId 则关联简历
            if (resumeId != null) {
                resumeRepository.findById(resumeId).ifPresent { resume -> session.resume = resume }
            }

            val saved = sessionRepository.save(session)
            log.info(
                "面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                sessionId, skillId, resumeId, session.sourceType
            )

            return saved
        } catch (e: JsonProcessingException) {
            log.error("序列化问题列表失败: {}", e.message, e)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败")
        }
    }

    /**
     * 更新会话状态
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateSessionStatus(sessionId: String, status: InterviewSessionEntity.SessionStatus) {
        val session = sessionRepository.findBySessionId(sessionId) ?: return
        session.status = status
        if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
            status == InterviewSessionEntity.SessionStatus.EVALUATED
        ) {
            session.completedAt = LocalDateTime.now()
        }
        sessionRepository.save(session)
    }

    /**
     * 更新评估状态
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateEvaluateStatus(sessionId: String, status: AsyncTaskStatus, error: String?) {
        val session = sessionRepository.findBySessionId(sessionId) ?: return
        session.evaluateStatus = status
        if (error != null) {
            session.evaluateError = if (error.length > 500) error.substring(0, 500) else error
        } else {
            session.evaluateError = null
        }
        sessionRepository.save(session)
        log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status)
    }

    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateCurrentQuestionIndex(sessionId: String, index: Int) {
        val session = sessionRepository.findBySessionId(sessionId) ?: return
        session.currentQuestionIndex = index
        session.status = InterviewSessionEntity.SessionStatus.IN_PROGRESS
        sessionRepository.save(session)
    }

    /**
     * 保存面试答案
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveAnswer(
        sessionId: String,
        questionIndex: Int,
        question: String?,
        category: String?,
        userAnswer: String?,
        score: Int,
        feedback: String?
    ): InterviewAnswerEntity {
        val session = sessionRepository.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)

        val answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            ?: InterviewAnswerEntity().also {
                it.session = session
                it.questionIndex = questionIndex
            }

        answer.question = question
        answer.category = category
        answer.userAnswer = userAnswer
        answer.score = score
        answer.feedback = feedback

        val saved = answerRepository.save(answer)
        log.info(
            "面试答案已保存: sessionId={}, questionIndex={}, score={}",
            sessionId, questionIndex, score
        )

        return saved
    }

    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveReport(sessionId: String, report: InterviewReportVo) {
        try {
            val session = sessionRepository.findBySessionId(sessionId)
            if (session == null) {
                log.warn("会话不存在: {}", sessionId)
                return
            }

            session.overallScore = report.overallScore
            session.overallFeedback = report.overallFeedback
            session.strengthsJson = objectMapper.writeValueAsString(report.strengths)
            session.improvementsJson = objectMapper.writeValueAsString(report.improvements)
            session.referenceAnswersJson = objectMapper.writeValueAsString(report.referenceAnswers)
            session.status = InterviewSessionEntity.SessionStatus.EVALUATED
            session.completedAt = LocalDateTime.now()

            sessionRepository.save(session)

            // 查询已存在的答案，建立索引
            val existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId)
            val answerMap = HashMap<Int?, InterviewAnswerEntity>()
            for (a in existingAnswers) {
                answerMap.putIfAbsent(a.questionIndex, a)
            }

            // 建立参考答案索引
            val refAnswerMap = HashMap<Int, InterviewReportVo.ReferenceAnswerVo>()
            for (r in report.referenceAnswers) {
                refAnswerMap.putIfAbsent(r.questionIndex, r)
            }

            val answersToSave = ArrayList<InterviewAnswerEntity>()

            // 遍历所有评估结果，更新或创建答案记录
            for (eval in report.questionDetails) {
                var answer = answerMap[eval.questionIndex]

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = InterviewAnswerEntity()
                    answer.session = session
                    answer.questionIndex = eval.questionIndex
                    answer.question = eval.question
                    answer.category = eval.category
                    answer.userAnswer = null // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex)
                }

                // 更新评分和反馈
                answer.score = eval.score
                answer.feedback = eval.feedback

                // 设置参考答案和关键点
                val refAns = refAnswerMap[eval.questionIndex]
                if (refAns != null) {
                    answer.referenceAnswer = refAns.referenceAnswer
                    if (!refAns.keyPoints.isNullOrEmpty()) {
                        answer.keyPointsJson = objectMapper.writeValueAsString(refAns.keyPoints)
                    }
                }

                answersToSave.add(answer)
            }

            answerRepository.saveAll(answersToSave)
            log.info(
                "面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore, answersToSave.size
            )
        } catch (e: JsonProcessingException) {
            log.error("序列化报告失败: {}", e.message, e)
        }
    }

    /**
     * 根据会话ID获取会话
     */
    fun findBySessionId(sessionId: String): InterviewSessionEntity? {
        return sessionRepository.findBySessionId(sessionId)
    }

    /**
     * 根据创建请求幂等键获取会话
     */
    fun findByRequestId(requestId: String): InterviewSessionEntity? {
        return sessionRepository.findByRequestId(requestId)
    }

    /**
     * 获取简历的所有面试记录
     */
    fun findByResumeId(resumeId: Long): List<InterviewSessionEntity> {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId)
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     */
    fun findAll(): List<InterviewSessionEntity> {
        return sessionRepository.findAllByOrderByCreatedAtDesc()
    }

    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSessionsByResumeId(resumeId: Long) {
        val sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId)
        if (sessions.isNotEmpty()) {
            sessionRepository.deleteAll(sessions)
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size)
        }
    }

    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSessionBySessionId(sessionId: String) {
        val session = sessionRepository.findBySessionId(sessionId)
        if (session != null) {
            sessionRepository.delete(session)
            log.info("已删除面试会话: sessionId={}", sessionId)
        } else {
            throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
        }
    }

    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    fun findUnfinishedSession(resumeId: Long): InterviewSessionEntity? {
        val unfinishedStatuses = listOf(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        )
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses)
    }

    /**
     * 根据会话ID查找所有答案
     */
    fun findAnswersBySessionId(sessionId: String): List<InterviewAnswerEntity> {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId)
    }

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    fun getHistoricalQuestions(skillId: String, resumeId: Long?): List<HistoricalQuestion> {
        val sessions = if (resumeId != null) {
            sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId)
        } else {
            sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId)
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size)

        val seen = LinkedHashSet<String>()
        val result = sessions.asSequence()
            .mapNotNull { it.questionsJson }
            .filter { it.isNotEmpty() }
            .flatMap { json ->
                try {
                    val questions = objectMapper.readValue(
                        json,
                        object : TypeReference<List<InterviewQuestionVo>>() {}
                    )
                    questions.asSequence()
                        .filter { q -> !q.isFollowUp }
                        .map { q -> HistoricalQuestion(q.question, q.type, q.topicSummary) }
                } catch (e: Exception) {
                    log.error("解析历史问题JSON失败", e)
                    emptySequence()
                }
            }
            .filter { hq -> seen.add(hq.question) }
            .take(MAX_HISTORICAL_QUESTIONS)
            .toList()

        log.info(
            "历史题目加载完成: 去重后 {} 道主问题，按分类: {}",
            result.size,
            result.groupingBy { hq -> hq.type ?: "GENERAL" }.eachCount()
        )

        return result
    }
}

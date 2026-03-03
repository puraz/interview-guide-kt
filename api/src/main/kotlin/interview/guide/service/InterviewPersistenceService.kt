package interview.guide.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.entity.InterviewAnswerEntity
import interview.guide.entity.InterviewSessionEntity
import interview.guide.repository.InterviewAnswerRepository
import interview.guide.repository.InterviewSessionRepository
import interview.guide.repository.ResumeRepository
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

    private val log = LoggerFactory.getLogger(InterviewPersistenceService::class.java)

    /**
     * 保存新的面试会话
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSession(sessionId: String, resumeId: Long, totalQuestions: Int, questions: List<InterviewQuestionVo>): InterviewSessionEntity {
        val resume = resumeRepository.findById(resumeId).orElseThrow {
            BusinessException(ErrorCode.RESUME_NOT_FOUND)
        }

        try {
            val session = InterviewSessionEntity().apply {
                this.sessionId = sessionId
                this.resume = resume
                this.totalQuestions = totalQuestions
                this.currentQuestionIndex = 0
                this.status = InterviewSessionEntity.SessionStatus.CREATED
                this.questionsJson = objectMapper.writeValueAsString(questions)
            }
            val saved = sessionRepository.save(session)
            log.info("面试会话已保存: sessionId={}, resumeId={}", sessionId, resumeId)
            return saved
        } catch (e: Exception) {
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
        if (status == InterviewSessionEntity.SessionStatus.COMPLETED || status == InterviewSessionEntity.SessionStatus.EVALUATED) {
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
        session.evaluateError = error?.let { if (it.length > 500) it.substring(0, 500) else it }
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
        question: String,
        category: String?,
        userAnswer: String?,
        score: Int,
        feedback: String?
    ): InterviewAnswerEntity {
        val session = sessionRepository.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)

        val answer = answerRepository.findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            ?: InterviewAnswerEntity().apply {
                this.session = session
                this.questionIndex = questionIndex
            }

        answer.question = question
        answer.category = category
        answer.userAnswer = userAnswer
        answer.score = score
        answer.feedback = feedback

        val saved = answerRepository.save(answer)
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", sessionId, questionIndex, score)
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

            val existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId)
            val answerMap = existingAnswers.associateBy { it.questionIndex }

            val refAnswerMap = report.referenceAnswers.associateBy { it.questionIndex }

            val answersToSave = mutableListOf<InterviewAnswerEntity>()

            for (eval in report.questionDetails) {
                var answer = answerMap[eval.questionIndex]
                if (answer == null) {
                    answer = InterviewAnswerEntity().apply {
                        this.session = session
                        this.questionIndex = eval.questionIndex
                        this.question = eval.question
                        this.category = eval.category
                        this.userAnswer = null
                    }
                }

                answer.score = eval.score
                answer.feedback = eval.feedback

                val refAns = refAnswerMap[eval.questionIndex]
                if (refAns != null) {
                    answer.referenceAnswer = refAns.referenceAnswer
                    if (refAns.keyPoints.isNotEmpty()) {
                        answer.keyPointsJson = objectMapper.writeValueAsString(refAns.keyPoints)
                    }
                }

                answersToSave.add(answer)
            }

            answerRepository.saveAll(answersToSave)
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}", sessionId, report.overallScore, answersToSave.size)
        } catch (e: Exception) {
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
     * 获取简历的所有面试记录
     */
    fun findByResumeId(resumeId: Long): List<InterviewSessionEntity> {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId)
    }

    /**
     * 删除简历的所有面试会话
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
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSessionBySessionId(sessionId: String) {
        val session = sessionRepository.findBySessionId(sessionId)
            ?: throw BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND)
        sessionRepository.delete(session)
        log.info("已删除面试会话: sessionId={}", sessionId)
    }

    /**
     * 查找未完成的面试会话
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
     * 获取简历的历史提问列表（限制最近的 N 条）
     */
    fun getHistoricalQuestionsByResumeId(resumeId: Long): List<String> {
        val sessions = sessionRepository.findTop10ByResumeIdOrderByCreatedAtDesc(resumeId)
        return sessions
            .mapNotNull { it.questionsJson }
            .filter { it.isNotBlank() }
            .flatMap { json ->
                try {
                    val questions: List<InterviewQuestionVo> = objectMapper.readValue(
                        json,
                        object : TypeReference<List<InterviewQuestionVo>>() {}
                    )
                    questions.filter { !it.isFollowUp }.map { it.question }
                } catch (e: Exception) {
                    log.error("解析历史问题JSON失败", e)
                    emptyList()
                }
            }
            .distinct()
            .take(30)
    }
}

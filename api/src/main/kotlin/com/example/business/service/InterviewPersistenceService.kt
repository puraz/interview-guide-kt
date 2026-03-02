package com.example.business.service

import com.example.business.entity.InterviewAnswerEntity
import com.example.business.entity.InterviewSessionEntity
import com.example.business.entity.ResumeEntity
import com.example.business.entity.answeredAt
import com.example.business.entity.category
import com.example.business.entity.completedAt
import com.example.business.entity.createdAt
import com.example.business.entity.currentQuestionIndex
import com.example.business.entity.evaluateError
import com.example.business.entity.evaluateStatus
import com.example.business.entity.feedback
import com.example.business.entity.id
import com.example.business.entity.improvementsJson
import com.example.business.entity.keyPointsJson
import com.example.business.entity.overallFeedback
import com.example.business.entity.overallScore
import com.example.business.entity.question
import com.example.business.entity.questionIndex
import com.example.business.entity.questionsJson
import com.example.business.entity.referenceAnswer
import com.example.business.entity.referenceAnswersJson
import com.example.business.entity.resume
import com.example.business.entity.resumeText
import com.example.business.entity.score
import com.example.business.entity.session
import com.example.business.entity.sessionId
import com.example.business.entity.status
import com.example.business.entity.strengthsJson
import com.example.business.entity.userAnswer
import com.example.business.enums.AsyncTaskStatus
import com.example.business.repository.InterviewAnswerRepository
import com.example.business.repository.InterviewSessionRepository
import com.example.business.repository.ResumeRepository
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.fasterxml.jackson.core.type.TypeReference
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.desc
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 面试持久化服务
 *
 * 用于面试会话、答案、报告的落库与查询。
 */
@Service
class InterviewPersistenceService(
    private val sessionRepository: InterviewSessionRepository,
    private val answerRepository: InterviewAnswerRepository,
    private val resumeRepository: ResumeRepository
) {
    private val logger = LoggerFactory.getLogger(InterviewPersistenceService::class.java)

    /**
     * 保存新的面试会话
     *
     * @param sessionId 会话ID
     * @param resumeId 简历ID
     * @param resumeText 简历文本
     * @param totalQuestions 题目总数
     * @param questions 问题列表
     * @return 保存后的会话
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSession(
        sessionId: String,
        resumeId: Long,
        resumeText: String,
        totalQuestions: Int,
        questions: List<InterviewSessionService.InterviewQuestionVo>
    ): InterviewSessionEntity {
        val resume = findResumeById(resumeId)
            ?: throw BusinessException("简历不存在")

        if (resume.resumeText.isNullOrBlank() && resumeText.isNotBlank()) {
            // 简历文本缺失时，使用当前输入补全
            resumeRepository.sql.createUpdate(ResumeEntity::class) {
                set(table.resumeText, resumeText)
                where(table.id eq resumeId)
            }.execute()
        }

        val questionsJson = JSONUtil.toJsonStr(questions)
            ?: throw BusinessException("问题列表序列化失败")

        val entity = InterviewSessionEntity {
            this.sessionId = sessionId // 会话ID
            this.resume = ResumeEntity { id = resumeId } // 关联简历
            this.totalQuestions = totalQuestions // 题目总数
            this.currentQuestionIndex = 0 // 当前题索引
            this.status = InterviewSessionEntity.SessionStatus.CREATED // 会话状态
            this.questionsJson = questionsJson // 问题JSON
            this.overallScore = null // 总分
            this.overallFeedback = null // 总体评价
            this.strengthsJson = null // 优势JSON
            this.improvementsJson = null // 改进建议JSON
            this.referenceAnswersJson = null // 参考答案JSON
            this.createdAt = LocalDateTime.now() // 创建时间
            this.completedAt = null // 完成时间
            this.evaluateStatus = null // 评估状态
            this.evaluateError = null // 评估错误
        }

        val saved = sessionRepository.save(entity)
        logger.info("面试会话已保存: sessionId={}, resumeId={}", sessionId, resumeId)
        return saved
    }

    /**
     * 更新会话状态
     *
     * @param sessionId 会话ID
     * @param status 会话状态
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateSessionStatus(sessionId: String, status: InterviewSessionEntity.SessionStatus) {
        sessionRepository.sql.createUpdate(InterviewSessionEntity::class) {
            set(table.status, status)
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED
            ) {
                set(table.completedAt, LocalDateTime.now())
            }
            where(table.sessionId eq sessionId)
        }.execute()
    }

    /**
     * 更新评估状态
     *
     * @param sessionId 会话ID
     * @param status 评估状态
     * @param error 评估错误信息
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateEvaluateStatus(sessionId: String, status: AsyncTaskStatus, error: String?) {
        val safeError = error?.let { if (it.length > 500) it.substring(0, 500) else it }
        sessionRepository.sql.createUpdate(InterviewSessionEntity::class) {
            set(table.evaluateStatus, status)
            set(table.evaluateError, safeError)
            where(table.sessionId eq sessionId)
        }.execute()
    }

    /**
     * 更新当前题索引
     *
     * @param sessionId 会话ID
     * @param index 当前题索引
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateCurrentQuestionIndex(sessionId: String, index: Int) {
        sessionRepository.sql.createUpdate(InterviewSessionEntity::class) {
            set(table.currentQuestionIndex, index)
            set(table.status, InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            where(table.sessionId eq sessionId)
        }.execute()
    }

    /**
     * 保存面试答案
     *
     * @param sessionId 会话ID
     * @param questionIndex 问题索引
     * @param question 问题内容
     * @param category 问题类别
     * @param userAnswer 用户回答
     * @param score 得分
     * @param feedback 反馈
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveAnswer(
        sessionId: String,
        questionIndex: Int,
        question: String,
        category: String,
        userAnswer: String,
        score: Int,
        feedback: String?
    ) {
        val sessionDbId = getSessionDbId(sessionId) ?: throw BusinessException("面试会话不存在")

        val existing = answerRepository.sql.createQuery(InterviewAnswerEntity::class) {
            where(table.session.id eq sessionDbId)
            where(table.questionIndex eq questionIndex)
            select(table)
        }.fetchOneOrNull()

        if (existing == null) {
            val entity = InterviewAnswerEntity {
                this.session = InterviewSessionEntity { id = sessionDbId } // 关联会话
                this.questionIndex = questionIndex // 问题索引
                this.question = question // 问题内容
                this.category = category // 问题类别
                this.userAnswer = userAnswer // 用户回答
                this.score = score // 得分
                this.feedback = feedback // 反馈
                this.referenceAnswer = null // 参考答案
                this.keyPointsJson = null // 关键点JSON
                this.answeredAt = LocalDateTime.now() // 回答时间
            }
            answerRepository.save(entity)
        } else {
            answerRepository.sql.createUpdate(InterviewAnswerEntity::class) {
                set(table.question, question)
                set(table.category, category)
                set(table.userAnswer, userAnswer)
                set(table.score, score)
                set(table.feedback, feedback)
                set(table.answeredAt, LocalDateTime.now())
                where(table.id eq existing.id)
            }.execute()
        }
    }

    /**
     * 保存面试报告
     *
     * @param sessionId 会话ID
     * @param report 面试报告
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveReport(sessionId: String, report: AnswerEvaluationService.InterviewReportVo) {
        val strengthsJson = JSONUtil.toJsonStr(report.strengths)
        val improvementsJson = JSONUtil.toJsonStr(report.improvements)
        val referenceAnswersJson = JSONUtil.toJsonStr(report.referenceAnswers)

        sessionRepository.sql.createUpdate(InterviewSessionEntity::class) {
            set(table.overallScore, report.overallScore)
            set(table.overallFeedback, report.overallFeedback)
            set(table.strengthsJson, strengthsJson)
            set(table.improvementsJson, improvementsJson)
            set(table.referenceAnswersJson, referenceAnswersJson)
            set(table.status, InterviewSessionEntity.SessionStatus.EVALUATED)
            set(table.completedAt, LocalDateTime.now())
            where(table.sessionId eq sessionId)
        }.execute()

        val sessionDbId = getSessionDbId(sessionId) ?: throw BusinessException("面试会话不存在")
        val existingAnswers = answerRepository.sql.createQuery(InterviewAnswerEntity::class) {
            where(table.session.id eq sessionDbId)
            select(table)
        }.execute()
        val answerMap = existingAnswers.associateBy { it.questionIndex }
        val refAnswerMap = report.referenceAnswers.associateBy { it.questionIndex }

        for (detail in report.questionDetails) {
            val existing = answerMap[detail.questionIndex]
            val refAnswer = refAnswerMap[detail.questionIndex]
            val keyPointsJson = refAnswer?.let { JSONUtil.toJsonStr(it.keyPoints) }
            if (existing == null) {
                val created = InterviewAnswerEntity {
                    this.session = InterviewSessionEntity { id = sessionDbId } // 关联会话
                    this.questionIndex = detail.questionIndex // 问题索引
                    this.question = detail.question // 问题内容
                    this.category = detail.category // 问题类别
                    this.userAnswer = detail.userAnswer // 用户回答
                    this.score = detail.score // 得分
                    this.feedback = detail.feedback // 反馈
                    this.referenceAnswer = refAnswer?.referenceAnswer // 参考答案
                    this.keyPointsJson = keyPointsJson // 关键点JSON
                    this.answeredAt = LocalDateTime.now() // 回答时间
                }
                answerRepository.save(created)
            } else {
                answerRepository.sql.createUpdate(InterviewAnswerEntity::class) {
                    set(table.score, detail.score)
                    set(table.feedback, detail.feedback)
                    set(table.referenceAnswer, refAnswer?.referenceAnswer)
                    set(table.keyPointsJson, keyPointsJson)
                    where(table.id eq existing.id)
                }.execute()
            }
        }
    }

    /**
     * 根据会话ID查询会话
     *
     * @param sessionId 会话ID
     * @return 会话实体
     */
    fun findBySessionId(sessionId: String): InterviewSessionEntity? {
        return sessionRepository.sql.createQuery(InterviewSessionEntity::class) {
            where(table.sessionId eq sessionId)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * 查询未完成会话
     *
     * @param resumeId 简历ID
     * @return 未完成会话
     */
    fun findUnfinishedSession(resumeId: Long): InterviewSessionEntity? {
        return sessionRepository.sql.createQuery(InterviewSessionEntity::class) {
            where(table.resume.id eq resumeId)
            where(
                org.babyfish.jimmer.sql.kt.ast.expression.or(
                    table.status eq InterviewSessionEntity.SessionStatus.CREATED,
                    table.status eq InterviewSessionEntity.SessionStatus.IN_PROGRESS
                )
            )
            orderBy(table.createdAt.desc())
            select(table)
        }.fetchFirstOrNull()
    }

    /**
     * 查询会话答案列表
     *
     * @param sessionId 会话ID
     * @return 答案列表
     */
    fun findAnswersBySessionId(sessionId: String): List<InterviewAnswerEntity> {
        val sessionDbId = getSessionDbId(sessionId) ?: return emptyList()
        return answerRepository.sql.createQuery(InterviewAnswerEntity::class) {
            where(table.session.id eq sessionDbId)
            orderBy(table.questionIndex.asc())
            select(table)
        }.execute()
    }

    /**
     * 获取历史问题列表（最多 30 条）
     *
     * @param resumeId 简历ID
     * @return 历史问题列表
     */
    fun getHistoricalQuestionsByResumeId(resumeId: Long): List<String> {
        val sessions = sessionRepository.sql.createQuery(InterviewSessionEntity::class) {
            where(table.resume.id eq resumeId)
            orderBy(table.createdAt.desc())
            select(table.questionsJson)
        }.execute().take(10)

        val questions = mutableListOf<String>()
        for (json in sessions) {
            if (json.isNullOrBlank()) {
                continue
            }
            val list = JSONUtil.parseObject(json, object : TypeReference<List<InterviewSessionService.InterviewQuestionVo>>() {})
                ?: continue
            list.filter { !it.isFollowUp }
                .mapNotNull { it.question }
                .filter { it.isNotBlank() }
                .forEach { questions.add(it) }
        }
        return questions.distinct().take(30)
    }

    /**
     * 删除会话及其答案
     *
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteSessionBySessionId(sessionId: String) {
        val sessionDbId = getSessionDbId(sessionId) ?: return
        answerRepository.sql.createDelete(InterviewAnswerEntity::class) {
            where(table.session.id eq sessionDbId)
        }.execute()
        sessionRepository.sql.createDelete(InterviewSessionEntity::class) {
            where(table.id eq sessionDbId)
        }.execute()
    }

    /**
     * 根据会话ID获取简历文本
     *
     * @param sessionId 会话ID
     * @return 简历文本
     */
    fun getResumeTextBySessionId(sessionId: String): String? {
        val resumeId = sessionRepository.sql.createQuery(InterviewSessionEntity::class) {
            where(table.sessionId eq sessionId)
            select(table.resume.id)
        }.fetchOneOrNull() ?: return null

        return resumeRepository.sql.createQuery(ResumeEntity::class) {
            where(table.id eq resumeId)
            select(table.resumeText)
        }.fetchOneOrNull()
    }

    private fun findResumeById(resumeId: Long): ResumeEntity? {
        return resumeRepository.sql.createQuery(ResumeEntity::class) {
            where(table.id eq resumeId)
            select(table)
        }.fetchOneOrNull()
    }

    private fun getSessionDbId(sessionId: String): Long? {
        return sessionRepository.sql.createQuery(InterviewSessionEntity::class) {
            where(table.sessionId eq sessionId)
            select(table.id)
        }.fetchOneOrNull()
    }
}

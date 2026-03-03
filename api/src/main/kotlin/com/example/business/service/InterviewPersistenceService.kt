package com.example.business.service

import com.example.business.entity.InterviewAnswerEntity
import com.example.business.entity.InterviewSessionEntity
import com.example.business.entity.ResumeEntity
import com.example.business.enums.AsyncTaskStatus
import com.example.business.repository.InterviewAnswerRepository
import com.example.business.repository.InterviewSessionRepository
import com.example.business.repository.ResumeRepository
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.fasterxml.jackson.core.type.TypeReference
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
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

    @PersistenceContext
    private lateinit var entityManager: EntityManager

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
            resume.resumeText = resumeText
            resumeRepository.save(resume)
        }

        val questionsJson = JSONUtil.toJsonStr(questions)
            ?: throw BusinessException("问题列表序列化失败")

        val entity = InterviewSessionEntity().apply {
            this.sessionId = sessionId // 会话ID
            this.resume = resume // 关联简历
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
        val now = LocalDateTime.now()
        if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
            status == InterviewSessionEntity.SessionStatus.EVALUATED
        ) {
            entityManager.createQuery(
                """
                UPDATE InterviewSessionEntity s
                SET s.status = :status,
                    s.completedAt = :completedAt
                WHERE s.sessionId = :sessionId
                """.trimIndent()
            )
                .setParameter("status", status)
                .setParameter("completedAt", now)
                .setParameter("sessionId", sessionId)
                .executeUpdate()
        } else {
            entityManager.createQuery(
                """
                UPDATE InterviewSessionEntity s
                SET s.status = :status
                WHERE s.sessionId = :sessionId
                """.trimIndent()
            )
                .setParameter("status", status)
                .setParameter("sessionId", sessionId)
                .executeUpdate()
        }
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
        entityManager.createQuery(
            """
            UPDATE InterviewSessionEntity s
            SET s.evaluateStatus = :status,
                s.evaluateError = :error
            WHERE s.sessionId = :sessionId
            """.trimIndent()
        )
            .setParameter("status", status)
            .setParameter("error", safeError)
            .setParameter("sessionId", sessionId)
            .executeUpdate()
    }

    /**
     * 更新当前题索引
     *
     * @param sessionId 会话ID
     * @param index 当前题索引
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateCurrentQuestionIndex(sessionId: String, index: Int) {
        entityManager.createQuery(
            """
            UPDATE InterviewSessionEntity s
            SET s.currentQuestionIndex = :index,
                s.status = :status
            WHERE s.sessionId = :sessionId
            """.trimIndent()
        )
            .setParameter("index", index)
            .setParameter("status", InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            .setParameter("sessionId", sessionId)
            .executeUpdate()
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
        val session = findBySessionId(sessionId) ?: throw BusinessException("面试会话不存在")

        val existing = entityManager.createQuery(
            """
            SELECT a
            FROM InterviewAnswerEntity a
            WHERE a.session.id = :sessionDbId
              AND a.questionIndex = :questionIndex
            """.trimIndent(),
            InterviewAnswerEntity::class.java
        )
            .setParameter("sessionDbId", session.id)
            .setParameter("questionIndex", questionIndex)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

        if (existing == null) {
            val entity = InterviewAnswerEntity().apply {
                this.session = session // 关联会话
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
            existing.question = question // 更新问题内容
            existing.category = category // 更新问题类别
            existing.userAnswer = userAnswer // 更新用户回答
            existing.score = score // 更新得分
            existing.feedback = feedback // 更新反馈
            existing.answeredAt = LocalDateTime.now() // 更新时间
            answerRepository.save(existing)
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
        val session = findBySessionId(sessionId) ?: throw BusinessException("面试会话不存在")

        val strengthsJson = JSONUtil.toJsonStr(report.strengths)
        val improvementsJson = JSONUtil.toJsonStr(report.improvements)
        val referenceAnswersJson = JSONUtil.toJsonStr(report.referenceAnswers)

        session.overallScore = report.overallScore // 总分
        session.overallFeedback = report.overallFeedback // 总体评价
        session.strengthsJson = strengthsJson // 优势JSON
        session.improvementsJson = improvementsJson // 改进建议JSON
        session.referenceAnswersJson = referenceAnswersJson // 参考答案JSON
        session.status = InterviewSessionEntity.SessionStatus.EVALUATED // 评估完成状态
        session.completedAt = LocalDateTime.now() // 完成时间

        val existingAnswers = entityManager.createQuery(
            """
            SELECT a
            FROM InterviewAnswerEntity a
            WHERE a.session.id = :sessionDbId
            """.trimIndent(),
            InterviewAnswerEntity::class.java
        )
            .setParameter("sessionDbId", session.id)
            .resultList

        val answerMap = existingAnswers.associateBy { it.questionIndex }
        val refAnswerMap = report.referenceAnswers.associateBy { it.questionIndex }

        for (detail in report.questionDetails) {
            val existing = answerMap[detail.questionIndex]
            val refAnswer = refAnswerMap[detail.questionIndex]
            val keyPointsJson = refAnswer?.let { JSONUtil.toJsonStr(it.keyPoints) }
            if (existing == null) {
                val created = InterviewAnswerEntity().apply {
                    this.session = session // 关联会话
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
                existing.score = detail.score // 更新得分
                existing.feedback = detail.feedback // 更新反馈
                existing.referenceAnswer = refAnswer?.referenceAnswer // 更新参考答案
                existing.keyPointsJson = keyPointsJson // 更新关键点
                answerRepository.save(existing)
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
        return entityManager.createQuery(
            """
            SELECT s
            FROM InterviewSessionEntity s
            WHERE s.sessionId = :sessionId
            """.trimIndent(),
            InterviewSessionEntity::class.java
        )
            .setParameter("sessionId", sessionId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
    }

    /**
     * 查询未完成会话
     *
     * @param resumeId 简历ID
     * @return 未完成会话
     */
    fun findUnfinishedSession(resumeId: Long): InterviewSessionEntity? {
        return entityManager.createQuery(
            """
            SELECT s
            FROM InterviewSessionEntity s
            WHERE s.resume.id = :resumeId
              AND s.status IN (:statusList)
            ORDER BY s.createdAt DESC
            """.trimIndent(),
            InterviewSessionEntity::class.java
        )
            .setParameter(
                "statusList",
                listOf(
                    InterviewSessionEntity.SessionStatus.CREATED,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS
                )
            )
            .setParameter("resumeId", resumeId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
    }

    /**
     * 查询会话答案列表
     *
     * @param sessionId 会话ID
     * @return 答案列表
     */
    fun findAnswersBySessionId(sessionId: String): List<InterviewAnswerEntity> {
        return entityManager.createQuery(
            """
            SELECT a
            FROM InterviewAnswerEntity a
            WHERE a.session.sessionId = :sessionId
            ORDER BY a.questionIndex ASC
            """.trimIndent(),
            InterviewAnswerEntity::class.java
        )
            .setParameter("sessionId", sessionId)
            .resultList
    }

    /**
     * 获取历史问题列表（最多 30 条）
     *
     * @param resumeId 简历ID
     * @return 历史问题列表
     */
    fun getHistoricalQuestionsByResumeId(resumeId: Long): List<String> {
        val sessions = entityManager.createQuery(
            """
            SELECT s.questionsJson
            FROM InterviewSessionEntity s
            WHERE s.resume.id = :resumeId
            ORDER BY s.createdAt DESC
            """.trimIndent(),
            String::class.java
        )
            .setParameter("resumeId", resumeId)
            .setMaxResults(10)
            .resultList

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
        val sessionDbId = entityManager.createQuery(
            "SELECT s.id FROM InterviewSessionEntity s WHERE s.sessionId = :sessionId",
            Long::class.java
        )
            .setParameter("sessionId", sessionId)
            .setMaxResults(1)
            .resultList
            .firstOrNull() ?: return

        entityManager.createQuery(
            "DELETE FROM InterviewAnswerEntity a WHERE a.session.id = :sessionDbId"
        )
            .setParameter("sessionDbId", sessionDbId)
            .executeUpdate()
        entityManager.createQuery(
            "DELETE FROM InterviewSessionEntity s WHERE s.id = :sessionDbId"
        )
            .setParameter("sessionDbId", sessionDbId)
            .executeUpdate()
    }

    /**
     * 根据会话ID获取简历文本
     *
     * @param sessionId 会话ID
     * @return 简历文本
     */
    fun getResumeTextBySessionId(sessionId: String): String? {
        return entityManager.createQuery(
            """
            SELECT r.resumeText
            FROM InterviewSessionEntity s
            JOIN s.resume r
            WHERE s.sessionId = :sessionId
            """.trimIndent(),
            String::class.java
        )
            .setParameter("sessionId", sessionId)
            .setMaxResults(1)
            .resultList
            .firstOrNull()
    }

    /**
     * 根据简历ID查询简历
     *
     * @param resumeId 简历ID
     * @return 简历实体
     */
    private fun findResumeById(resumeId: Long): ResumeEntity? {
        return resumeRepository.findById(resumeId).orElse(null)
    }
}

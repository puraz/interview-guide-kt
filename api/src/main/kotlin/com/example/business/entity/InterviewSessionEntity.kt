package com.example.business.entity

import com.example.business.enums.AsyncTaskStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 面试会话实体
 */
@Entity
@Table(name = "interview_sessions")
open class InterviewSessionEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 会话ID(UUID)
     */
    @Column(name = "session_id", nullable = false, length = 36)
    open lateinit var sessionId: String

    /**
     * 关联的简历
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    open lateinit var resume: ResumeEntity

    /**
     * 问题总数
     */
    @Column(name = "total_questions")
    open var totalQuestions: Int? = null

    /**
     * 当前问题索引
     */
    @Column(name = "current_question_index")
    open var currentQuestionIndex: Int? = null

    /**
     * 会话状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    open var status: SessionStatus? = null

    /**
     * 问题列表(JSON)
     */
    @Column(name = "questions_json", columnDefinition = "TEXT")
    open var questionsJson: String? = null

    /**
     * 总分(0-100)
     */
    @Column(name = "overall_score")
    open var overallScore: Int? = null

    /**
     * 总体评价
     */
    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    open var overallFeedback: String? = null

    /**
     * 优势(JSON)
     */
    @Column(name = "strengths_json", columnDefinition = "TEXT")
    open var strengthsJson: String? = null

    /**
     * 改进建议(JSON)
     */
    @Column(name = "improvements_json", columnDefinition = "TEXT")
    open var improvementsJson: String? = null

    /**
     * 参考答案(JSON)
     */
    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    open var referenceAnswersJson: String? = null

    /**
     * 面试答案记录
     */
    @OneToMany(mappedBy = "session", fetch = FetchType.LAZY)
    open var answers: MutableList<InterviewAnswerEntity> = mutableListOf()

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    open lateinit var createdAt: LocalDateTime

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    open var completedAt: LocalDateTime? = null

    /**
     * 评估状态(异步评估)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluate_status")
    open var evaluateStatus: AsyncTaskStatus? = null

    /**
     * 评估错误信息
     */
    @Column(name = "evaluate_error")
    open var evaluateError: String? = null

    /**
     * 面试会话状态枚举
     */
    enum class SessionStatus {
        CREATED, // 会话已创建
        IN_PROGRESS, // 面试进行中
        COMPLETED, // 面试已完成
        EVALUATED // 已生成评估报告
    }
}

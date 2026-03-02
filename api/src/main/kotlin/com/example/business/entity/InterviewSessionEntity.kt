package com.example.business.entity

import com.example.business.enums.AsyncTaskStatus
import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinColumn
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.OneToMany
import org.babyfish.jimmer.sql.Table
import java.time.LocalDateTime

/**
 * 面试会话实体
 */
@Entity
@Table(name = "interview_sessions")
interface InterviewSessionEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 会话ID(UUID)
     */
    @Column(name = "session_id")
    val sessionId: String

    /**
     * 关联的简历
     */
    @ManyToOne
    @JoinColumn(name = "resume_id")
    val resume: ResumeEntity

    /**
     * 问题总数
     */
    @Column(name = "total_questions")
    val totalQuestions: Int?

    /**
     * 当前问题索引
     */
    @Column(name = "current_question_index")
    val currentQuestionIndex: Int?

    /**
     * 会话状态
     */
    @Column(name = "status")
    val status: SessionStatus?

    /**
     * 问题列表(JSON)
     */
    @Column(name = "questions_json", sqlType = "TEXT")
    val questionsJson: String?

    /**
     * 总分(0-100)
     */
    @Column(name = "overall_score")
    val overallScore: Int?

    /**
     * 总体评价
     */
    @Column(name = "overall_feedback", sqlType = "TEXT")
    val overallFeedback: String?

    /**
     * 优势(JSON)
     */
    @Column(name = "strengths_json", sqlType = "TEXT")
    val strengthsJson: String?

    /**
     * 改进建议(JSON)
     */
    @Column(name = "improvements_json", sqlType = "TEXT")
    val improvementsJson: String?

    /**
     * 参考答案(JSON)
     */
    @Column(name = "reference_answers_json", sqlType = "TEXT")
    val referenceAnswersJson: String?

    /**
     * 面试答案记录
     */
    @OneToMany(mappedBy = "session")
    val answers: List<InterviewAnswerEntity>

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    val createdAt: LocalDateTime

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    val completedAt: LocalDateTime?

    /**
     * 评估状态(异步评估)
     */
    @Column(name = "evaluate_status")
    val evaluateStatus: AsyncTaskStatus?

    /**
     * 评估错误信息
     */
    @Column(name = "evaluate_error")
    val evaluateError: String?

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

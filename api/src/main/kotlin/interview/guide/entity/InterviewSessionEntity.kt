package interview.guide.entity

import interview.guide.common.model.AsyncTaskStatus
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 面试会话实体
 */
@Entity
@Table(
    name = "interview_sessions",
    indexes = [
        Index(name = "idx_interview_session_resume_created", columnList = "resume_id,created_at"),
        Index(name = "idx_interview_session_resume_status_created", columnList = "resume_id,status,created_at")
    ]
)
open class InterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @Column(nullable = false, unique = true, length = 36)
    var sessionId: String? = null // 会话ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    var resume: ResumeEntity? = null // 关联简历

    var totalQuestions: Int? = null // 问题总数

    var currentQuestionIndex: Int? = 0 // 当前问题索引

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var status: SessionStatus? = SessionStatus.CREATED // 会话状态

    @Column(columnDefinition = "TEXT")
    var questionsJson: String? = null // 问题列表 JSON

    var overallScore: Int? = null // 总分

    @Column(columnDefinition = "TEXT")
    var overallFeedback: String? = null // 总体评价

    @Column(columnDefinition = "TEXT")
    var strengthsJson: String? = null // 优势 JSON

    @Column(columnDefinition = "TEXT")
    var improvementsJson: String? = null // 改进建议 JSON

    @Column(columnDefinition = "TEXT")
    var referenceAnswersJson: String? = null // 参考答案 JSON

    @OneToMany(mappedBy = "session", cascade = [CascadeType.ALL], orphanRemoval = true)
    var answers: MutableList<InterviewAnswerEntity>? = mutableListOf() // 面试答案记录

    @Column(nullable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    var completedAt: LocalDateTime? = null // 完成时间

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var evaluateStatus: AsyncTaskStatus? = null // 评估状态

    @Column(length = 500)
    var evaluateError: String? = null // 评估错误信息

    enum class SessionStatus {
        CREATED, // 会话已创建
        IN_PROGRESS, // 面试进行中
        COMPLETED, // 面试已完成
        EVALUATED // 已生成评估报告
    }

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }
}

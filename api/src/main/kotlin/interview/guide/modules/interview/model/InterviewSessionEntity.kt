package interview.guide.modules.interview.model

import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.resume.model.ResumeEntity
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
        Index(name = "idx_interview_session_resume_status_created", columnList = "resume_id,status,created_at"),
        Index(name = "idx_interview_session_skill_created", columnList = "skillId,createdAt")
    ]
)
open class InterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    // 会话ID (UUID)
    @Column(nullable = false, unique = true, length = 36)
    var sessionId: String? = null

    // 创建请求幂等键（仅文本面试创建链路使用）
    @Column(name = "request_id", unique = true, length = 64)
    var requestId: String? = null

    // 面试主题
    @Column(length = 64)
    var skillId: String? = "java-backend"

    // 难度级别 (junior / mid / senior)
    @Column(length = 16)
    var difficulty: String? = "mid"

    // 简历ID（直接映射FK列，避免LAZY加载触发额外查询）
    @Column(name = "resume_id", insertable = false, updatable = false)
    var resumeId: Long? = null

    // 关联的简历（可选，支持无简历通用面试）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    var resume: ResumeEntity? = null

    // 问题总数
    var totalQuestions: Int? = null

    // 当前问题索引
    var currentQuestionIndex: Int? = 0

    // 会话状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var status: SessionStatus? = SessionStatus.CREATED

    // 问题列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    var questionsJson: String? = null

    // 总分 (0-100)
    var overallScore: Int? = null

    // 总体评价
    @Column(columnDefinition = "TEXT")
    var overallFeedback: String? = null

    // 优势 (JSON)
    @Column(columnDefinition = "TEXT")
    var strengthsJson: String? = null

    // 改进建议 (JSON)
    @Column(columnDefinition = "TEXT")
    var improvementsJson: String? = null

    // 参考答案 (JSON)
    @Column(columnDefinition = "TEXT")
    var referenceAnswersJson: String? = null

    // 面试答案记录
    @OneToMany(mappedBy = "session", cascade = [CascadeType.ALL], orphanRemoval = true)
    var answers: MutableList<InterviewAnswerEntity> = mutableListOf()

    // 创建时间
    @Column(nullable = false)
    var createdAt: LocalDateTime? = null

    // 完成时间
    var completedAt: LocalDateTime? = null

    // 评估状态（异步评估）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var evaluateStatus: AsyncTaskStatus? = null

    // 评估错误信息
    @Column(length = 500)
    var evaluateError: String? = null

    // LLM提供商
    @Column(length = 50)
    var llmProvider: String? = "dashscope"

    // 会话来源：NORMAL / KNOWLEDGE_BASE
    @Column(length = 32)
    var sourceType: String? = "NORMAL"

    // 知识库面试来源知识库 ID
    var knowledgeBaseId: Long? = null

    // 知识库面试方向（来自题库 category，普通面试为 null）
    @Column(length = 64)
    var interviewCategory: String? = null

    /**
     * 会话状态
     */
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

    /**
     * 追加答案并建立双向关联
     */
    fun addAnswer(answer: InterviewAnswerEntity) {
        answers.add(answer)
        answer.session = this
    }
}

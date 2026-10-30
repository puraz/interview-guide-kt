package interview.guide.modules.voiceinterview.model

import interview.guide.common.model.AsyncTaskStatus
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 语音面试会话实体
 */
@Entity
@Table(name = "voice_interview_sessions")
open class VoiceInterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @Column(name = "user_id")
    var userId: String? = null // 用户ID

    @Column(name = "role_type", nullable = false)
    var roleType: String? = null // 面试角色类型（模板 ID）

    @Column(name = "skill_id", length = 64)
    var skillId: String? = "java-backend" // 技能模板 ID

    @Column(name = "difficulty", length = 16)
    var difficulty: String? = "mid" // 面试难度：junior/mid/senior

    @Column(name = "custom_jd_text", columnDefinition = "TEXT")
    var customJdText: String? = null // 自定义 JD 文本

    @Column(name = "resume_id")
    var resumeId: Long? = null // 关联简历ID

    @Column(name = "intro_enabled")
    var introEnabled: Boolean? = true // 是否启用自我介绍阶段

    @Column(name = "tech_enabled")
    var techEnabled: Boolean? = true // 是否启用技术阶段

    @Column(name = "project_enabled")
    var projectEnabled: Boolean? = true // 是否启用项目阶段

    @Column(name = "hr_enabled")
    var hrEnabled: Boolean? = true // 是否启用 HR 阶段

    @Column(name = "llm_provider", length = 50)
    var llmProvider: String? = "dashscope" // 使用的 LLM Provider

    @Column(name = "current_phase")
    @Enumerated(EnumType.STRING)
    var currentPhase: InterviewPhase? = null // 当前面试阶段

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: VoiceInterviewSessionStatus? = VoiceInterviewSessionStatus.IN_PROGRESS // 会话状态

    @Column(name = "planned_duration")
    var plannedDuration: Int? = 30 // 计划时长（分钟）

    @Column(name = "actual_duration")
    var actualDuration: Int? = null // 实际时长（秒）

    @Column(name = "start_time")
    var startTime: LocalDateTime? = null // 开始时间

    @Column(name = "end_time")
    var endTime: LocalDateTime? = null // 结束时间

    @Column(name = "created_at")
    var createdAt: LocalDateTime? = null // 创建时间

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null // 更新时间

    @Column(name = "paused_at")
    var pausedAt: LocalDateTime? = null // 暂停时间

    @Column(name = "resumed_at")
    var resumedAt: LocalDateTime? = null // 恢复时间

    @Column(name = "evaluate_status")
    @Enumerated(EnumType.STRING)
    var evaluateStatus: AsyncTaskStatus? = null // 评估任务状态

    @Column(name = "evaluate_error", length = 500)
    var evaluateError: String? = null // 评估错误信息

    /**
     * 更新前自动刷新更新时间
     */
    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * 首次持久化时初始化创建/更新/开始时间
     */
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
        startTime = LocalDateTime.now()
    }

    /**
     * 面试阶段
     */
    enum class InterviewPhase {
        INTRO, // 自我介绍
        TECH, // 技术
        PROJECT, // 项目
        HR, // HR
        COMPLETED // 已完成
    }
}

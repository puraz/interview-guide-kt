package interview.guide.modules.voiceinterview.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Voice Interview Evaluation Entity
 * 语音面试评估实体
 *
 * Stores evaluation results in a format aligned with text-based interviews:
 * per-question evaluations, overall feedback, strengths, improvements, and reference answers.
 * All structured data (arrays/objects) is stored as JSON TEXT columns.
 */
@Entity
@Table(name = "voice_interview_evaluations")
open class VoiceInterviewEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @Column(name = "session_id", unique = true)
    var sessionId: Long? = null // 会话ID（一对一）

    @Column(name = "overall_score")
    var overallScore: Int? = null // 总体评分

    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    var overallFeedback: String? = null // 总体评价

    @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
    var questionEvaluationsJson: String? = null // 逐题评估 JSON

    @Column(name = "strengths_json", columnDefinition = "TEXT")
    var strengthsJson: String? = null // 优势 JSON

    @Column(name = "improvements_json", columnDefinition = "TEXT")
    var improvementsJson: String? = null // 改进建议 JSON

    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    var referenceAnswersJson: String? = null // 参考答案 JSON

    @Column(name = "interviewer_role")
    var interviewerRole: String? = null // 面试官角色

    @Column(name = "interview_date")
    var interviewDate: LocalDateTime? = null // 面试日期

    @Column(name = "created_at")
    var createdAt: LocalDateTime? = null // 创建时间

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }
}

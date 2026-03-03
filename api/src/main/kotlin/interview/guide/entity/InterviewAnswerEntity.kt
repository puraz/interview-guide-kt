package interview.guide.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 面试答案实体
 */
@Entity
@Table(
    name = "interview_answers",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_interview_answer_session_question",
            columnNames = ["session_id", "question_index"]
        )
    ],
    indexes = [
        Index(name = "idx_interview_answer_session_question", columnList = "session_id,question_index")
    ]
)
open class InterviewAnswerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: InterviewSessionEntity? = null // 关联会话

    @Column(name = "question_index")
    var questionIndex: Int? = null // 问题索引

    @Column(columnDefinition = "TEXT")
    var question: String? = null // 问题内容

    var category: String? = null // 问题类别

    @Column(columnDefinition = "TEXT")
    var userAnswer: String? = null // 用户答案

    var score: Int? = null // 得分

    @Column(columnDefinition = "TEXT")
    var feedback: String? = null // 反馈

    @Column(columnDefinition = "TEXT")
    var referenceAnswer: String? = null // 参考答案

    @Column(columnDefinition = "TEXT")
    var keyPointsJson: String? = null // 关键点 JSON

    @Column(nullable = false)
    var answeredAt: LocalDateTime? = null // 回答时间

    @PrePersist
    fun onCreate() {
        answeredAt = LocalDateTime.now()
    }
}

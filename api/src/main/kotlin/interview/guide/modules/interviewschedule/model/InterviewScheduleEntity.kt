package interview.guide.modules.interviewschedule.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 面试日程实体
 */
@Entity
@Table(name = "interview_schedule")
open class InterviewScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @Column(name = "company_name", nullable = false)
    var companyName: String? = null // 公司名称

    @Column(nullable = false)
    var position: String? = null // 应聘岗位

    @Column(name = "interview_time", nullable = false)
    var interviewTime: LocalDateTime? = null // 面试时间

    @Column(name = "interview_type")
    var interviewType: String? = null // 面试形式：ONSITE, VIDEO, PHONE

    @Column(name = "meeting_link", columnDefinition = "TEXT")
    var meetingLink: String? = null // 会议链接

    @Column(name = "round_number")
    var roundNumber: Int? = 1 // 面试轮次

    var interviewer: String? = null // 面试官

    @Column(columnDefinition = "TEXT")
    var notes: String? = null // 备注

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InterviewStatus = InterviewStatus.PENDING // 面试状态

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null // 更新时间

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

package interview.guide.modules.interviewschedule.model

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

/**
 * 面试日程返回对象
 */
data class InterviewScheduleVo(
    var id: Long? = null, // 面试记录ID
    var companyName: String? = null, // 公司名称
    var position: String? = null, // 应聘岗位

    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @get:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    var interviewTime: LocalDateTime? = null, // 面试时间

    var interviewType: String? = null, // 面试形式
    var meetingLink: String? = null, // 会议链接
    var roundNumber: Int? = null, // 面试轮次
    var interviewer: String? = null, // 面试官
    var notes: String? = null, // 备注
    var status: InterviewStatus? = null, // 面试状态
    var createdAt: LocalDateTime? = null, // 创建时间
    var updatedAt: LocalDateTime? = null // 更新时间
)

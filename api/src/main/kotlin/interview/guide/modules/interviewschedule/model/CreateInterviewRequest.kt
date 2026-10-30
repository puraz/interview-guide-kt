package interview.guide.modules.interviewschedule.model

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * 创建/更新面试记录请求
 */
data class CreateInterviewRequest(
    @field:NotBlank(message = "公司名称不能为空")
    var companyName: String? = null, // 公司名称

    @field:NotBlank(message = "岗位不能为空")
    var position: String? = null, // 应聘岗位

    @field:NotNull(message = "面试时间不能为空")
    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    var interviewTime: LocalDateTime? = null, // 面试时间

    var interviewType: String? = null, // 面试形式：ONSITE, VIDEO, PHONE

    var meetingLink: String? = null, // 会议链接

    var roundNumber: Int? = 1, // 面试轮次

    var interviewer: String? = null, // 面试官

    var notes: String? = null // 备注
)

package interview.guide.modules.interviewschedule.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity
import interview.guide.modules.interviewschedule.model.InterviewScheduleVo
import interview.guide.modules.interviewschedule.model.InterviewStatus
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 面试日程服务
 */
@Service
class InterviewScheduleService(
    private val repository: InterviewScheduleRepository // 面试日程仓库
) {

    companion object {
        /**
         * 创建/更新时可复制的字段
         */
        private val COPYABLE_FIELDS: Array<String> = arrayOf(
            "companyName", "position", "interviewTime", "interviewType",
            "meetingLink", "roundNumber", "interviewer", "notes"
        )
    }

    /**
     * 创建面试记录
     */
    @Transactional
    fun create(request: CreateInterviewRequest): InterviewScheduleVo {
        val entity = InterviewScheduleEntity()
        copyRequestToEntity(request, entity)
        entity.status = InterviewStatus.PENDING

        return toDTO(repository.save(entity))
    }

    /**
     * 更新面试记录（不修改 id 与 status）
     */
    @Transactional
    fun update(id: Long, request: CreateInterviewRequest): InterviewScheduleVo {
        val entity = getByIdOrThrow(id)
        copyRequestToEntity(request, entity)
        return toDTO(repository.save(entity))
    }

    /**
     * 删除面试记录
     */
    @Transactional
    fun delete(id: Long) {
        repository.deleteById(id)
    }

    /**
     * 更新面试状态
     */
    @Transactional
    fun updateStatus(id: Long, status: InterviewStatus): InterviewScheduleVo {
        val entity = getByIdOrThrow(id)
        entity.status = status
        return toDTO(repository.save(entity))
    }

    /**
     * 查询面试记录列表（时间区间优先，其次状态过滤）
     */
    fun getAll(status: String?, start: LocalDateTime?, end: LocalDateTime?): List<InterviewScheduleVo> {
        val entities: List<InterviewScheduleEntity> = when {
            start != null && end != null -> repository.findByInterviewTimeBetween(start, end)
            status != null -> repository.findByStatus(InterviewStatus.valueOf(status))
            else -> repository.findAll()
        }

        return entities.map { toDTO(it) }
    }

    /**
     * 根据 ID 查询面试记录
     */
    fun getById(id: Long): InterviewScheduleVo {
        return toDTO(getByIdOrThrow(id))
    }

    /**
     * 根据 ID 获取实体，不存在则抛出业务异常
     */
    private fun getByIdOrThrow(id: Long): InterviewScheduleEntity {
        return repository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: $id") }
    }

    /**
     * 将请求字段复制到实体（对应 Java 的 BeanUtils.copyProperties）
     */
    private fun copyRequestToEntity(request: CreateInterviewRequest, entity: InterviewScheduleEntity) {
        entity.companyName = request.companyName
        entity.position = request.position
        entity.interviewTime = request.interviewTime
        entity.interviewType = request.interviewType
        entity.meetingLink = request.meetingLink
        entity.roundNumber = request.roundNumber
        entity.interviewer = request.interviewer
        entity.notes = request.notes
    }

    /**
     * 实体转返回对象（对应 Java 的 BeanUtils.copyProperties）
     */
    private fun toDTO(entity: InterviewScheduleEntity): InterviewScheduleVo {
        return InterviewScheduleVo(
            id = entity.id,
            companyName = entity.companyName,
            position = entity.position,
            interviewTime = entity.interviewTime,
            interviewType = entity.interviewType,
            meetingLink = entity.meetingLink,
            roundNumber = entity.roundNumber,
            interviewer = entity.interviewer,
            notes = entity.notes,
            status = entity.status,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}

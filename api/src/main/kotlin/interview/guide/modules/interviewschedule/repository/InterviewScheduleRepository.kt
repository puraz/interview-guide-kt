package interview.guide.modules.interviewschedule.repository

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity
import interview.guide.modules.interviewschedule.model.InterviewStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 面试日程 Repository
 */
@Repository
interface InterviewScheduleRepository : JpaRepository<InterviewScheduleEntity, Long> {

    /**
     * 按状态与面试时间查询
     */
    fun findByStatusAndInterviewTimeBefore(
        status: InterviewStatus,
        time: LocalDateTime
    ): List<InterviewScheduleEntity>

    /**
     * 按状态查询
     */
    fun findByStatus(status: InterviewStatus): List<InterviewScheduleEntity>

    /**
     * 按面试时间区间查询
     */
    fun findByInterviewTimeBetween(start: LocalDateTime, end: LocalDateTime): List<InterviewScheduleEntity>

    /**
     * 批量更新过期面试状态
     */
    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    fun updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") newStatus: InterviewStatus,
        @Param("oldStatus") oldStatus: InterviewStatus,
        @Param("cutoff") cutoff: LocalDateTime
    ): Int
}

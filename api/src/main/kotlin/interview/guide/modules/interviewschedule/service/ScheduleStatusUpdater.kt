package interview.guide.modules.interviewschedule.service

import interview.guide.modules.interviewschedule.model.InterviewStatus
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 面试日程状态定时更新器
 */
@Component
class ScheduleStatusUpdater(
    private val repository: InterviewScheduleRepository // 面试日程仓库
) {

    companion object {
        private val log = LoggerFactory.getLogger(ScheduleStatusUpdater::class.java)
    }

    /**
     * 每小时将已过期的待进行面试标记为已取消
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    fun updateExpiredInterviews() {
        val updated = repository.updateStatusByStatusAndInterviewTimeBefore(
            InterviewStatus.CANCELLED, InterviewStatus.PENDING, LocalDateTime.now()
        )

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated)
        }
    }
}

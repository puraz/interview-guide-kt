package interview.guide.modules.interviewschedule.model

/**
 * 面试状态
 */
enum class InterviewStatus {
    PENDING, // 待进行
    COMPLETED, // 已完成
    CANCELLED, // 已取消
    RESCHEDULED // 已改期
}

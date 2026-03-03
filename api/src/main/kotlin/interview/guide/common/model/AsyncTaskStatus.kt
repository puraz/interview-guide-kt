package interview.guide.common.model

/**
 * 异步任务状态
 */
enum class AsyncTaskStatus {
    PENDING, // 等待处理
    PROCESSING, // 处理中
    COMPLETED, // 已完成
    FAILED // 已失败
}

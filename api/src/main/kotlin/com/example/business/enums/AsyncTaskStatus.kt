package com.example.business.enums

/**
 * 异步任务状态枚举
 */
enum class AsyncTaskStatus {
    PENDING, // 待处理
    PROCESSING, // 处理中
    COMPLETED, // 完成
    FAILED // 失败
}
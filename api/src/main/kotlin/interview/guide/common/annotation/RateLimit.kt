package interview.guide.common.annotation

import interview.guide.common.aspect.RateLimitAspect

/**
 * 限流注解
 * 用于方法级别的限流控制，支持多维度组合限流
 *
 * @see RateLimitAspect
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(
    /**
     * 限流维度配置
     * 支持多维度组合，只有所有维度都满足条件时才允许请求通过
     */
    val dimensions: Array<Dimension> = [Dimension.GLOBAL],
    /**
     * 在指定时间窗口内允许的最大请求数
     */
    val count: Double,
    /**
     * 时间窗口大小
     */
    val interval: Long = 1,
    /**
     * 时间单位
     */
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
    /**
     * 等待令牌的超时时间
     */
    val timeout: Long = 0,
    /**
     * 降级方法名
     */
    val fallback: String = ""
) {
    /**
     * 限流维度枚举
     */
    enum class Dimension {
        GLOBAL, // 全局限流
        IP, // IP限流
        USER // 用户限流
    }

    /**
     * 时间单位枚举
     */
    enum class TimeUnit {
        MILLISECONDS,
        SECONDS,
        MINUTES,
        HOURS,
        DAYS
    }
}

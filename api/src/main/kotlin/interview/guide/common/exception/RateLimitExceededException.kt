package interview.guide.common.exception

/**
 * 限流异常
 * 当请求超过限流阈值时抛出此异常
 */
class RateLimitExceededException : BusinessException {

    constructor() : super(ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED.message)

    constructor(message: String) : super(ErrorCode.RATE_LIMIT_EXCEEDED, message)

    constructor(message: String, cause: Throwable) : super(ErrorCode.RATE_LIMIT_EXCEEDED.code, message) {
        initCause(cause)
    }
}

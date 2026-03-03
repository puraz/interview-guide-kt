package interview.guide.common.exception

/**
 * 业务异常
 */
open class BusinessException : RuntimeException {

    val code: Int // 业务错误码
    private val detailMessage: String // 业务错误消息

    constructor(errorCode: ErrorCode) : super(errorCode.message) {
        this.code = errorCode.code
        this.detailMessage = errorCode.message
    }

    constructor(errorCode: ErrorCode, message: String) : super(message) {
        this.code = errorCode.code
        this.detailMessage = message
    }

    constructor(code: Int, message: String) : super(message) {
        this.code = code
        this.detailMessage = message
    }

    constructor(message: String) : super(message) {
        this.code = ErrorCode.INTERNAL_ERROR.code
        this.detailMessage = message
    }

    constructor(message: String, cause: Throwable) : super(message, cause) {
        this.code = ErrorCode.INTERNAL_ERROR.code
        this.detailMessage = message
    }

    override val message: String
        get() = detailMessage
}

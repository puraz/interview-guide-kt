package interview.guide.common.exception

import interview.guide.common.result.Result
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.validation.BindException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.net.SocketTimeoutException

/**
 * 全局异常处理器
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 处理业务异常
     *
     * @param e 业务异常 // 业务错误封装
     * @return 统一错误响应 // 返回业务错误码
     */
    @ExceptionHandler(BusinessException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleBusinessException(e: BusinessException): Result<Void> {
        log.warn("业务异常: code={}, message={}", e.code, e.message)
        return Result.error(e.code, e.message)
    }

    /**
     * 处理参数校验异常
     *
     * @param e 参数校验异常 // 校验失败异常
     * @return 统一错误响应 // 参数错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleValidationException(e: MethodArgumentNotValidException): Result<Void> {
        val message = e.bindingResult.fieldErrors.joinToString(", ") { it.defaultMessage ?: "参数错误" }
        log.warn("参数校验失败: {}", message)
        return Result.error(ErrorCode.BAD_REQUEST, message)
    }

    /**
     * 处理绑定异常
     *
     * @param e 绑定异常 // 参数绑定失败
     * @return 统一错误响应 // 参数错误信息
     */
    @ExceptionHandler(BindException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleBindException(e: BindException): Result<Void> {
        val message = e.bindingResult.fieldErrors.joinToString(", ") { it.defaultMessage ?: "参数错误" }
        log.warn("参数绑定失败: {}", message)
        return Result.error(ErrorCode.BAD_REQUEST, message)
    }

    /**
     * 处理文件上传大小超限异常
     *
     * @param e 上传大小异常 // 上传大小超过限制
     * @return 统一错误响应 // 文件大小错误
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleMaxUploadSizeExceededException(e: MaxUploadSizeExceededException): Result<Void> {
        log.warn("文件上传大小超限: {}", e.message)
        return Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制")
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常 // 参数非法
     * @return 统一错误响应 // 参数错误信息
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleIllegalArgumentException(e: IllegalArgumentException): Result<Void> {
        log.warn("非法参数: {}", e.message)
        return Result.error(ErrorCode.BAD_REQUEST, e.message ?: "参数错误")
    }

    /**
     * 处理 AI 服务网络异常（SSL握手失败、连接超时等）
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(ResourceAccessException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleResourceAccessException(e: ResourceAccessException): Result<Void> {
        log.error("AI服务连接失败: {}", e.message, e)

        val cause = e.cause
        if (cause is SocketTimeoutException) {
            return Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试")
        }

        val message = e.message
        if (message != null && message.contains("handshake")) {
            return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务连接失败（网络不稳定），请检查网络或稍后重试")
        }

        return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试")
    }

    /**
     * 处理 AI 服务调用异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(RestClientException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleRestClientException(e: RestClientException): Result<Void> {
        log.error("AI服务调用失败: {}", e.message, e)

        val message = e.message
        if (message != null) {
            if (message.contains("401") || message.contains("Unauthorized")) {
                return Result.error(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请联系管理员")
            }
            if (message.contains("429") || message.contains("Too Many Requests")) {
                return Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试")
            }
        }

        return Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试")
    }

    /**
     * 处理其他未知异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleException(e: Exception): Result<Void> {
        log.error("系统异常: {}", e.message, e)
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试")
    }
}

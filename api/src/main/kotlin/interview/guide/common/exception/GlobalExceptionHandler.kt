package interview.guide.common.exception

import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.common.result.Result
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.validation.BindException
import org.springframework.validation.FieldError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
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
        log.warn("业务异常: code={}", e.code)
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
        val message = e.bindingResult.fieldErrors
            .map { it: FieldError -> it.defaultMessage }
            .joinToString(", ")
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
        val message = e.bindingResult.fieldErrors
            .map { it: FieldError -> it.defaultMessage }
            .joinToString(", ")
        log.warn("参数绑定失败: {}", message)
        return Result.error(ErrorCode.BAD_REQUEST, message)
    }

    /**
     * 处理文件上传大小超限异常
     *
     * @param e 上传大小异常 // 上传文件超过配置上限
     * @return 统一错误响应 // 文件大小错误
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleMaxUploadSizeExceededException(e: MaxUploadSizeExceededException): Result<Void> {
        log.warn("文件上传大小超限: {}", ErrorLogSanitizer.summarize(e))
        return Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制")
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常 // 参数不合法
     * @return 统一错误响应 // 参数错误信息
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleIllegalArgumentException(e: IllegalArgumentException): Result<Void> {
        log.warn("非法参数: {}", ErrorLogSanitizer.summarize(e))
        return Result.error(ErrorCode.BAD_REQUEST, e.message ?: ErrorCode.BAD_REQUEST.message)
    }

    /**
     * 处理 AI 服务网络异常（SSL握手失败、连接超时等）
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     *
     * @param e 资源访问异常 // AI 服务网络层异常
     * @return 统一错误响应 // 超时/不可用错误码
     */
    @ExceptionHandler(ResourceAccessException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleResourceAccessException(e: ResourceAccessException): Result<Void> {
        log.error(
            "AI服务连接失败: {}", ErrorLogSanitizer.summarize(e),
            ErrorLogSanitizer.forLogging(e)
        )

        return when (e.cause) {
            is SocketTimeoutException ->
                Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试")
            else -> {
                val message = e.message
                if (message != null && message.contains("handshake")) {
                    return Result.error(
                        ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "AI服务连接失败（网络不稳定），请检查网络或稍后重试"
                    )
                }
                Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试")
            }
        }
    }

    /**
     * 处理 AI 服务调用异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     *
     * @param e AI 调用异常 // RestClient 抛出
     * @return 统一错误响应 // 密钥/限流/调用失败错误码
     */
    @ExceptionHandler(RestClientException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleRestClientException(e: RestClientException): Result<Void> {
        log.error(
            "AI服务调用失败: {}", ErrorLogSanitizer.summarize(e),
            ErrorLogSanitizer.forLogging(e)
        )

        val message = e.message ?: return Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试")
        if (message.contains("401") || message.contains("Unauthorized")) {
            return Result.error(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请联系管理员")
        }
        if (message.contains("429") || message.contains("Too Many Requests")) {
            return Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试")
        }
        return Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试")
    }

    /**
     * 处理 404 - 资源未找到异常
     *
     * @param e 资源未找到异常 // 静态资源或接口不存在
     * @return 统一错误响应 // 404 错误码
     */
    @ExceptionHandler(NoResourceFoundException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleNoResourceFoundException(e: NoResourceFoundException): Result<Void> {
        log.warn("资源未找到: {}", e.resourcePath)
        return Result.error(ErrorCode.NOT_FOUND, "API 接口不存在")
    }

    /**
     * 处理请求方法不支持异常
     *
     * @param e 请求方法不支持异常 // HTTP Method 不匹配
     * @return 统一错误响应 // 405 错误码
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleHttpRequestMethodNotSupportedException(e: HttpRequestMethodNotSupportedException): Result<Void> {
        log.warn("请求方法不支持: {} {}", e.method, e.supportedHttpMethods)
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.method)
    }

    /**
     * 处理其他未知异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     *
     * @param e 未知异常 // 兜底异常
     * @return 统一错误响应 // 500 错误码
     */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleException(e: Exception): Result<Void> {
        log.error(
            "系统异常: {}", ErrorLogSanitizer.summarize(e),
            ErrorLogSanitizer.forLogging(e)
        )
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试")
    }
}

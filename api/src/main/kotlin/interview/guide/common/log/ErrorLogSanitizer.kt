package interview.guide.common.log

/**
 * 异常日志消息简化器。
 *
 * SDK 与上游服务可能把请求体、Prompt 片段回显到异常 message 中，直接记录存在隐私风险。
 * 统一只保留异常类型。原始异常 message、cause 和 suppressed exception 都可能回显用户输入，
 * 因此不得直接进入日志；需要堆栈时使用 [forLogging] 生成脱敏副本。
 */
object ErrorLogSanitizer {

    /**
     * 仅输出异常类名，不输出可能包含 Prompt、转写或文件正文的 message。
     *
     * @param e 原始异常 // 可能为 null
     * @return 异常类名 // 无法识别时返回 unknown
     */
    @JvmStatic
    fun summarize(e: Throwable?): String {
        if (e == null) {
            return "unknown"
        }
        return e.javaClass.simpleName
    }

    /**
     * 将外部服务返回的自由文本归一为固定标记，避免响应正文或用户输入被回显。
     *
     * @param message 外部服务返回的文本 // 可能为 null 或空白
     * @return 固定标记 // 空文本返回空串
     */
    @JvmStatic
    fun summarize(message: String?): String {
        if (message.isNullOrBlank()) {
            return ""
        }
        return "external-error"
    }

    /**
     * 返回可安全作为 SLF4J 最后一个参数记录的异常副本。
     *
     * 副本保留原始调用栈以便定位代码，但丢弃原始 message、cause 与 suppressed exception。
     *
     * @param error 原始异常 // 可能为 null
     * @return 脱敏后的异常副本 // 保留调用栈
     */
    @JvmStatic
    fun forLogging(error: Throwable?): Throwable {
        val errorType = error?.javaClass?.simpleName ?: "unknown"
        val sanitized = SanitizedLogException(errorType)
        if (error != null) {
            sanitized.stackTrace = error.stackTrace
        }
        return sanitized
    }

    /**
     * 脱敏日志异常：只保留异常类型与调用栈，不保留 message、cause、suppressed
     */
    private class SanitizedLogException(errorType: String) :
        Exception("sanitized-error type=$errorType", null, false, true)
}

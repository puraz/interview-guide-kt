package interview.guide.common.ai

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.common.util.stripCodeFences
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.Logger
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
class StructuredOutputInvoker(
    properties: StructuredOutputProperties, // 结构化输出配置
    @Autowired(required = false) private val meterRegistry: MeterRegistry? = null // 指标注册表（可选）
) {

    private val maxAttempts: Int = maxOf(1, properties.structuredMaxAttempts) // 最大尝试次数

    private val includeLastErrorInRetryPrompt: Boolean = properties.structuredIncludeLastError // 重试是否携带上次错误

    private val retryUseRepairPrompt: Boolean = properties.structuredRetryUseRepairPrompt // 是否使用修复型提示词

    private val retryAppendStrictJsonInstruction: Boolean =
        properties.structuredRetryAppendStrictJsonInstruction // 修复提示词是否追加严格 JSON 指令

    private val errorMessageMaxLength: Int =
        maxOf(20, properties.structuredErrorMessageMaxLength) // 错误信息截断长度

    private val metricsEnabled: Boolean = properties.structuredMetricsEnabled // 是否开启指标

    private val schemaValidationEnabled: Boolean = properties.structuredSchemaValidationEnabled // 是否启用 schema 校验

    companion object {
        private const val STRICT_JSON_INSTRUCTION: String = """请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
"""

        private const val METRIC_INVOCATIONS = "app.ai.structured_output.invocations"
        private const val METRIC_ATTEMPTS = "app.ai.structured_output.attempts"
        private const val METRIC_LATENCY = "app.ai.structured_output.latency"
        private const val STATUS_SUCCESS = "success"
        private const val STATUS_FAILURE = "failure"
        private const val MAX_CONTEXT_TAG_LENGTH = 48

        private val NON_ALNUM_PATTERN: Pattern = Pattern.compile("[^a-z0-9_]+")
        private val MULTI_UNDERSCORE: Pattern = Pattern.compile("_+")
        private val LEADING_TRAILING_UNDERSCORE: Pattern = Pattern.compile("^_+|_+$")
    }

    /**
     * 执行结构化输出调用，失败时按配置重试
     *
     * @param chatClient AI 客户端 // 已按 Provider 构建
     * @param systemPromptWithFormat 系统提示词 // 包含格式约束
     * @param userPrompt 用户提示词 // 业务输入
     * @param outputConverter 输出转换器 // JSON 转对象
     * @param errorCode 错误码 // 最终失败时返回的业务错误码
     * @param errorPrefix 错误前缀 // 失败提示前缀
     * @param logContext 日志上下文 // 日志展示名称
     * @param log 日志对象 // 输出日志
     * @return 结构化结果 // 解析成功的业务对象
     */
    fun <T : Any> invoke(
        chatClient: ChatClient,
        systemPromptWithFormat: String,
        userPrompt: String,
        outputConverter: BeanOutputConverter<T>,
        errorCode: ErrorCode,
        errorPrefix: String,
        logContext: String,
        log: Logger
    ): T {
        val startNanos = System.nanoTime()
        val contextTag = normalizeContextTag(logContext)
        val securedSystemPrompt = systemPromptWithFormat + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            val attemptSystemPrompt = if (attempt == 1) {
                securedSystemPrompt
            } else {
                buildRetrySystemPrompt(securedSystemPrompt, lastError)
            }
            try {
                val result = callStructuredOutput(
                    chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext, log
                )
                recordAttempt(contextTag, STATUS_SUCCESS)
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos)
                return result
            } catch (e: Exception) {
                lastError = e
                recordAttempt(contextTag, STATUS_FAILURE)
                if (attempt < maxAttempts) {
                    log.warn(
                        "{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, ErrorLogSanitizer.summarize(e),
                        ErrorLogSanitizer.forLogging(e)
                    )
                } else {
                    log.error(
                        "{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, ErrorLogSanitizer.summarize(e),
                        ErrorLogSanitizer.forLogging(e)
                    )
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos)
        throw BusinessException(
            errorCode,
            errorPrefix + "请稍后重试"
        )
    }

    /**
     * 调用一次结构化输出：schema 校验开启时走 entity 转换，否则走本地修复转换
     *
     * @param chatClient AI 客户端
     * @param systemPrompt 本次 system 提示词
     * @param userPrompt 用户提示词
     * @param outputConverter 输出转换器
     * @param logContext 日志上下文
     * @param log 日志对象
     * @return 结构化结果
     */
    private fun <T : Any> callStructuredOutput(
        chatClient: ChatClient,
        systemPrompt: String,
        userPrompt: String,
        outputConverter: BeanOutputConverter<T>,
        logContext: String,
        log: Logger
    ): T {
        val call = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
        if (schemaValidationEnabled) {
            return call.entity(outputConverter) { spec: ChatClient.EntityParamSpec -> spec.validateSchema() }
                ?: throw IllegalStateException("AI 结构化输出为空")
        }
        val content = call.content()
        return convertWithRepair(content, outputConverter, logContext, log)
    }

    /**
     * 本地修复未转义引号后再解析
     *
     * @param content 模型原始输出 // 可能包含未转义引号
     * @param outputConverter 输出转换器
     * @param logContext 日志上下文
     * @param log 日志对象
     * @return 结构化结果
     */
    private fun <T : Any> convertWithRepair(
        content: String?,
        outputConverter: BeanOutputConverter<T>,
        logContext: String,
        log: Logger
    ): T {
        // 去除可能的 Markdown 代码块标记（项目规范要求 AI 返回内容先 stripCodeFences）
        val normalized = content?.stripCodeFences()
            ?: throw IllegalStateException("AI 返回内容为空")
        try {
            return outputConverter.convert(normalized)
        } catch (firstError: Exception) {
            val repaired = repairUnescapedQuotesInJsonStrings(normalized)
            if (repaired != normalized) {
                try {
                    val result = outputConverter.convert(repaired)
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext)
                    return result
                } catch (repairError: Exception) {
                    firstError.addSuppressed(repairError)
                }
            }
            throw firstError
        }
    }

    /**
     * 修复 JSON 字符串内部未转义的双引号
     *
     * @param content 原始 JSON 文本 // 可为 null
     * @return 修复后的 JSON 文本
     */
    private fun repairUnescapedQuotesInJsonStrings(content: String): String {
        if (content.isBlank()) {
            return content
        }
        val repaired = StringBuilder(content.length + 16)
        var inString = false
        var escaping = false
        for (i in content.indices) {
            val ch = content[i]
            if (!inString) {
                if (ch == '"') {
                    inString = true
                }
                repaired.append(ch)
                continue
            }

            if (escaping) {
                repaired.append(ch)
                escaping = false
                continue
            }
            if (ch == '\\') {
                repaired.append(ch)
                escaping = true
                continue
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false
                    repaired.append(ch)
                } else {
                    repaired.append("\\\"")
                }
                continue
            }
            repaired.append(ch)
        }
        return repaired.toString()
    }

    /**
     * 判断当前位置是否可能是 JSON 字符串的结束引号
     *
     * @param content 原始 JSON 文本
     * @param start 起始下标
     * @return true=后面紧跟结构符号，视为结束引号
     */
    private fun isLikelyJsonStringTerminator(content: String, start: Int): Boolean {
        for (i in start until content.length) {
            val next = content[i]
            if (Character.isWhitespace(next)) {
                continue
            }
            return next == ',' || next == '}' || next == ']' || next == ':'
        }
        return true
    }

    /**
     * 构建重试提示词
     *
     * @param systemPromptWithFormat 原始 system 提示词
     * @param lastError 上次失败异常
     * @return 重试使用的 system 提示词
     */
    private fun buildRetrySystemPrompt(systemPromptWithFormat: String, lastError: Exception?): String {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat
        }

        val prompt = StringBuilder(systemPromptWithFormat)
            .append("\n\n")

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n')
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。")

        val lastErrorMessage = lastError?.message
        if (includeLastErrorInRetryPrompt && lastErrorMessage != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastErrorMessage))
        }
        return prompt.toString()
    }

    /**
     * 将错误信息压缩为单行并截断
     *
     * @param message 原始错误信息
     * @return 处理后的错误信息
     */
    private fun sanitizeErrorMessage(message: String): String {
        val oneLine = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.length > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "..."
        }
        return oneLine
    }

    /**
     * 记录一次尝试的指标
     *
     * @param contextTag 上下文标签
     * @param status 尝试结果 // success/failure
     */
    private fun recordAttempt(contextTag: String, status: String) {
        val registry = meterRegistry
        if (!metricsEnabled || registry == null) {
            return
        }
        registry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment()
    }

    /**
     * 记录一次调用的指标与耗时
     *
     * @param contextTag 上下文标签
     * @param status 调用结果 // success/failure
     * @param startNanos 开始时间（纳秒）
     */
    private fun recordInvocation(contextTag: String, status: String, startNanos: Long) {
        val registry = meterRegistry
        if (!metricsEnabled || registry == null) {
            return
        }
        val tags = Tags.of("context", contextTag, "status", status)
        registry.counter(METRIC_INVOCATIONS, tags).increment()
        registry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * 指标是否可用
     *
     * @return true=开启且已注入 MeterRegistry
     */
    private fun isMetricsAvailable(): Boolean {
        return metricsEnabled && meterRegistry != null
    }

    /**
     * 归一化指标上下文标签：小写、空格转下划线、仅保留字母数字下划线、去重下划线、截断
     *
     * @param raw 原始上下文 // 可为 null/空白
     * @return 归一化后的标签 // 默认 unknown
     */
    private fun normalizeContextTag(raw: String?): String {
        val source = if (raw == null || raw.isBlank()) "unknown" else raw
        var normalized = source.lowercase(Locale.ROOT).trim().replace(' ', '_')
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_")
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_")
        normalized = LEADING_TRAILING_UNDERSCORE.matcher(normalized).replaceAll("")
        if (normalized.isBlank()) {
            normalized = "unknown"
        }
        if (normalized.length > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH)
        }
        return normalized
    }
}

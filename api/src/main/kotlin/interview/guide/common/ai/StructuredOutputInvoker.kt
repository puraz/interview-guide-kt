package interview.guide.common.ai

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.util.stripCodeFences
import org.slf4j.Logger
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 统一封装结构化输出调用与重试策略
 */
@Component
class StructuredOutputInvoker(
    @Value("\${app.ai.structured-max-attempts:2}") private val maxAttemptsInput: Int,
    @Value("\${app.ai.structured-include-last-error:true}") private val includeLastErrorInRetryPrompt: Boolean
) {

    private val maxAttempts: Int = if (maxAttemptsInput < 1) 1 else maxAttemptsInput // 最小尝试次数

    companion object {
        private const val STRICT_JSON_INSTRUCTION: String = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
"""
    }

    /**
     * 执行结构化输出调用
     *
     * @param chatClient AI 客户端 // 用于调用大模型
     * @param systemPromptWithFormat 系统提示词 // 包含格式约束
     * @param userPrompt 用户提示词 // 业务输入
     * @param outputConverter 输出转换器 // JSON 转对象
     * @param errorCode 错误码 // 失败返回的业务错误码
     * @param errorPrefix 错误前缀 // 失败提示前缀
     * @param logContext 日志上下文 // 日志展示名称
     * @param log 日志对象 // 输出日志
     */
    fun <T> invoke(
        chatClient: ChatClient,
        systemPromptWithFormat: String,
        userPrompt: String,
        outputConverter: BeanOutputConverter<T>,
        errorCode: ErrorCode,
        errorPrefix: String,
        logContext: String,
        log: Logger
    ): T {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val attemptSystemPrompt = if (attempt == 1) {
                systemPromptWithFormat
            } else {
                buildRetrySystemPrompt(systemPromptWithFormat, lastError)
            }

            try {
                // 调用 AI 获取原始内容
                val raw = chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .content()

                // 清理 AI 返回的代码块标记
                val cleaned = raw?.stripCodeFences()
                    ?: throw BusinessException(errorCode, "$errorPrefix 空响应，无法解析为结构化结果")

                // 转换为目标对象
                val converted = outputConverter.convert(cleaned)
                if (converted == null) {
                    throw BusinessException(errorCode, "$errorPrefix 空响应，无法解析为结构化结果")
                }
                return converted
            } catch (e: Exception) {
                lastError = e
                log.warn("{}结构化解析失败，准备重试: attempt={}, error={}", logContext, attempt, e.message)
            }
        }

        throw BusinessException(
            errorCode,
            errorPrefix + (lastError?.message ?: "unknown")
        )
    }

    private fun buildRetrySystemPrompt(systemPromptWithFormat: String, lastError: Exception?): String {
        val builder = StringBuilder(systemPromptWithFormat)
            .append("\n\n")
            .append(STRICT_JSON_INSTRUCTION)
            .append("\n上次输出解析失败，请仅返回合法 JSON。")

        if (includeLastErrorInRetryPrompt && lastError?.message != null) {
            builder.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.message ?: ""))
        }
        return builder.toString()
    }

    private fun sanitizeErrorMessage(message: String): String {
        val oneLine = message.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length > 200) {
            oneLine.substring(0, 200) + "..."
        } else {
            oneLine
        }
    }
}

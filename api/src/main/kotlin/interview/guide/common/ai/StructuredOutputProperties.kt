package interview.guide.common.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 结构化输出重试策略配置属性（对应 app.ai.structured-*）
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
class StructuredOutputProperties {

    var structuredMaxAttempts: Int = 2 // 结构化输出最大尝试次数

    var structuredIncludeLastError: Boolean = true // 重试时是否将上次错误原因注入提示词

    var structuredRetryUseRepairPrompt: Boolean = true // 重试时是否使用修复型提示词

    var structuredRetryAppendStrictJsonInstruction: Boolean = true // 修复型提示词是否追加严格 JSON 指令

    var structuredErrorMessageMaxLength: Int = 200 // 注入提示词的错误信息最大长度

    var structuredMetricsEnabled: Boolean = true // 是否开启结构化输出调用指标

    var structuredSchemaValidationEnabled: Boolean = true // 是否启用 Spring AI 2.0 entity schema validation
}

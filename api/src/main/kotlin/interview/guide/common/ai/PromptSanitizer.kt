package interview.guide.common.ai

import interview.guide.common.config.LlmProviderProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.regex.Pattern

/**
 * Prompt 注入净化工具。
 *
 * 仅用于 4 个严重风险的直接拼接点（裸拼接，无模板包裹）。
 * 模板插值点有 Layer 2 的系统提示词保护，不需要额外净化。
 */
@Component
class PromptSanitizer(
    private val properties: LlmProviderProperties // Provider/Advisor 配置（含净化开关）
) {

    private val log = LoggerFactory.getLogger(PromptSanitizer::class.java)

    companion object {
        // 行首角色标记：只匹配行首，避免误杀 "Experience with system design"
        private val ROLE_INJECTION_PATTERN: Pattern = Pattern.compile(
            "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
        )

        // 注入短语：精确匹配，不单独匹配 "忽略" 或 "instruction" 等常见词
        private val INJECTION_PHRASE_PATTERN: Pattern = Pattern.compile(
            "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
                "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
                "|(new\\s+instructions?:)" +
                "|忽略之前的指令" +
                "|忘记之前的指令" +
                "|忽略以上所有" +
                "|你不再是" +
                "|你的新角色是",
            Pattern.CASE_INSENSITIVE
        )

        // 分隔符伪造：匹配项目中 .st 模板使用的静态分隔符
        private val DELIMITER_INJECTION_PATTERN: Pattern = Pattern.compile(
            "---(?:简历|文档|问答)内容(?:开始|结束)---"
        )

        // XML 边界标签伪造：防止攻击者构造 <data-boundary...> 来提前关闭包裹
        private val BOUNDARY_TAG_PATTERN: Pattern = Pattern.compile(
            "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
        )
    }

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     * 受 `app.ai.advisors.promptSanitizerEnabled` 配置控制。
     *
     * @param text 用户原始文本 // 可能为 null/空白
     * @return 清洗后的文本 // 未启用净化时原样返回
     */
    fun sanitize(text: String?): String? {
        if (text == null || text.isBlank()) {
            return text
        }
        if (!isSanitizerEnabled()) {
            return text
        }

        var injected = false
        var result = text

        val roleMatcher = ROLE_INJECTION_PATTERN.matcher(result)
        if (roleMatcher.find()) {
            injected = true
            result = roleMatcher.replaceAll("[filtered-role-marker]")
        }
        val phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result)
        if (phraseMatcher.find()) {
            injected = true
            result = phraseMatcher.replaceAll("[filtered]")
        }
        val delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result)
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]")
        }
        val tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result)
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]")
        }

        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length)
        }
        return result
    }

    /**
     * 用不可预测的分隔符包裹用户文本。
     * 格式：`<data-boundary-{uuid片段}-{label}> ... </data-boundary-{uuid片段}-{label}>`
     * UUID 片段使攻击者无法提前构造伪造分隔符。
     *
     * @param label 数据标签 // 用于区分数据段
     * @param text 用户文本 // 需要包裹的内容
     * @return 包裹后的文本 // 带随机边界标签
     */
    fun wrapWithDelimiters(label: String, text: String?): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val openTag = "<data-boundary-$id-$label>"
        val closeTag = "</data-boundary-$id-$label>"
        return "$openTag\n$text\n$closeTag"
    }

    /**
     * 检测注入尝试（仅日志告警，不阻断）。
     *
     * @param text 用户原始文本 // 可能为 null/空白
     * @return 是否检测到注入特征 // true=命中
     */
    fun detectInjectionAttempt(text: String?): Boolean {
        if (text == null || text.isBlank()) {
            return false
        }
        return ROLE_INJECTION_PATTERN.matcher(text).find() ||
            INJECTION_PHRASE_PATTERN.matcher(text).find()
    }

    /**
     * 是否启用净化：未配置 advisors 时默认启用
     *
     * @return 开关状态 // true=启用
     */
    private fun isSanitizerEnabled(): Boolean {
        val advisors = properties.advisors
        return advisors == null || advisors.promptSanitizerEnabled
    }
}

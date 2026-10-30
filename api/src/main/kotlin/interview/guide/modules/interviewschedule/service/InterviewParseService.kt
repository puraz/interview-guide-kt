package interview.guide.modules.interviewschedule.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.ai.PromptSecurityConstants
import interview.guide.common.util.stripCodeFences
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest
import interview.guide.modules.interviewschedule.model.ParseResponseVo
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * 统一的面试邀约解析服务 - 整合规则解析和AI解析
 * Simplified interview schedule parsing service combining rule-based and AI parsing
 */
@Service
class InterviewParseService(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val promptSanitizer: PromptSanitizer // Prompt 注入净化工具
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewParseService::class.java)

        // Date formatters
        private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") // 日期时间格式1
        private val DATE_TIME_FORMATTER_2: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm") // 日期时间格式2

        private val CHINESE_NUMBERS: Map<String, Int> = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )

        // Feishu patterns
        private val TIME_PATTERN_FEISHU: Pattern =
            Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})") // 飞书时间
        private val LINK_PATTERN_FEISHU: Pattern =
            Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+") // 飞书会议链接
        private val COMPANY_PATTERN_FEISHU: Pattern =
            Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})") // 飞书公司
        private val POSITION_PATTERN_FEISHU: Pattern =
            Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})") // 飞书岗位
        private val ROUND_PATTERN_FEISHU: Pattern =
            Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]") // 飞书轮次

        // Tencent meeting patterns
        private val TIME_PATTERN_TENCENT: Pattern =
            Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})") // 腾讯会议时间
        private val MEETING_ID_PATTERN_TENCENT: Pattern =
            Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})") // 腾讯会议号
        private val PASSWORD_PATTERN_TENCENT: Pattern =
            Pattern.compile("密码[：:]?\\s*(\\d{4,})") // 腾讯会议密码
        private val COMPANY_PATTERN_TENCENT: Pattern =
            Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})") // 腾讯会议公司
        private val POSITION_PATTERN_TENCENT: Pattern =
            Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})") // 腾讯会议岗位

        // Zoom patterns
        private val LINK_PATTERN_ZOOM: Pattern =
            Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+") // Zoom 会议链接
        private val DATE_PATTERN_ZOOM: Pattern =
            Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})") // Zoom 日期
        private val HOUR_PATTERN_ZOOM: Pattern =
            Pattern.compile("(\\d{1,2}:\\d{2})") // Zoom 时间

        // Round number pattern
        private val ROUND_NUMBER_PATTERN: Pattern =
            Pattern.compile("[一二三四五六七八九十]|\\d") // 轮次数字

        private val PARSE_PROMPT: String = """
你是一个专业的面试邀约信息提取助手。请仔细分析以下文本，提取面试相关信息。

**提取规则**：
1. companyName（公司名称）：提取面试公司的全称或简称，**必需字段**
2. position（岗位名称）：提取面试岗位的名称，**必需字段**
3. interviewTime（面试时间）：提取面试开始时间并转换为 ISO 8601 格式，**必需字段**
   - 格式：YYYY-MM-DDTHH:MM:SS（例如：2026-04-10T14:00:00）
   - 若只有相对时间（如"明天下午2点"），根据当前日期 %s 推算
4. interviewType（面试形式）：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话）
5. meetingLink（会议链接）：提取完整的会议链接或会议号+密码
6. roundNumber（第几轮面试）：提取数字（1-10），如"二面"提取为2
7. notes（其他备注）：包含面试官姓名（如果不重要可忽略）、时长（**默认30分钟**）等。

**重要提示**：
- 面试官是谁不重要，只需在 notes 中提及。
- 优先保证 companyName、position、interviewTime 的准确性。
- 如果文本中没说时长，默认设置为 30 分钟。

**待解析文本**：
%s

**返回格式**：
纯 JSON 格式，不要包含```json标记，示例：
{"companyName":"阿里巴巴","position":"Java工程师","interviewTime":"2026-04-10T14:00:00","interviewType":"VIDEO","meetingLink":"https://meeting.feishu.cn/xxx","roundNumber":2,"interviewer":"张三","notes":"技术面"}
"""
    }

    /**
     * Parse interview schedule text
     *
     * @param rawText Raw text to parse
     * @param source Source platform (feishu/tencent/zoom or null for auto-detect)
     * @return Parse response with extracted interview details
     */
    fun parse(rawText: String?, source: String?): ParseResponseVo {
        log.info("开始解析文本，来源: {}, 文本长度: {}", source, rawText?.length ?: 0)

        if (rawText.isNullOrBlank()) {
            log.warn("Input text is null or empty")
            return ParseResponseVo(false, null, 0.0, "none", "输入文本为空")
        }

        // Step 1: Try rule-based parsing
        var result = tryRuleParsing(rawText, source)
        if (isValidResult(result)) {
            log.info("规则解析成功")
            return ParseResponseVo(true, result, 0.95, "rule", "规则解析成功")
        }

        // Step 2: Rule parsing failed, try AI parsing
        log.info("规则解析失败，尝试 AI 解析")
        result = parseWithAI(rawText, source) // 使用source作为provider参数
        if (isValidResult(result)) {
            log.info("AI 解析成功")
            return ParseResponseVo(true, result, 0.8, "ai", "AI 解析成功")
        }

        // Step 3: Both failed
        log.warn("所有解析方式均失败")
        return ParseResponseVo(false, null, 0.0, "none", "解析失败")
    }

    /**
     * 规则解析：优先按来源平台匹配，未命中则依次尝试
     */
    private fun tryRuleParsing(rawText: String, source: String?): CreateInterviewRequest? {
        // Try source-specific format first
        if ("feishu".equals(source, ignoreCase = true)) {
            return parseFeishu(rawText)
        } else if ("tencent".equals(source, ignoreCase = true)) {
            return parseTencent(rawText)
        } else if ("zoom".equals(source, ignoreCase = true)) {
            return parseZoom(rawText)
        }

        // No source specified, try all formats
        if (rawText.contains("飞书") || rawText.contains("Feishu") || rawText.contains("meeting.feishu.cn")) {
            val result = parseFeishu(rawText)
            if (isValidResult(result)) return result
        }

        if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting") || rawText.contains("会议号")) {
            val result = parseTencent(rawText)
            if (isValidResult(result)) return result
        }

        if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
            val result = parseZoom(rawText)
            if (isValidResult(result)) return result
        }

        // Try all formats
        var result = parseFeishu(rawText)
        if (isValidResult(result)) return result

        result = parseTencent(rawText)
        if (isValidResult(result)) return result

        return parseZoom(rawText)
    }

    // ========== Feishu Parsing ==========

    /**
     * 解析飞书面试邀约
     */
    private fun parseFeishu(rawText: String): CreateInterviewRequest {
        log.debug("尝试解析飞书格式")

        val request = CreateInterviewRequest()

        try {
            // Extract time
            val timeMatcher = TIME_PATTERN_FEISHU.matcher(rawText)
            if (timeMatcher.find()) {
                val timeStr = timeMatcher.group(1)
                request.interviewTime = parseDateTime(timeStr)
            }

            // Extract link
            val linkMatcher = LINK_PATTERN_FEISHU.matcher(rawText)
            if (linkMatcher.find()) {
                request.meetingLink = linkMatcher.group()
            }

            // Extract company
            val companyMatcher = COMPANY_PATTERN_FEISHU.matcher(rawText)
            if (companyMatcher.find()) {
                request.companyName = companyMatcher.group(1).trim()
            }

            // Extract position
            val positionMatcher = POSITION_PATTERN_FEISHU.matcher(rawText)
            if (positionMatcher.find()) {
                request.position = positionMatcher.group(1).trim()
            }

            // Extract round number
            val roundMatcher = ROUND_PATTERN_FEISHU.matcher(rawText)
            if (roundMatcher.find()) {
                request.roundNumber = parseRoundNumber(roundMatcher.group())
            }

            request.interviewType = "VIDEO"

            return request
        } catch (e: Exception) {
            log.error("飞书格式解析异常", e)
            return request
        }
    }

    // ========== Tencent Meeting Parsing ==========

    /**
     * 解析腾讯会议面试邀约
     */
    private fun parseTencent(rawText: String): CreateInterviewRequest {
        log.debug("尝试解析腾讯会议格式")

        val request = CreateInterviewRequest()

        try {
            // Extract time
            val timeMatcher = TIME_PATTERN_TENCENT.matcher(rawText)
            if (timeMatcher.find()) {
                val timeStr = timeMatcher.group(1) + " " + timeMatcher.group(2)
                request.interviewTime = parseDateTime(timeStr)
            }

            // Extract meeting ID and password
            val meetingIdMatcher = MEETING_ID_PATTERN_TENCENT.matcher(rawText)
            val passwordMatcher = PASSWORD_PATTERN_TENCENT.matcher(rawText)

            val meetingLink = StringBuilder()
            if (meetingIdMatcher.find()) {
                meetingLink.append("会议号: ").append(meetingIdMatcher.group())
            }
            if (passwordMatcher.find()) {
                meetingLink.append(" 密码: ").append(passwordMatcher.group())
            }
            if (meetingLink.isNotEmpty()) {
                request.meetingLink = meetingLink.toString()
            }

            // Extract company and position
            val companyMatcher = COMPANY_PATTERN_TENCENT.matcher(rawText)
            if (companyMatcher.find()) {
                request.companyName = companyMatcher.group(1).trim()
            }

            val positionMatcher = POSITION_PATTERN_TENCENT.matcher(rawText)
            if (positionMatcher.find()) {
                request.position = positionMatcher.group(1).trim()
            }

            request.interviewType = "VIDEO"

            return request
        } catch (e: Exception) {
            log.error("腾讯会议格式解析异常", e)
            return request
        }
    }

    // ========== Zoom Parsing ==========

    /**
     * 解析 Zoom 面试邀约
     */
    private fun parseZoom(rawText: String): CreateInterviewRequest {
        log.debug("尝试解析 Zoom 格式")

        val request = CreateInterviewRequest()

        try {
            // Extract link
            val linkMatcher = LINK_PATTERN_ZOOM.matcher(rawText)
            if (linkMatcher.find()) {
                request.meetingLink = linkMatcher.group()
            }

            // Extract date and hour
            val dateMatcher = DATE_PATTERN_ZOOM.matcher(rawText)
            val hourMatcher = HOUR_PATTERN_ZOOM.matcher(rawText)

            if (dateMatcher.find() && hourMatcher.find()) {
                val timeStr = dateMatcher.group(1) + " " + hourMatcher.group(1)
                request.interviewTime = parseDateTime(timeStr)
            }

            request.interviewType = "VIDEO"

            return request
        } catch (e: Exception) {
            log.error("Zoom 格式解析异常", e)
            return request
        }
    }

    // ========== AI Parsing ==========

    /**
     * AI 解析面试邀约
     */
    private fun parseWithAI(rawText: String, provider: String?): CreateInterviewRequest? {
        try {
            val currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val safeRawText = promptSanitizer.sanitize(rawText)
            val prompt = String.format(
                PARSE_PROMPT, currentDate,
                PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
                    promptSanitizer.wrapWithDelimiters("parse-input", safeRawText)
            )

            val chatClient: ChatClient = llmProviderRegistry.getChatClientOrDefault(provider)

            val content = chatClient.prompt()
                .user(prompt)
                .call()
                .content()

            if (content.isNullOrBlank()) {
                log.error("AI 解析返回内容为空")
                return null
            }

            // 去除 Markdown 代码块标记后再提取 JSON
            var jsonContent = content.stripCodeFences()
            if (jsonContent.contains("```")) {
                val pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
                val matcher = pattern.matcher(jsonContent)
                if (matcher.find()) {
                    jsonContent = matcher.group(1).trim()
                }
            }

            log.debug("提取到的 JSON 内容: {}", jsonContent)
            val result: Map<String, Any>? = objectMapper.readValue(
                jsonContent,
                object : TypeReference<Map<String, Any>>() {}
            )

            if (result.isNullOrEmpty()) {
                log.error("JSON 解析返回空结果")
                return null
            }

            val request = CreateInterviewRequest()

            // Extract and validate fields
            result["companyName"]?.let {
                request.companyName = it.toString().trim()
            }

            result["position"]?.let {
                request.position = it.toString().trim()
            }

            result["interviewTime"]?.let {
                try {
                    val timeStr = it.toString().trim()
                    if (timeStr.length == 16) { // YYYY-MM-DDTHH:MM
                        request.interviewTime = LocalDateTime.parse("$timeStr:00")
                    } else {
                        request.interviewTime = LocalDateTime.parse(timeStr)
                    }
                } catch (e: Exception) {
                    log.error("AI 返回的时间格式不正确: {}", result["interviewTime"])
                }
            }

            result["interviewType"]?.let {
                request.interviewType = it.toString().trim()
            }

            result["meetingLink"]?.let {
                request.meetingLink = it.toString().trim()
            }

            result["roundNumber"]?.let {
                try {
                    val roundStr = it.toString().trim()
                    request.roundNumber = roundStr.toInt()
                } catch (e: Exception) {
                    request.roundNumber = 1
                }
            }

            result["interviewer"]?.let {
                request.interviewer = it.toString().trim()
            }

            result["notes"]?.let {
                request.notes = it.toString().trim()
            }

            log.info("AI 解析成功: {}", request.companyName)
            return request
        } catch (e: Exception) {
            log.error("AI 解析异常: {}", e.message, e)
            return null
        }
    }

    // ========== Helper Methods ==========

    /**
     * 解析日期时间字符串
     */
    private fun parseDateTime(timeStr: String): LocalDateTime? {
        var normalized = timeStr
        return try {
            normalized = normalized.replace("/", "-")
            if (normalized.length == 16) { // yyyy-MM-dd HH:mm
                LocalDateTime.parse(normalized, DATE_TIME_FORMATTER)
            } else if (normalized.length == 19) { // yyyy-MM-dd HH:mm:ss
                LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            } else {
                LocalDateTime.parse(normalized)
            }
        } catch (e: Exception) {
            log.error("时间解析失败: {}", normalized, e)
            null
        }
    }

    /**
     * 解析轮次数字（支持中文数字）
     */
    private fun parseRoundNumber(text: String?): Int {
        if (text == null) return 1

        val trimmed = text.trim()

        if (trimmed.matches(Regex("\\d+"))) {
            return trimmed.toInt()
        }

        val matcher = ROUND_NUMBER_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            val num = matcher.group()
            return CHINESE_NUMBERS[num] ?: num.replace(Regex("\\D"), "").toInt()
        }

        return 1
    }

    /**
     * 校验解析结果是否包含必需字段
     */
    private fun isValidResult(result: CreateInterviewRequest?): Boolean {
        return result != null &&
            result.companyName != null &&
            result.position != null &&
            result.interviewTime != null
    }
}

package interview.guide.infrastructure.file

import org.springframework.stereotype.Service
import java.util.regex.Pattern

/**
 * 文本清理服务
 * 提供统一的文本内容清理和规范化功能
 */
@Service
class TextCleaningService {

    companion object {
        private val IMAGE_FILENAME_LINE = Pattern.compile("(?m)^image\\d+\\.(png|jpe?g|gif|bmp|webp)\\s*$") // 图片文件名行
        private val IMAGE_URL = Pattern.compile("https?://\\S+?\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?", Pattern.CASE_INSENSITIVE) // 图片链接
        private val FILE_URL = Pattern.compile("file:(//)?\\S+", Pattern.CASE_INSENSITIVE) // 文件协议URL
        private val SEPARATOR_LINE = Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$") // 分隔线
        private val CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]") // 控制字符
        private val HTML_TAGS = Pattern.compile("<[^>]+>") // HTML 标签
    }

    /**
     * 清理和规范化文本内容
     *
     * @param text 原始文本 // 待清理文本
     * @return 清理后的文本 // 过滤噪声后的文本
     */
    fun cleanText(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }

        var t = text

        // 语义去噪
        t = CONTROL_CHARS.matcher(t).replaceAll("")
        t = IMAGE_FILENAME_LINE.matcher(t).replaceAll("")
        t = IMAGE_URL.matcher(t).replaceAll("")
        t = FILE_URL.matcher(t).replaceAll("")
        t = SEPARATOR_LINE.matcher(t).replaceAll("")

        // 格式规范化
        t = t.replace("\r\n", "\n").replace("\r", "\n")
        t = t.replace(Regex("(?m)[ \t]+$"), "")
        t = t.replace(Regex("\\n{3,}"), "\n\n")

        return t.trim()
    }

    /**
     * 清理文本并限制最大长度
     *
     * @param text 原始文本 // 待清理文本
     * @param maxLength 最大长度 // 最大允许长度
     * @return 清理后的文本 // 可能被截断
     */
    fun cleanTextWithLimit(text: String?, maxLength: Int): String {
        val cleaned = cleanText(text)
        return if (cleaned.length > maxLength) cleaned.substring(0, maxLength) else cleaned
    }

    /**
     * 清理文本并移除所有换行符（转为空格）
     *
     * @param text 原始文本 // 待清理文本
     * @return 单行文本 // 适合摘要展示
     */
    fun cleanToSingleLine(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }
        return text
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 移除 HTML 标签和常见 HTML 实体
     *
     * @param text 可能包含 HTML 的文本 // 原始文本
     * @return 纯文本 // HTML 移除后的文本
     */
    fun stripHtml(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }

        return HTML_TAGS.matcher(text).replaceAll(" ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

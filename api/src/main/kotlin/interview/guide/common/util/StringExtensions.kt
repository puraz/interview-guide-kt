package interview.guide.common.util

/**
 * 去除 Markdown 代码块标记
 *
 * @return 去除 ``` 包裹后的文本 // 处理 AI 返回的代码块格式
 */
fun String.stripCodeFences(): String {
    var text = this.trim()
    if (text.startsWith("```")) {
        text = text.removePrefix("```")
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd >= 0) {
            // 移除语言标识行
            text = text.substring(firstLineEnd + 1)
        }
    }
    if (text.endsWith("```")) {
        text = text.removeSuffix("```")
    }
    return text.trim()
}

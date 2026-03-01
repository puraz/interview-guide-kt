package com.example.framework.core.utils

private val CODE_FENCE_WRAPPER =
    Regex("^```[\\w-]*\\s*\\n?([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)

/**
 * 去除AI生成内容最外层的代码块标记，例如 ```html ... ```。
 */
fun String.stripCodeFences(): String {
    val trimmed = this.trim()
    val match = CODE_FENCE_WRAPPER.matchEntire(trimmed)
    return if (match != null) {
        match.groupValues[1].trim()
    } else {
        this
    }
}

/**
 * Java可调用的便捷方法。
 */
object AiContentSanitizer {
    @JvmStatic
    fun stripCodeFences(value: String?): String? = value?.stripCodeFences()
}

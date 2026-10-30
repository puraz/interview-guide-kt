package interview.guide.modules.llmprovider.dto

/**
 * TTS（语音合成）配置更新请求
 *
 * @property model 合成模型
 * @property apiKey API Key（为空表示不修改）
 * @property voice 音色
 * @property format 音频格式
 * @property sampleRate 采样率
 * @property mode 合成模式
 * @property languageType 语言类型
 * @property speechRate 语速
 * @property volume 音量
 */
data class TtsConfigRequest(
    val model: String? = null,
    val apiKey: String? = null,
    val voice: String? = null,
    val format: String? = null,
    val sampleRate: Int? = null,
    val mode: String? = null,
    val languageType: String? = null,
    val speechRate: Float? = null,
    val volume: Int? = null
)

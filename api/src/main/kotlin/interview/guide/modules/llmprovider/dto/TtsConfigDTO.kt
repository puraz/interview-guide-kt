package interview.guide.modules.llmprovider.dto

/**
 * TTS（语音合成）配置返回体
 *
 * @property model 合成模型
 * @property maskedApiKey 脱敏后的 API Key
 * @property voice 音色
 * @property format 音频格式
 * @property sampleRate 采样率
 * @property mode 合成模式
 * @property languageType 语言类型
 * @property speechRate 语速
 * @property volume 音量
 */
data class TtsConfigDTO(
    val model: String? = null,
    val maskedApiKey: String? = null,
    val voice: String? = null,
    val format: String? = null,
    val sampleRate: Int = 0,
    val mode: String? = null,
    val languageType: String? = null,
    val speechRate: Float = 0f,
    val volume: Int = 0
)

package interview.guide.modules.llmprovider.dto

/**
 * ASR（语音识别）配置返回体
 *
 * @property url 实时语音识别 WebSocket 地址
 * @property model 识别模型
 * @property maskedApiKey 脱敏后的 API Key
 * @property language 识别语言
 * @property format 音频格式
 * @property sampleRate 采样率
 * @property enableTurnDetection 是否启用断句检测
 * @property turnDetectionType 断句检测类型
 * @property turnDetectionThreshold 断句检测阈值
 * @property turnDetectionSilenceDurationMs 静音判定时长（毫秒）
 */
data class AsrConfigDTO(
    val url: String? = null,
    val model: String? = null,
    val maskedApiKey: String? = null,
    val language: String? = null,
    val format: String? = null,
    val sampleRate: Int = 0,
    val enableTurnDetection: Boolean = false,
    val turnDetectionType: String? = null,
    val turnDetectionThreshold: Float = 0f,
    val turnDetectionSilenceDurationMs: Int = 0
)

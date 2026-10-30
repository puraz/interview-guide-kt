package interview.guide.modules.llmprovider.dto

/**
 * ASR（语音识别）配置更新请求
 *
 * @property url 实时语音识别 WebSocket 地址
 * @property model 识别模型
 * @property apiKey API Key（为空表示不修改）
 * @property language 识别语言
 * @property format 音频格式
 * @property sampleRate 采样率
 * @property enableTurnDetection 是否启用断句检测
 * @property turnDetectionType 断句检测类型
 * @property turnDetectionThreshold 断句检测阈值
 * @property turnDetectionSilenceDurationMs 静音判定时长（毫秒）
 */
data class AsrConfigRequest(
    val url: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
    val language: String? = null,
    val format: String? = null,
    val sampleRate: Int? = null,
    val enableTurnDetection: Boolean? = null,
    val turnDetectionType: String? = null,
    val turnDetectionThreshold: Float? = null,
    val turnDetectionSilenceDurationMs: Int? = null
)

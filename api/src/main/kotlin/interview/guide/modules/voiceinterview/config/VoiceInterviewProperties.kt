package interview.guide.modules.voiceinterview.config

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * Voice interview configuration properties
 * 语音面试配置项
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.voice-interview")
class VoiceInterviewProperties {

    var phase: PhaseConfig = PhaseConfig() // 各面试阶段时长与题量配置
    var aliyun: AliyunConfig = AliyunConfig() // 阿里云 STT/TTS 配置（旧版）
    var rateLimit: RateLimitConfig = RateLimitConfig() // 限流配置
    var audio: AudioConfig = AudioConfig() // 音频参数配置
    var qwen: QwenConfig = QwenConfig() // Qwen3 Realtime ASR/TTS 配置
    var opening: OpeningConfig = OpeningConfig() // 开场白配置

    @field:Valid
    var contextCompression: ContextCompressionConfig = ContextCompressionConfig() // 上下文压缩配置

    /**
     * 语音面试单轮面试官回复最大字符数（超出会截断到句子边界）。
     */
    var aiQuestionMaxChars: Int = 120

    /**
     * 是否启用 LLM 流式文本下发（先文本后音频），用于降低首字等待时延。
     */
    var llmStreamingEnabled: Boolean = true

    /**
     * 流式文本最小推送间隔（毫秒），避免 WebSocket 过于频繁刷屏。
     */
    var aiStreamPushIntervalMs: Int = 180

    /**
     * 流式文本推送最小增量字符数。
     */
    var aiStreamMinCharsDelta: Int = 12

    /**
     * 每会话允许的并发 TTS 合成调用上限（防止 DashScope 连接限制）。
     */
    var maxConcurrentTtsPerSession: Int = 3

    /**
     * 是否启用分块音频推送（每句 TTS 完成后立即推送，不等全部完成）。
     */
    var chunkedAudioEnabled: Boolean = true

    /**
     * 单句 TTS 合成超时（秒），超时后跳过该句，用已成功的句子拼合音频。
     */
    var ttsTimeoutSeconds: Int = 8

    /**
     * TTS WebSocket 建连超时（秒），避免 SDK 握手失败后永久等待。
     */
    var ttsConnectTimeoutSeconds: Int = 5

    /**
     * 是否在应用启动时预热开场白音频缓存。默认关闭，避免启动时产生云端 TTS 调用。
     */
    var openingAudioWarmupEnabled: Boolean = false

    /**
     * 面试阶段配置
     */
    class PhaseConfig {
        var intro: DurationConfig = DurationConfig(3, 5, 8, 2, 5) // 自我介绍阶段
        var tech: DurationConfig = DurationConfig(8, 10, 15, 3, 8) // 技术阶段
        var project: DurationConfig = DurationConfig(8, 10, 15, 2, 5) // 项目阶段
        var hr: DurationConfig = DurationConfig(3, 5, 8, 2, 5) // HR 阶段
    }

    /**
     * 阶段时长/题量配置（Java 版通过构造器设置默认值，Kotlin 保留全部默认参数以支持 JavaBean 绑定）
     */
    class DurationConfig(
        var minDuration: Int = 0, // 最短时长（分钟）
        var suggestedDuration: Int = 0, // 建议时长（分钟）
        var maxDuration: Int = 0, // 最长时长（分钟）
        var minQuestions: Int = 0, // 最少问题数
        var maxQuestions: Int = 0 // 最多问题数
    )

    /**
     * 阿里云配置（旧版 STT/TTS）
     */
    class AliyunConfig {
        var stt: SttConfig = SttConfig() // 语音识别配置
        var tts: TtsConfig = TtsConfig() // 语音合成配置
    }

    /**
     * 阿里云 STT 配置
     */
    class SttConfig {
        var appKey: String? = null // 应用 Key
        var accessKey: String? = null // 访问 Key
        var format: String = "opus" // 音频格式
        var sampleRate: Int = 16000 // 采样率
    }

    /**
     * 阿里云 TTS 配置
     */
    class TtsConfig {
        var appKey: String? = null // 应用 Key
        var accessKey: String? = null // 访问 Key
        var voice: String = "xiaoyun" // 音色
        var format: String = "mp3" // 音频格式
        var sampleRate: Int = 16000 // 采样率
    }

    /**
     * 限流配置
     */
    class RateLimitConfig {
        var maxPerSession: Int = 10 // 单会话每秒最大消息数
        var maxPerIp: Int = 3 // 单 IP 每小时最大面试数
        var maxConcurrent: Int = 50 // 全局最大并发面试数
    }

    /**
     * 音频参数配置
     */
    class AudioConfig {
        var codec: String = "opus" // 编码格式
        var sampleRate: Int = 16000 // 采样率
        var bitRate: Int = 24000 // 码率
        var channels: Int = 1 // 声道数
        var chunkDuration: Int = 2000 // 分块时长（毫秒）// 2 seconds
    }

    /**
     * Qwen3 Realtime 配置
     */
    class QwenConfig {
        var asr: AsrConfig = AsrConfig() // 语音识别配置
        var tts: QwenTtsConfig = QwenTtsConfig() // 语音合成配置
    }

    /**
     * Qwen3 Realtime ASR 配置
     */
    class AsrConfig {
        var url: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime" // WebSocket 地址
        var model: String = "qwen3-asr-flash-realtime" // 模型名称
        var apiKey: String? = null // DashScope API Key
        var language: String = "zh" // 识别语言
        var format: String = "pcm" // 音频格式
        var sampleRate: Int = 16000 // 采样率
        var enableTurnDetection: Boolean = true // 是否启用 VAD 轮次检测
        var turnDetectionType: String = "server_vad" // VAD 类型
        var turnDetectionThreshold: Float = 0.0f // VAD 阈值
        var turnDetectionSilenceDurationMs: Int = 1000 // 静音判定时长（毫秒）
    }

    /**
     * Qwen3 Realtime TTS 配置
     */
    class QwenTtsConfig {
        var model: String = "qwen3-tts-flash-realtime" // 模型名称
        var apiKey: String? = null // DashScope API Key
        var voice: String = "Cherry" // 音色
        var format: String = "pcm" // 音频格式
        var sampleRate: Int = 24000 // 采样率
        var mode: String = "commit" // 合成模式
        var languageType: String = "Chinese" // 语言类型
        var speechRate: Float = 1.0f // 语速
        var volume: Int = 60 // 音量
    }

    /**
     * 开场白配置
     */
    class OpeningConfig {
        var skillQuestions: MutableMap<String, String> = linkedMapOf() // 各技能模板对应的开场问题
        var algorithmSkills: MutableList<String> = mutableListOf("bytedance-backend", "algorithm") // 算法方向技能
        var algorithmQuestion: String =
            "你好，我是本场面试官。先做一道算法与数据结构热身题：请你从“哈希表/堆/栈/队列/树/图”里选两个，结合一道你熟悉的题，口述“为什么选这个结构、核心步骤、时间复杂度、空间复杂度、边界条件与反例”。本场不需要写代码，重点看你的思路和取舍。"
        var backendQuestion: String =
            "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。"
    }

    /**
     * 上下文压缩配置
     */
    class ContextCompressionConfig {
        /**
         * 是否启用上下文压缩。enabled=false 是唯一关闭方式：返回全量历史，仅用于本地排查。
         * enabled=true 时无论何种模式都执行字符硬预算。
         */
        var enabled: Boolean = true

        /**
         * 压缩模式：NONE=不摘要不裁窗口但仍有字符硬预算；WINDOW=窗口+字符硬预算；
         * SUMMARY=摘要+窗口+字符硬预算（生产默认）。
         */
        var mode: Mode = Mode.SUMMARY

        /**
         * 保留的最近轮次数量（滑动窗口大小）。
         * 注意：SUMMARY 模式在两次摘要批次之间最多保留 windowSize + summaryBatchSize - 1 轮，
         * 轮数有界；Prompt 的最终硬上限由 maxHistoryChars 字符预算保证。
         */
        @field:Min(value = 1, message = "windowSize 必须大于等于 1")
        var windowSize: Int = 20

        /**
         * 早期轮次每越过该批次数，触发一次增量摘要合并（降低 LLM 摘要调用频率）。
         */
        @field:Min(value = 1, message = "summaryBatchSize 必须大于等于 1")
        var summaryBatchSize: Int = 10

        /**
         * 历史总字符预算：摘要字符数 + 近期历史格式化后字符数 <= maxHistoryChars。
         */
        @field:Min(value = 1, message = "maxHistoryChars 必须大于 0")
        var maxHistoryChars: Int = 12000

        /**
         * 摘要字符上限：摘要字符数 <= maxSummaryChars，超出时从尾部截断。
         */
        @field:Min(value = 0, message = "maxSummaryChars 不能为负数")
        var maxSummaryChars: Int = 4000

        /**
         * 摘要占满上限后至少要给近期消息留出空间。
         */
        @get:AssertTrue(message = "maxHistoryChars 必须大于等于 maxSummaryChars")
        val isBudgetConsistent: Boolean
            get() = maxHistoryChars >= maxSummaryChars

        /**
         * SUMMARY 模式依赖摘要，摘要上限必须为正数。
         */
        @get:AssertTrue(message = "SUMMARY 模式下 maxSummaryChars 必须大于 0")
        val isSummaryBudgetPositive: Boolean
            get() = mode != Mode.SUMMARY || maxSummaryChars > 0
    }

    /**
     * 上下文压缩模式
     */
    enum class Mode {
        NONE, // 不摘要、不裁窗口，仅字符硬预算
        WINDOW, // 最近窗口 + 字符硬预算
        SUMMARY // 摘要 + 最近窗口 + 字符硬预算
    }
}

package interview.guide.modules.voiceinterview.dto

import java.time.LocalDateTime

/**
 * 语音面试消息 DTO
 */
data class VoiceInterviewMessageVo(
    var id: Long? = null, // 消息ID
    var sessionId: Long? = null, // 会话ID
    var messageType: String? = null, // 消息类型
    var phase: String? = null, // 所属阶段
    var userRecognizedText: String? = null, // 用户语音识别文本
    var aiGeneratedText: String? = null, // AI 生成文本
    var timestamp: LocalDateTime? = null, // 消息时间
    var sequenceNum: Int? = null // 会话内序号
)

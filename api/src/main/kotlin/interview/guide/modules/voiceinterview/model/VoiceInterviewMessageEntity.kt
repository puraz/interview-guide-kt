package interview.guide.modules.voiceinterview.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 语音面试消息实体
 */
@Entity
@Table(name = "voice_interview_messages")
open class VoiceInterviewMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @Column(name = "session_id")
    var sessionId: Long? = null // 会话ID

    @Column(name = "message_type", nullable = false)
    var messageType: String? = null // USER_SPEECH, AI_SPEECH, SYSTEM, SUMMARY

    @Column(name = "phase")
    @Enumerated(EnumType.STRING)
    var phase: VoiceInterviewSessionEntity.InterviewPhase? = null // 所属面试阶段

    @Column(name = "user_recognized_text", columnDefinition = "TEXT")
    var userRecognizedText: String? = null // 用户语音识别文本

    @Column(name = "ai_generated_text", columnDefinition = "TEXT")
    var aiGeneratedText: String? = null // AI 生成文本

    @Column(name = "timestamp")
    var timestamp: LocalDateTime? = null // 消息时间

    @Column(name = "sequence_num")
    var sequenceNum: Int? = null // 会话内序号

    /**
     * 摘要覆盖边界：SUMMARY 行专用，记录已摘要到的最大原始消息 sequenceNum。
     * 旧数据为 null（旧版以负 sequenceNum 编码轮数），由一次性迁移写入。
     */
    @Column(name = "summary_covered_sequence_num")
    var summaryCoveredSequenceNum: Int? = null // 摘要覆盖边界

    @Column(name = "created_at")
    var createdAt: LocalDateTime? = null // 创建时间

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        timestamp = LocalDateTime.now()
    }

    companion object {
        /**
         * 对话摘要行类型。用于持久化上下文压缩产生的滚动摘要，
         * 其 sequenceNum 取负值以在排序时位于普通轮次之前，
         * 并通过 `-(sequenceNum + 1)` 编码已覆盖的轮次数（coveredTurns）。
         */
        const val MESSAGE_TYPE_SUMMARY: String = "SUMMARY"

        /**
         * 去除首尾空白；null / 空白文本归一为 null
         *
         * @param text 原始文本 // 可能为 null
         * @return 归一化后的文本 // 空白返回 null
         */
        @JvmStatic
        fun trimToNull(text: String?): String? {
            if (text == null || text.isBlank()) {
                return null
            }
            return text.trim()
        }
    }
}

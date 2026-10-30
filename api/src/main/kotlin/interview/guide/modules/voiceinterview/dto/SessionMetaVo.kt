package interview.guide.modules.voiceinterview.dto

import java.time.LocalDateTime

/**
 * Session metadata for list display
 * 会话列表展示用元数据
 */
data class SessionMetaVo(
    var sessionId: Long? = null, // 会话ID
    var roleType: String? = null, // 面试角色类型
    var status: String? = null, // 会话状态
    var currentPhase: String? = null, // 当前阶段
    var createdAt: LocalDateTime? = null, // 创建时间
    var updatedAt: LocalDateTime? = null, // 更新时间
    var actualDuration: Int? = null, // 实际时长（秒）
    var messageCount: Long? = null, // 对话消息数量
    var evaluateStatus: String? = null, // 评估状态
    var evaluateError: String? = null // 评估错误信息
)

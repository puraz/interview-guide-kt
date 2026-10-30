package interview.guide.modules.voiceinterview.dto

import java.time.LocalDateTime

/**
 * Session response with WebSocket URL
 * 会话响应（含 WebSocket 地址）
 */
data class SessionResponseVo(
    var sessionId: Long? = null, // 会话ID
    var roleType: String? = null, // 面试角色类型
    var currentPhase: String? = null, // 当前阶段
    var status: String? = null, // 会话状态
    var startTime: LocalDateTime? = null, // 开始时间
    var plannedDuration: Int? = null, // 计划时长（分钟）
    /** 相对于 API 服务入口的路径，由客户端根据部署地址选择 ws/wss。 */
    var webSocketUrl: String? = null
)

package interview.guide.modules.voiceinterview.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * WebSocket 控制消息（客户端 -> 服务端）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WebSocketControlMessage(
    var type: String? = null, // "control"
    var action: String? = null, // "start_phase", "end_phase", "end_interview", "submit"
    var phase: String? = null, // "INTRO", "TECH", "PROJECT", "HR"
    var data: Map<String, Any?>? = null // 附加数据（submit 时包含 text）
)

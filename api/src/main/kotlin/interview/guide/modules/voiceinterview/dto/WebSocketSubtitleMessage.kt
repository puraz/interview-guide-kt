package interview.guide.modules.voiceinterview.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * WebSocket 字幕消息（服务端 -> 客户端）
 */
data class WebSocketSubtitleMessage(
    var type: String? = null, // "subtitle"
    var text: String? = null, // 字幕文本
    @get:JsonProperty("isFinal")
    @param:JsonProperty("isFinal")
    var isFinal: Boolean? = null // 是否为最终结果
)

package interview.guide.modules.voiceinterview.config

import interview.guide.common.config.CorsProperties
import interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor

/**
 * 语音面试 WebSocket 配置
 * 注册 /ws/voice-interview/{sessionId} 处理器并放宽音视频消息大小限制
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val voiceInterviewWebSocketHandler: VoiceInterviewWebSocketHandler, // 语音面试 WebSocket 处理器
    private val corsProperties: CorsProperties // 跨域配置属性
) : WebSocketConfigurer {

    /**
     * 注册语音面试 WebSocket 处理器
     */
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
            .addInterceptors(HttpSessionHandshakeInterceptor())
            .setAllowedOrigins(*corsProperties.allowedOrigins.split(",").toTypedArray())
    }

    /**
     * 配置 WebSocket 容器的文本/二进制消息大小上限（音频流式传输需要更大的缓冲）
     */
    @Bean
    fun createWebSocketContainer(): ServletServerContainerFactoryBean {
        val container = ServletServerContainerFactoryBean()
        container.maxTextMessageBufferSize = 2 * 1024 * 1024
        container.maxBinaryMessageBufferSize = 2 * 1024 * 1024
        return container
    }
}

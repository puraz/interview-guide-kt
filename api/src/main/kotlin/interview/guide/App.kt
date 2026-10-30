package interview.guide

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * AI Interview Platform - 主启动类
 *
 * 排除 Spring AI 官方 OpenAI 自动配置：Provider 由 app.ai.providers / llm_provider_config 驱动，
 * ChatClient 与 EmbeddingModel 统一由 LlmProviderRegistry、LlmEmbeddingConfig 创建。
 */
@EnableScheduling
@SpringBootApplication(
    exclude = [
        OpenAiAudioSpeechAutoConfiguration::class,
        OpenAiAudioTranscriptionAutoConfiguration::class,
        OpenAiChatAutoConfiguration::class,
        OpenAiEmbeddingAutoConfiguration::class,
        OpenAiImageAutoConfiguration::class,
        OpenAiModerationAutoConfiguration::class
    ]
)
class App

private val log: Logger = LoggerFactory.getLogger(App::class.java)

/**
 * 应用入口
 *
 * @param args 启动参数 // Spring Boot 启动参数
 */
fun main(args: Array<String>) {
    val context = runApplication<App>(*args)
    val env = context.environment
    log.info(
        """
----------------------------------------------------------
\t项目 '${env.getProperty("spring.application.name")}' 启动成功! 访问连接:
\tSwagger文档: \t http://127.0.0.1:${env.getProperty("server.port", "8080")}/swagger-ui.html
\t数据库监控: \t\t http://127.0.0.1:${env.getProperty("server.port", "8080")}/druid
----------------------------------------------------------
        """.trimIndent()
    )
}

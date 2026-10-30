package interview.guide.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * LLM Provider 配置属性（对应 app.ai.*）
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
class LlmProviderProperties {

    var defaultProvider: String = "dashscope" // 默认对话 Provider ID

    var defaultEmbeddingProvider: String? = null // 默认向量 Provider ID（为空时回退到 defaultProvider）

    var embeddingDimensions: Int = 1024 // 默认向量维度

    var providers: MutableMap<String, ProviderConfig>? = null // 各 Provider 的静态配置（仅非数据库模式/种子数据使用）

    var advisors: AdvisorConfig? = AdvisorConfig() // Advisor 配置

    var configYamlPath: String? = null // 运行时可写 YAML 配置文件路径

    var configEnvPath: String? = null // 运行时可写 .env 文件路径

    var security: SecurityConfig? = SecurityConfig() // 密钥安全配置

    /**
     * 单个 Provider 的静态配置
     */
    class ProviderConfig {
        var baseUrl: String? = null // 服务基础地址
        var apiKey: String? = null // API Key（明文，仅配置态）
        var model: String? = null // 对话模型名称
        var embeddingModel: String? = null // 向量模型名称
        var embeddingDimensions: Int? = null // 向量维度
        var supportsEmbedding: Boolean? = null // 是否支持 Embedding
        var temperature: Double? = null // 采样温度
    }

    /**
     * API Key 加密相关安全配置
     */
    class SecurityConfig {
        var apiKeyEncryptionKey: String? = null // API Key 加密主密钥
        var requireEncryptionKey: Boolean = true // 是否强制要求配置加密密钥
        var allowFallbackEncryptionKey: Boolean = false // 是否允许使用开发用 fallback 密钥
    }

    /**
     * Advisor 配置
     */
    class AdvisorConfig {
        var enabled: Boolean = true // 是否启用默认 Advisor 集合

        // ToolCallAdvisor
        var toolCallEnabled: Boolean = true // 是否启用工具调用 Advisor
        var toolCallConversationHistoryEnabled: Boolean = false // 工具调用 Advisor 是否携带会话历史

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        var messageChatMemoryEnabled: Boolean = false // 是否启用对话记忆 Advisor
        var messageChatMemoryMaxMessages: Int = 120 // 记忆窗口最大消息数

        // SimpleLoggerAdvisor（默认关闭）
        var simpleLoggerEnabled: Boolean = false // 是否启用请求日志 Advisor

        // SafeGuardAdvisor
        var safeguardEnabled: Boolean = true // 是否启用安全护栏 Advisor
        var safeguardWords: MutableList<String> = mutableListOf( // 触发安全护栏的敏感词
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        )

        // PromptSanitizer
        var promptSanitizerEnabled: Boolean = true // 是否启用 Prompt 注入净化
    }
}

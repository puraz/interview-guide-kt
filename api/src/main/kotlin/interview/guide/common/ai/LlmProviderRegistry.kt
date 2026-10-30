package interview.guide.common.ai

import interview.guide.common.config.LlmProviderProperties
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig
import interview.guide.common.config.LlmProviderProperties.ProviderConfig
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity
import interview.guide.modules.llmprovider.model.LlmProviderEntity
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository
import interview.guide.modules.llmprovider.repository.LlmProviderRepository
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
class LlmProviderRegistry(
    private val properties: LlmProviderProperties, // Provider/Advisor 配置
    private val providerRepository: LlmProviderRepository? = null, // Provider 配置仓库（DB 模式）
    private val globalSettingRepository: LlmGlobalSettingRepository? = null, // 全局默认 Provider 设置仓库
    private val encryptionService: ApiKeyEncryptionService? = null, // API Key 加解密服务
    @Autowired(required = false) private val toolCallingManager: ToolCallingManager? = null, // 工具调用管理器（可选）
    @Autowired(required = false) private val observationRegistry: ObservationRegistry? = null, // 观测注册表（可选）
    @Autowired(required = false) @Qualifier("interviewSkillsToolCallback")
    private val interviewSkillsToolCallback: ToolCallback? = null // 面试技能工具回调（可选）
) {

    private val log = LoggerFactory.getLogger(LlmProviderRegistry::class.java)

    private val clientCache = ConcurrentHashMap<String, ChatClient>() // ChatClient 缓存
    private val chatModelCache = ConcurrentHashMap<String, OpenAiChatModel>() // ChatModel 缓存
    private val embeddingModelCache = ConcurrentHashMap<String, EmbeddingModel>() // EmbeddingModel 缓存

    /**
     * Get a ChatClient for the specified provider ID.
     * If the client is not in the cache, it will be created based on the provider's configuration.
     *
     * @param providerId The ID of the provider (e.g., "dashscope", "lmstudio")
     * @return A ChatClient instance
     * @throws IllegalArgumentException if the providerId is unknown
     */
    fun getChatClient(providerId: String): ChatClient {
        return clientCache.computeIfAbsent(providerId) { id ->
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id)
            createChatClient(id)
        }
    }

    /**
     * Get the default ChatClient based on app.ai.default-provider.
     *
     * @return The default ChatClient instance
     */
    fun getDefaultChatClient(): ChatClient {
        return getChatClient(resolveDefaultChatProviderId())
    }

    /**
     * 获取默认 Provider 的 Plain ChatClient，当前用于知识库查询。
     * 与 [getDefaultChatClient] 的区别在于不注册工具回调、ToolCall、Memory 或 Logger Advisor，
     * 仅按配置注册 SafeGuardAdvisor。
     *
     * @return Plain ChatClient // 无工具、无记忆的客户端
     */
    fun getPlainChatClient(): ChatClient {
        return getPlainChatClient(resolveDefaultChatProviderId())
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null, blank, or
     * the legacy "default" alias.
     *
     * @param providerId 指定 Provider ID // 可为 null/空白/default
     * @return ChatClient // 解析后的客户端
     */
    fun getChatClientOrDefault(providerId: String?): ChatClient {
        return getChatClient(resolveProviderId(providerId))
    }

    /**
     * 获取指定 Provider 的 Plain ChatClient，当前用于文字方向题、简历题及知识库面试出题。
     * 不注册工具回调，仅按配置注册 SafeGuardAdvisor，减少工具调用对结构化输出的干扰。
     * 面试评分和简历分析当前仍使用默认类型的 ChatClient；结构化输出不会自动切换为 Plain。
     *
     * @param providerId 指定 Provider ID // 可为 null/空白/default
     * @return Plain ChatClient // 无工具、无记忆的客户端
     * @see getChatClientOrDefault
     * @see getDefaultChatClient
     */
    fun getPlainChatClient(providerId: String?): ChatClient {
        val id = resolveProviderId(providerId)
        return clientCache.computeIfAbsent("$id:plain") { createPlainChatClient(id) }
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     *
     * @param providerId 指定 Provider ID // 可为 null/空白/default
     * @return 语音面试 ChatClient // 含 SkillsTool 与流式 ToolCall
     */
    fun getVoiceChatClient(providerId: String?): ChatClient {
        val id = resolveProviderId(providerId)
        return clientCache.computeIfAbsent("$id:voice") { createVoiceChatClient(id) }
    }

    /**
     * 清空缓存，重新加载所有 provider。
     */
    fun reload() {
        val size = clientCache.size + chatModelCache.size + embeddingModelCache.size
        clientCache.clear()
        chatModelCache.clear()
        embeddingModelCache.clear()
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size)
    }

    /**
     * 获取指定 Provider 的 EmbeddingModel
     *
     * @param providerId Provider ID // 需支持 Embedding
     * @return EmbeddingModel // 向量模型实例
     */
    fun getEmbeddingModel(providerId: String): EmbeddingModel {
        return embeddingModelCache.computeIfAbsent(providerId) { id ->
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id)
            createEmbeddingModel(id)
        }
    }

    /**
     * 获取默认 Provider 的 EmbeddingModel
     *
     * @return EmbeddingModel // 默认向量模型
     */
    fun getDefaultEmbeddingModel(): EmbeddingModel {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId())
    }

    /**
     * 创建标准 ChatClient：注册 SkillsTool 与默认 Advisor 集合
     *
     * @param providerId Provider ID
     * @return ChatClient // 含工具与 Advisor
     */
    private fun createChatClient(providerId: String): ChatClient {
        val chatModel = getChatModel(providerId)

        val builder = ChatClient.builder(chatModel)
        val skillsToolCallback = interviewSkillsToolCallback
        if (skillsToolCallback != null) {
            builder.defaultTools(skillsToolCallback)
        }
        val advisors = buildDefaultAdvisors(providerId)
        if (advisors.isNotEmpty()) {
            builder.defaultAdvisors(advisors)
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size, providerId)
        }

        return builder.build()
    }

    /**
     * 创建 Plain ChatClient：不注册工具回调，仅按配置注册 SafeGuardAdvisor
     *
     * @param providerId Provider ID
     * @return ChatClient // 无工具的客户端
     */
    private fun createPlainChatClient(providerId: String): ChatClient {
        val chatModel = getChatModel(providerId)
        val builder = ChatClient.builder(chatModel)
        val advisors = ArrayList<Advisor>()
        buildSafeGuardAdvisor()?.let { advisors.add(it) }
        if (advisors.isNotEmpty()) {
            builder.defaultAdvisors(advisors)
        }
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId)
        return builder.build()
    }

    /**
     * 创建语音面试 ChatClient：SkillsTool + 流式 ToolCallAdvisor + SafeGuardAdvisor
     *
     * @param providerId Provider ID
     * @return ChatClient // 语音面试专用客户端
     */
    private fun createVoiceChatClient(providerId: String): ChatClient {
        val chatModel = getChatModel(providerId)

        val builder = ChatClient.builder(chatModel)
        val skillsToolCallback = interviewSkillsToolCallback
        if (skillsToolCallback != null) {
            builder.defaultTools(skillsToolCallback)
        }
        val advisors = ArrayList<Advisor>()
        val manager = toolCallingManager
        if (manager != null) {
            advisors.add(buildToolCallAdvisor(manager, true))
        }
        buildSafeGuardAdvisor()?.let { advisors.add(it) }
        if (advisors.isNotEmpty()) {
            builder.defaultAdvisors(advisors)
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId)
        return builder.build()
    }

    /**
     * 获取（并缓存）指定 Provider 的 ChatModel
     *
     * @param providerId Provider ID
     * @return OpenAiChatModel // 对话模型
     */
    private fun getChatModel(providerId: String): OpenAiChatModel {
        return chatModelCache.computeIfAbsent(providerId) { id ->
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id)
            buildChatModel(id)
        }
    }

    /**
     * 按 Provider 配置构建 ChatModel
     *
     * @param providerId Provider ID
     * @return OpenAiChatModel // 对话模型
     */
    private fun buildChatModel(providerId: String): OpenAiChatModel {
        val config = loadProviderOrThrow(providerId)
        log.info(
            "[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl, config.model
        )

        val openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl, config.apiKey)

        val options = OpenAiChatOptions.builder()
            .model(config.model.orEmpty())
            .temperature(config.temperature ?: 0.2)
            .build()

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry ?: ObservationRegistry.NOOP)
            .build()
    }

    /**
     * 按 Provider 配置构建 EmbeddingModel
     *
     * @param providerId Provider ID
     * @return EmbeddingModel // 向量模型
     */
    private fun createEmbeddingModel(providerId: String): EmbeddingModel {
        val config = loadProviderOrThrow(providerId)
        val embeddingModel: String = config.embeddingModel?.takeIf { !isBlank(it) } ?: ""
        if (!config.supportsEmbedding || embeddingModel.isEmpty()) {
            throw BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化"
            )
        }
        // 模型名和自定义别名不能证明能力；实际支持情况由 Embedding 接口确认。
        log.info(
            "[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl, embeddingModel
        )

        val openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl, config.apiKey)
        val options = OpenAiEmbeddingOptions.builder()
            .model(embeddingModel)
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions))
            .build()

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry ?: ObservationRegistry.NOOP)
            .build()
    }

    /**
     * 构建默认 Advisor 集合：ToolCall / Memory / Logger / SafeGuard
     *
     * @param providerId Provider ID // 仅用于日志
     * @return Advisor 列表 // 未启用时返回空列表
     */
    private fun buildDefaultAdvisors(providerId: String): List<Advisor> {
        val config: AdvisorConfig = properties.advisors ?: return emptyList()
        if (!config.enabled) {
            return emptyList()
        }

        val advisors = ArrayList<Advisor>()

        if (config.toolCallEnabled) {
            val manager = toolCallingManager
            if (manager != null) {
                advisors.add(buildToolCallAdvisor(manager, config.toolCallConversationHistoryEnabled))
            } else {
                log.warn(
                    "[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}",
                    providerId
                )
            }
        }

        if (config.messageChatMemoryEnabled) {
            val maxMessages = maxOf(20, config.messageChatMemoryMaxMessages)
            val memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build()
            advisors.add(memoryAdvisor)
        }

        if (config.simpleLoggerEnabled) {
            advisors.add(SimpleLoggerAdvisor())
        }

        buildSafeGuardAdvisor()?.let { advisors.add(it) }

        return advisors
    }

    /**
     * 构建 ToolCallAdvisor
     *
     * @param manager 工具调用管理器 // 非空
     * @param conversationHistoryEnabled 是否携带会话历史
     * @return ToolCallingAdvisor // 工具调用 Advisor
     */
    private fun buildToolCallAdvisor(manager: ToolCallingManager, conversationHistoryEnabled: Boolean): ToolCallingAdvisor {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(manager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build()
    }

    /**
     * 按配置构建 SafeGuardAdvisor
     *
     * @return SafeGuardAdvisor // 未启用时返回 null
     */
    private fun buildSafeGuardAdvisor(): SafeGuardAdvisor? {
        val config: AdvisorConfig = properties.advisors ?: return null
        if (!config.safeguardEnabled) {
            return null
        }
        return SafeGuardAdvisor.builder()
            .sensitiveWords(config.safeguardWords)
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build()
    }

    /**
     * 解析 Provider ID：null/空白/default 别名回退到默认对话 Provider
     *
     * @param providerId 原始 Provider ID
     * @return 实际使用的 Provider ID
     */
    private fun resolveProviderId(providerId: String?): String {
        if (providerId == null || providerId.isBlank() || "default".equals(providerId.trim(), ignoreCase = true)) {
            return resolveDefaultChatProviderId()
        }
        return providerId
    }

    /**
     * 解析默认对话 Provider ID：数据库全局设置优先，其次配置文件
     *
     * @return 默认对话 Provider ID
     */
    private fun resolveDefaultChatProviderId(): String {
        val repository = globalSettingRepository ?: return properties.defaultProvider
        val configured = repository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map { it.defaultChatProviderId }
            .orElse(null)
        return configured?.takeIf { !isBlank(it) } ?: properties.defaultProvider
    }

    /**
     * 解析默认向量 Provider ID：数据库全局设置优先，其次配置文件的 defaultEmbeddingProvider/defaultProvider
     *
     * @return 默认向量 Provider ID
     */
    private fun resolveDefaultEmbeddingProviderId(): String {
        val configuredFallback = properties.defaultEmbeddingProvider?.takeIf { !isBlank(it) }
            ?: properties.defaultProvider
        val repository = globalSettingRepository ?: return configuredFallback
        val configured = repository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map { it.defaultEmbeddingProviderId }
            .orElse(null)
        return configured?.takeIf { !isBlank(it) } ?: configuredFallback
    }

    /**
     * 加载 Provider 快照：DB 模式读表并解密，否则读配置文件
     *
     * @param providerId Provider ID
     * @return Provider 运行时快照
     */
    private fun loadProviderOrThrow(providerId: String): ProviderSnapshot {
        val repository = providerRepository ?: return loadProviderFromPropertiesOrThrow(providerId)
        val entity: LlmProviderEntity = repository.findById(providerId)
            .filter { it.enabled }
            .orElseThrow { IllegalArgumentException("Unknown LLM provider: $providerId") }
        val encryption = encryptionService
            ?: throw IllegalArgumentException("Unknown LLM provider: $providerId")
        return ProviderSnapshot(
            entity.id,
            entity.baseUrl,
            encryption.decrypt(entity.apiKeyNonce.orEmpty(), entity.apiKeyCiphertext.orEmpty()),
            entity.model,
            entity.embeddingModel,
            entity.embeddingDimensions,
            entity.supportsEmbedding,
            entity.temperature
        )
    }

    /**
     * 从配置文件的 app.ai.providers 加载 Provider 快照
     *
     * @param providerId Provider ID
     * @return Provider 运行时快照
     */
    private fun loadProviderFromPropertiesOrThrow(providerId: String): ProviderSnapshot {
        val config: ProviderConfig? = properties.providers?.get(providerId)
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId)
            throw IllegalArgumentException("Unknown LLM provider: $providerId")
        }
        val supportsEmbedding = config.supportsEmbedding == true || !isBlank(config.embeddingModel)
        return ProviderSnapshot(
            providerId,
            config.baseUrl,
            config.apiKey.orEmpty(),
            config.model,
            config.embeddingModel,
            config.embeddingDimensions,
            supportsEmbedding,
            config.temperature
        )
    }

    /**
     * 判断字符串是否为空白
     *
     * @param value 字符串 // 可为 null
     * @return true=空白
     */
    private fun isBlank(value: String?): Boolean {
        return value == null || value.isBlank()
    }

    /**
     * 解析向量维度：Provider 配置优先，否则回退全局配置
     *
     * @param configuredDimensions Provider 级向量维度 // 可为 null
     * @return 生效的向量维度
     */
    private fun resolveEmbeddingDimensions(configuredDimensions: Int?): Int {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions
        }
        return properties.embeddingDimensions
    }

    /**
     * Provider 运行时快照
     *
     * @property id Provider ID
     * @property baseUrl 服务基础地址
     * @property apiKey 解密后的 API Key
     * @property model 对话模型
     * @property embeddingModel 向量模型
     * @property embeddingDimensions 向量维度
     * @property supportsEmbedding 是否支持向量化
     * @property temperature 采样温度
     */
    private data class ProviderSnapshot(
        val id: String?,
        val baseUrl: String?,
        val apiKey: String,
        val model: String?,
        val embeddingModel: String?,
        val embeddingDimensions: Int?,
        val supportsEmbedding: Boolean,
        val temperature: Double?
    )
}

package interview.guide.modules.llmprovider.service

import interview.guide.common.config.LlmProviderProperties
import interview.guide.common.config.LlmProviderProperties.ProviderConfig
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity
import interview.guide.modules.llmprovider.model.LlmProviderEntity
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository
import interview.guide.modules.llmprovider.repository.LlmProviderRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Provider 启动引导服务：首次启动时把 app.ai.providers 种子数据写入数据库，并初始化全局默认设置。
 */
@Service
class LlmProviderBootstrapService(
    private val properties: LlmProviderProperties, // Provider 配置属性
    private val providerRepository: LlmProviderRepository, // Provider 仓库
    private val globalSettingRepository: LlmGlobalSettingRepository, // 全局设置仓库
    private val encryptionService: ApiKeyEncryptionService // API Key 加密服务
) {

    private val log = LoggerFactory.getLogger(LlmProviderBootstrapService::class.java)

    /**
     * 应用启动时执行：无 Provider 时写入种子数据，并确保全局默认设置存在
     */
    @PostConstruct
    @Transactional
    fun seedProvidersIfNecessary() {
        if (providerRepository.count() == 0L) {
            seedProviders()
        }
        ensureGlobalSetting()
    }

    /**
     * 从 application 配置写入种子 Provider
     */
    private fun seedProviders() {
        val providers = properties.providers
        if (providers.isNullOrEmpty()) {
            log.warn("No app.ai.providers seed configuration found")
            return
        }

        providers.forEach { (id, config) ->
            if (isBlank(id) || isBlank(config.baseUrl) || isBlank(config.model)) {
                log.warn("Skip invalid provider seed: id={}", id)
                return@forEach
            }
            val encrypted = encryptionService.encrypt(config.apiKey ?: "")
            val supportsEmbedding = config.supportsEmbedding == true || !isBlank(config.embeddingModel)

            val entity = LlmProviderEntity().apply {
                this.id = id
                this.baseUrl = config.baseUrl
                this.apiKeyNonce = encrypted.nonce
                this.apiKeyCiphertext = encrypted.ciphertext
                this.model = config.model
                this.embeddingModel = trimOrNull(config.embeddingModel)
                this.embeddingDimensions = resolveEmbeddingDimensions(config.embeddingDimensions)
                this.supportsEmbedding = supportsEmbedding
                this.temperature = config.temperature
                this.enabled = true
                this.builtin = true
            }
            providerRepository.save(entity)
        }
        log.info("Seeded {} LLM providers from application configuration", providerRepository.count())
    }

    /**
     * 确保全局默认 Provider 设置存在
     */
    private fun ensureGlobalSetting() {
        if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
            return
        }
        val fallbackChatProvider = providerRepository.findAll().firstOrNull()?.id ?: "dashscope"
        val defaultChatProvider = resolveExistingProvider(properties.defaultProvider, fallbackChatProvider)
        val configuredEmbeddingProvider =
            properties.defaultEmbeddingProvider?.takeIf { !isBlank(it) } ?: defaultChatProvider
        val defaultEmbeddingProvider =
            resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider)

        val setting = LlmGlobalSettingEntity().apply {
            this.id = LlmGlobalSettingEntity.SINGLETON_ID
            this.defaultChatProviderId = defaultChatProvider
            this.defaultEmbeddingProviderId = defaultEmbeddingProvider
        }
        globalSettingRepository.save(setting)
        log.info(
            "Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
            defaultChatProvider, defaultEmbeddingProvider
        )
    }

    /**
     * 校验首选 Provider 是否存在，不存在时回退
     *
     * @param preferredProvider 首选 Provider ID // 可为空/空白
     * @param fallbackProvider 回退 Provider ID
     * @return 实际使用的 Provider ID
     */
    private fun resolveExistingProvider(preferredProvider: String?, fallbackProvider: String): String {
        if (preferredProvider != null && preferredProvider.isNotBlank() && providerRepository.existsById(preferredProvider)) {
            return preferredProvider
        }
        return fallbackProvider
    }

    /**
     * 解析可用的默认向量 Provider：首选可用则用首选，否则取第一个支持 Embedding 的 Provider
     *
     * @param preferredProvider 首选 Provider ID
     * @param fallbackProvider 回退 Provider ID
     * @return 实际使用的向量 Provider ID
     */
    private fun resolveExistingEmbeddingProvider(preferredProvider: String, fallbackProvider: String): String {
        return providerRepository.findById(preferredProvider)
            .filter { canProvideEmbedding(it) }
            .map { it.id ?: preferredProvider }
            .orElseGet {
                providerRepository.findAll().firstOrNull { canProvideEmbedding(it) }?.id ?: fallbackProvider
            }
    }

    /**
     * 判断 Provider 是否可用于向量化
     *
     * @param provider Provider 实体
     * @return true=启用且配置了向量模型
     */
    private fun canProvideEmbedding(provider: LlmProviderEntity): Boolean {
        return provider.enabled && provider.supportsEmbedding && !isBlank(provider.embeddingModel)
    }

    /**
     * 解析向量维度：Provider 配置优先，否则回退全局配置
     *
     * @param configuredDimensions Provider 级维度 // 可为 null
     * @return 生效维度
     */
    private fun resolveEmbeddingDimensions(configuredDimensions: Int?): Int {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions
        }
        return properties.embeddingDimensions
    }

    /**
     * 去除首尾空白，空串转 null
     *
     * @param value 原始字符串 // 可为 null
     * @return 处理后的字符串
     */
    private fun trimOrNull(value: String?): String? {
        if (value == null) {
            return null
        }
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) null else trimmed
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
}

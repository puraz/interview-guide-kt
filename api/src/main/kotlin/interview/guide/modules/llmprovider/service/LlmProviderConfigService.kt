package interview.guide.modules.llmprovider.service

import interview.guide.common.ai.ApiPathResolver
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.config.LlmProviderProperties
import interview.guide.common.config.LlmProviderProperties.ProviderConfig
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.llmprovider.dto.AsrConfigDTO
import interview.guide.modules.llmprovider.dto.AsrConfigRequest
import interview.guide.modules.llmprovider.dto.CreateProviderRequest
import interview.guide.modules.llmprovider.dto.DefaultProviderVo
import interview.guide.modules.llmprovider.dto.ProviderTestResult
import interview.guide.modules.llmprovider.dto.ProviderVo
import interview.guide.modules.llmprovider.dto.TtsConfigDTO
import interview.guide.modules.llmprovider.dto.TtsConfigRequest
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity
import interview.guide.modules.llmprovider.model.LlmProviderEntity
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository
import interview.guide.modules.llmprovider.repository.LlmProviderRepository
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties
import interview.guide.modules.voiceinterview.service.QwenAsrService
import interview.guide.modules.voiceinterview.service.QwenTtsService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.InetAddressFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * LLM Provider 配置服务：负责 Provider 的增删改查、默认 Provider 切换、连通性测试，
 * 以及在“配置文件模式”下对 YAML / .env 配置的原地文本写入（保留注释与格式）。
 */
@Service
class LlmProviderConfigService(
    private val properties: LlmProviderProperties, // Provider 配置属性
    private val registry: LlmProviderRegistry, // Provider 注册表（缓存与客户端创建）
    private val providerRepository: LlmProviderRepository? = null, // Provider 仓库（DB 模式）
    private val globalSettingRepository: LlmGlobalSettingRepository? = null, // 全局设置仓库（DB 模式）
    private val encryptionService: ApiKeyEncryptionService? = null, // API Key 加解密服务（DB 模式）
    private val voiceProperties: VoiceInterviewProperties, // 语音面试配置属性
    private val asrService: QwenAsrService, // Qwen ASR 服务（配置变更后重载）
    private val ttsService: QwenTtsService // Qwen TTS 服务（配置变更后重载）
) {

    private val log = LoggerFactory.getLogger(LlmProviderConfigService::class.java)

    private val yamlPath: String? = properties.configYamlPath // 运行时可写 YAML 路径

    private val envPath: String? = properties.configEnvPath // 运行时可写 .env 路径

    private val rwLock = ReentrantReadWriteLock() // 读写锁：读多写少，写操作互斥

    /**
     * 校验可写路径：父目录可创建且可写，否则启动失败
     */
    @PostConstruct
    fun validateWritablePaths() {
        ensureParentWritable(yamlPath, "config-yaml-path")
        ensureParentWritable(envPath, "config-env-path")
    }

    /**
     * 校验配置文件父目录可写
     *
     * @param rawPath 配置的路径 // 可为空/空白（跳过）
     * @param label 配置项名称 // 日志与异常提示
     */
    private fun ensureParentWritable(rawPath: String?, label: String) {
        if (rawPath == null || rawPath.isBlank()) {
            log.warn("{} is not configured; runtime Provider edits will be skipped", label)
            return
        }
        val parent = Path.of(rawPath).toAbsolutePath().parent ?: return
        try {
            Files.createDirectories(parent)
        } catch (e: IOException) {
            throw BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "$label 的父目录不可创建: $parent", e)
        }
        if (!Files.isWritable(parent)) {
            throw BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "$label 的父目录不可写: $parent")
        }
        log.info("{} resolved to {} (parent writable)", label, rawPath)
    }

    // ===== Read operations (read lock) =====

    /**
     * 查询全部 Provider
     *
     * @return Provider 列表 // 已脱敏 API Key
     */
    fun listProviders(): List<ProviderVo> {
        rwLock.readLock().lock()
        try {
            if (!isDatabaseBacked()) {
                val providers = properties.providers ?: return emptyList()
                return providers.entries.map { entry ->
                    val config = entry.value
                    ProviderVo(
                        id = entry.key,
                        baseUrl = config.baseUrl,
                        maskedApiKey = maskApiKey(config.apiKey),
                        model = config.model,
                        embeddingModel = config.embeddingModel,
                        embeddingDimensions = resolveEmbeddingDimensions(config.embeddingDimensions),
                        supportsEmbedding = config.supportsEmbedding == true ||
                            trimOrNull(config.embeddingModel) != null,
                        temperature = config.temperature,
                        defaultChatProvider = entry.key == properties.defaultProvider,
                        defaultEmbeddingProvider = entry.key == properties.defaultEmbeddingProvider
                    )
                }
            }
            val setting = getGlobalSettingOrThrow()
            return providerRepo().findAll().map { provider ->
                ProviderVo(
                    id = provider.id,
                    baseUrl = provider.baseUrl,
                    maskedApiKey = maskApiKey(decryptApiKey(provider)),
                    model = provider.model,
                    embeddingModel = provider.embeddingModel,
                    embeddingDimensions = resolveEmbeddingDimensions(provider.embeddingDimensions),
                    supportsEmbedding = provider.supportsEmbedding,
                    temperature = provider.temperature,
                    defaultChatProvider = provider.id == setting.defaultChatProviderId,
                    defaultEmbeddingProvider = provider.id == setting.defaultEmbeddingProviderId
                )
            }
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 查询单个 Provider
     *
     * @param id Provider ID
     * @return Provider 详情 // API Key 已脱敏
     */
    fun getProvider(id: String): ProviderVo {
        rwLock.readLock().lock()
        try {
            if (!isDatabaseBacked()) {
                val config = getLegacyProviderConfigOrThrow(id)
                return ProviderVo(
                    id = id,
                    baseUrl = config.baseUrl,
                    maskedApiKey = maskApiKey(config.apiKey),
                    model = config.model,
                    embeddingModel = config.embeddingModel,
                    embeddingDimensions = resolveEmbeddingDimensions(config.embeddingDimensions),
                    supportsEmbedding = config.supportsEmbedding == true ||
                        trimOrNull(config.embeddingModel) != null,
                    temperature = config.temperature,
                    defaultChatProvider = id == properties.defaultProvider,
                    defaultEmbeddingProvider = id == properties.defaultEmbeddingProvider
                )
            }
            val setting = getGlobalSettingOrThrow()
            val provider = getProviderEntityOrThrow(id)
            return ProviderVo(
                id = id,
                baseUrl = provider.baseUrl,
                maskedApiKey = maskApiKey(decryptApiKey(provider)),
                model = provider.model,
                embeddingModel = provider.embeddingModel,
                embeddingDimensions = resolveEmbeddingDimensions(provider.embeddingDimensions),
                supportsEmbedding = provider.supportsEmbedding,
                temperature = provider.temperature,
                defaultChatProvider = id == setting.defaultChatProviderId,
                defaultEmbeddingProvider = id == setting.defaultEmbeddingProviderId
            )
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 查询默认 Provider 设置
     *
     * @return 默认对话/向量 Provider ID
     */
    fun getDefaultProvider(): DefaultProviderVo {
        rwLock.readLock().lock()
        try {
            if (!isDatabaseBacked()) {
                return DefaultProviderVo(properties.defaultProvider, properties.defaultEmbeddingProvider)
            }
            val setting = getGlobalSettingOrThrow()
            return DefaultProviderVo(
                setting.defaultChatProviderId,
                setting.defaultEmbeddingProviderId
            )
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 查询 ASR 配置
     *
     * @return ASR 配置 // API Key 已脱敏
     */
    fun getAsrConfig(): AsrConfigDTO {
        rwLock.readLock().lock()
        try {
            val asr = voiceProperties.qwen.asr
            return AsrConfigDTO(
                url = asr.url,
                model = asr.model,
                maskedApiKey = maskApiKey(asr.apiKey),
                language = asr.language,
                format = asr.format,
                sampleRate = asr.sampleRate,
                enableTurnDetection = asr.enableTurnDetection,
                turnDetectionType = asr.turnDetectionType,
                turnDetectionThreshold = asr.turnDetectionThreshold,
                turnDetectionSilenceDurationMs = asr.turnDetectionSilenceDurationMs
            )
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 查询 TTS 配置
     *
     * @return TTS 配置 // API Key 已脱敏
     */
    fun getTtsConfig(): TtsConfigDTO {
        rwLock.readLock().lock()
        try {
            val tts = voiceProperties.qwen.tts
            return TtsConfigDTO(
                model = tts.model,
                maskedApiKey = maskApiKey(tts.apiKey),
                voice = tts.voice,
                format = tts.format,
                sampleRate = tts.sampleRate,
                mode = tts.mode,
                languageType = tts.languageType,
                speechRate = tts.speechRate,
                volume = tts.volume
            )
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 测试 Provider 连通性
     *
     * @param id Provider ID
     * @return 测试结果 // success=true 表示连通
     */
    fun testProvider(id: String): ProviderTestResult {
        rwLock.readLock().lock()
        try {
            val config = if (isDatabaseBacked()) {
                getProviderRuntimeConfigOrThrow(id)
            } else {
                toRuntimeConfig(getLegacyProviderConfigOrThrow(id))
            }
            return doTestProvider(config, id)
        } finally {
            rwLock.readLock().unlock()
        }
    }

    /**
     * 测试 ASR 服务网络可达性（仅 TCP 端口探测）
     *
     * @return 测试结果 // success=true 表示端口可达
     */
    fun testAsrConfig(): ProviderTestResult {
        rwLock.readLock().lock()
        try {
            val asr = voiceProperties.qwen.asr
            try {
                val wsUri = URI.create(asr.url)
                val host = wsUri.host
                val port = if (wsUri.port > 0) wsUri.port else if (wsUri.scheme == "wss") 443 else 80
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                return ProviderTestResult(
                    success = true,
                    message = "ASR 服务网络端口可达: $host；尚未验证 API Key、模型权限及语音识别能力",
                    model = asr.model
                )
            } catch (e: Exception) {
                return ProviderTestResult(
                    success = false,
                    message = "ASR 连接失败: ${e.message}",
                    model = asr.model
                )
            }
        } finally {
            rwLock.readLock().unlock()
        }
    }

    // ===== Write operations (write lock) =====

    /**
     * 新建 Provider（DB 模式写库并清缓存；配置文件模式改写 YAML/.env）
     *
     * @param request 新建请求 // id/baseUrl/apiKey/model 必填
     */
    @Transactional
    fun createProvider(request: CreateProviderRequest) {
        rwLock.writeLock().lock()
        try {
            if (!isDatabaseBacked()) {
                createProviderLegacy(request)
                return
            }
            val providerId = requireNonBlank(request.id, "id")
            if (providerRepo().existsById(providerId)) {
                throw BusinessException(
                    ErrorCode.PROVIDER_ALREADY_EXISTS,
                    "Provider '" + request.id + "' 已存在"
                )
            }
            val baseUrl = requireNonBlank(request.baseUrl, "baseUrl")
            val model = requireNonBlank(request.model, "model")
            val apiKey = requireNonBlank(request.apiKey, "apiKey")
            val embeddingModel = trimOrNull(request.embeddingModel)
            val embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions)
            val requestedSupportsEmbedding = request.supportsEmbedding
            val supportsEmbedding = requestedSupportsEmbedding ?: (embeddingModel != null)
            validateEmbeddingConfig(supportsEmbedding, embeddingModel, embeddingDimensions)

            val encrypted = encryption().encrypt(apiKey)
            val entity = LlmProviderEntity().apply {
                this.id = providerId
                this.baseUrl = baseUrl
                this.apiKeyNonce = encrypted.nonce
                this.apiKeyCiphertext = encrypted.ciphertext
                this.model = model
                this.embeddingModel = embeddingModel
                this.embeddingDimensions = embeddingDimensions
                this.supportsEmbedding = supportsEmbedding
                this.temperature = request.temperature
                this.enabled = true
                this.builtin = false
            }
            providerRepo().save(entity)
            registry.reload()
            log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model)
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 更新 Provider（字段为 null 表示不修改）
     *
     * @param id Provider ID
     * @param request 更新请求
     */
    @Transactional
    fun updateProvider(id: String, request: UpdateProviderRequest) {
        rwLock.writeLock().lock()
        try {
            if (!isDatabaseBacked()) {
                updateProviderLegacy(id, request)
                return
            }
            val provider = getProviderEntityOrThrow(id)

            val trimmedBaseUrl = trimOrNull(request.baseUrl)
            if (request.baseUrl != null && trimmedBaseUrl == null) {
                throw BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串")
            }
            val trimmedModel = trimOrNull(request.model)
            if (request.model != null && trimmedModel == null) {
                throw BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串")
            }
            val trimmedApiKey = trimOrNull(request.apiKey)
            if (request.apiKey != null && trimmedApiKey == null) {
                throw BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串")
            }

            if (trimmedBaseUrl != null) provider.baseUrl = trimmedBaseUrl
            if (trimmedModel != null) provider.model = trimmedModel
            if (request.embeddingModel != null) {
                provider.embeddingModel = trimOrNull(request.embeddingModel)
            }
            if (request.embeddingDimensions != null) {
                provider.embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions)
            }
            val supportsEmbedding = request.supportsEmbedding
            if (supportsEmbedding != null) {
                provider.supportsEmbedding = supportsEmbedding
            }
            validateEmbeddingConfig(
                provider.supportsEmbedding,
                provider.embeddingModel,
                resolveEmbeddingDimensions(provider.embeddingDimensions)
            )
            val temperature = request.temperature
            if (temperature != null) {
                provider.temperature = temperature
            }
            if (trimmedApiKey != null) {
                val encrypted = encryption().encrypt(trimmedApiKey)
                provider.apiKeyNonce = encrypted.nonce
                provider.apiKeyCiphertext = encrypted.ciphertext
            }

            providerRepo().save(provider)
            registry.reload()
            log.info("Updated provider: id={}", id)
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 删除 Provider（默认 Provider 不可删除）
     *
     * @param id Provider ID
     */
    @Transactional
    fun deleteProvider(id: String) {
        rwLock.writeLock().lock()
        try {
            if (!isDatabaseBacked()) {
                deleteProviderLegacy(id)
                return
            }
            val setting = getGlobalSettingOrThrow()
            if (id == setting.defaultChatProviderId || id == setting.defaultEmbeddingProviderId) {
                throw BusinessException(
                    ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
                    "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider"
                )
            }
            getProviderEntityOrThrow(id)

            providerRepo().deleteById(id)
            registry.reload()
            log.info("Deleted provider: id={}", id)
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 切换默认对话 Provider
     *
     * @param request 请求体 // defaultProvider 必填
     */
    @Transactional
    fun updateDefaultProvider(request: DefaultProviderVo) {
        rwLock.writeLock().lock()
        try {
            if (!isDatabaseBacked()) {
                updateDefaultProviderLegacy(request)
                return
            }
            val providerId = trimOrNull(request.defaultProvider)
                ?: throw BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空")
            getProviderEntityOrThrow(providerId)
            val setting = getGlobalSettingOrThrow()
            setting.defaultChatProviderId = providerId
            settingRepo().save(setting)
            registry.reload()
            log.info("Updated default provider: {}", providerId)
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 切换默认向量 Provider（必须支持 Embedding）
     *
     * @param request 请求体 // defaultEmbeddingProvider 必填
     */
    @Transactional
    fun updateDefaultEmbeddingProvider(request: DefaultProviderVo) {
        rwLock.writeLock().lock()
        try {
            val providerId = trimOrNull(request.defaultEmbeddingProvider)
                ?: throw BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空")
            val provider = getProviderEntityOrThrow(providerId)
            val embeddingModel = trimOrNull(provider.embeddingModel)
            if (!provider.supportsEmbedding || embeddingModel == null) {
                throw BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务"
                )
            }
            validateEmbeddingConfig(
                true,
                embeddingModel,
                resolveEmbeddingDimensions(provider.embeddingDimensions)
            )
            val setting = getGlobalSettingOrThrow()
            setting.defaultEmbeddingProviderId = providerId
            settingRepo().save(setting)
            registry.reload()
            log.info("Updated default embedding provider: {}", providerId)
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 更新 ASR 配置：写回 YAML 并重载 ASR（API Key 变更时同时重载 TTS 并写入 .env）
     *
     * @param request ASR 更新请求
     */
    fun updateAsrConfig(request: AsrConfigRequest) {
        rwLock.writeLock().lock()
        try {
            val qwen = voiceProperties.qwen
            val asr = qwen.asr
            val tts = qwen.tts
            request.url?.let { asr.url = it }
            request.model?.let { asr.model = it }
            request.language?.let { asr.language = it }
            request.format?.let { asr.format = it }
            request.sampleRate?.let { asr.sampleRate = it }
            request.enableTurnDetection?.let { asr.enableTurnDetection = it }
            request.turnDetectionType?.let { asr.turnDetectionType = it }
            request.turnDetectionThreshold?.let { asr.turnDetectionThreshold = it }
            request.turnDetectionSilenceDurationMs?.let { asr.turnDetectionSilenceDurationMs = it }
            val apiKey = request.apiKey
            if (apiKey != null) {
                asr.apiKey = apiKey
                tts.apiKey = apiKey
                updateEnvValue("AI_BAILIAN_API_KEY", apiKey)
            }

            writeAsrConfigToYaml(asr)
            asrService.reload(voiceProperties)
            if (apiKey != null) {
                ttsService.reload(voiceProperties)
            }
            log.info("Updated ASR config")
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 更新 TTS 配置：写回 YAML 并重载 TTS（API Key 变更时同时重载 ASR 并写入 .env）
     *
     * @param request TTS 更新请求
     */
    fun updateTtsConfig(request: TtsConfigRequest) {
        rwLock.writeLock().lock()
        try {
            val qwen = voiceProperties.qwen
            val asr = qwen.asr
            val tts = qwen.tts
            request.model?.let { tts.model = it }
            request.voice?.let { tts.voice = it }
            request.format?.let { tts.format = it }
            request.sampleRate?.let { tts.sampleRate = it }
            request.mode?.let { tts.mode = it }
            request.languageType?.let { tts.languageType = it }
            request.speechRate?.let { tts.speechRate = it }
            request.volume?.let { tts.volume = it }
            val apiKey = request.apiKey
            if (apiKey != null) {
                tts.apiKey = apiKey
                asr.apiKey = apiKey
                updateEnvValue("AI_BAILIAN_API_KEY", apiKey)
            }

            writeTtsConfigToYaml(tts)
            ttsService.reload(voiceProperties)
            if (apiKey != null) {
                asrService.reload(voiceProperties)
            }
            log.info("Updated TTS config")
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    /**
     * 手动清空 Provider 缓存，下次访问时重建
     */
    fun reloadProviders() {
        registry.reload()
        log.info("Manual provider reload triggered")
    }

    // ===== Internal helpers =====

    /**
     * 是否为数据库配置模式：仓库与加密服务均可用
     *
     * @return true=DB 模式
     */
    private fun isDatabaseBacked(): Boolean {
        return providerRepository != null && globalSettingRepository != null && encryptionService != null
    }

    /**
     * Provider 仓库访问器（DB 模式下必非空）
     *
     * @return Provider 仓库
     */
    private fun providerRepo(): LlmProviderRepository {
        return providerRepository
            ?: throw BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "Provider 配置未初始化")
    }

    /**
     * 全局设置仓库访问器（DB 模式下必非空）
     *
     * @return 全局设置仓库
     */
    private fun settingRepo(): LlmGlobalSettingRepository {
        return globalSettingRepository
            ?: throw BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "默认 Provider 配置未初始化")
    }

    /**
     * 加密服务访问器（DB 模式下必非空）
     *
     * @return 加密服务
     */
    private fun encryption(): ApiKeyEncryptionService {
        return encryptionService
            ?: throw BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "Provider 配置未初始化")
    }

    /**
     * 获取配置文件模式的 Provider 配置集合
     *
     * @return Provider 配置 Map
     */
    private fun getLegacyProvidersOrThrow(): MutableMap<String, ProviderConfig> {
        return properties.providers
            ?: throw BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "Provider 配置未初始化")
    }

    /**
     * 获取配置文件模式的单个 Provider 配置
     *
     * @param id Provider ID
     * @return Provider 配置
     */
    internal fun getLegacyProviderConfigOrThrow(id: String): ProviderConfig {
        val config = getLegacyProvidersOrThrow()[id]
        if (config == null) {
            throw BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "Provider '" + id + "' 不存在")
        }
        return config
    }

    /**
     * 配置文件模式新建 Provider：写入内存配置并改写 YAML/.env
     *
     * @param request 新建请求
     */
    private fun createProviderLegacy(request: CreateProviderRequest) {
        val providers = getLegacyProvidersOrThrow()
        val providerId = request.id ?: ""
        if (providers.containsKey(providerId)) {
            throw BusinessException(
                ErrorCode.PROVIDER_ALREADY_EXISTS,
                "Provider '" + request.id + "' 已存在"
            )
        }

        val config = ProviderConfig().apply {
            baseUrl = request.baseUrl
            apiKey = request.apiKey
            model = request.model
            embeddingModel = request.embeddingModel
            embeddingDimensions = request.embeddingDimensions
            supportsEmbedding = request.supportsEmbedding
            temperature = request.temperature
        }
        providers[providerId] = config

        val envKey = toEnvKey(providerId)
        writeProviderToYaml(providerId, config, envKey)
        writeEnvValue(envKey, request.apiKey ?: "")
        registry.reload()
    }

    /**
     * 配置文件模式更新 Provider：校验后改写内存配置与 YAML/.env
     *
     * @param id Provider ID
     * @param request 更新请求
     */
    private fun updateProviderLegacy(id: String, request: UpdateProviderRequest) {
        val config = getLegacyProviderConfigOrThrow(id)
        val trimmedBaseUrl = trimOrNull(request.baseUrl)
        if (request.baseUrl != null && trimmedBaseUrl == null) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串")
        }
        val trimmedModel = trimOrNull(request.model)
        if (request.model != null && trimmedModel == null) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串")
        }
        val trimmedApiKey = trimOrNull(request.apiKey)
        if (request.apiKey != null && trimmedApiKey == null) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串")
        }

        if (trimmedBaseUrl != null) config.baseUrl = trimmedBaseUrl
        if (trimmedModel != null) config.model = trimmedModel
        if (request.embeddingModel != null) {
            config.embeddingModel = trimOrNull(request.embeddingModel)
        }
        if (request.embeddingDimensions != null) {
            config.embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions)
        }
        val supportsEmbedding = request.supportsEmbedding
        if (supportsEmbedding != null) {
            config.supportsEmbedding = supportsEmbedding
        }
        val temperature = request.temperature
        if (temperature != null) {
            config.temperature = temperature
        }
        if (trimmedApiKey != null) {
            config.apiKey = trimmedApiKey
            updateEnvValue(toEnvKey(id), trimmedApiKey)
        }

        writeProviderToYaml(id, config, toEnvKey(id))
        registry.reload()
    }

    /**
     * 配置文件模式删除 Provider：清理内存配置、YAML 与 .env
     *
     * @param id Provider ID
     */
    private fun deleteProviderLegacy(id: String) {
        if (id == properties.defaultProvider) {
            throw BusinessException(
                ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
                "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider"
            )
        }
        getLegacyProviderConfigOrThrow(id)
        getLegacyProvidersOrThrow().remove(id)
        val envKey = toEnvKey(id)
        removeProviderFromYaml(id)
        removeFromEnv(envKey)
        registry.reload()
    }

    /**
     * 配置文件模式切换默认对话 Provider：改写内存与 YAML
     *
     * @param request 请求体
     */
    private fun updateDefaultProviderLegacy(request: DefaultProviderVo) {
        val providerId = trimOrNull(request.defaultProvider)
            ?: throw BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空")
        getLegacyProviderConfigOrThrow(providerId)
        properties.defaultProvider = providerId
        writeDefaultProviderToYaml(providerId)
        registry.reload()
    }

    /**
     * 配置文件模式的 Provider 配置转运行时配置
     *
     * @param config Provider 配置
     * @return 运行时配置
     */
    private fun toRuntimeConfig(config: ProviderConfig): ProviderRuntimeConfig {
        return ProviderRuntimeConfig(
            config.baseUrl,
            config.apiKey.orEmpty(),
            config.model,
            config.embeddingModel,
            resolveEmbeddingDimensions(config.embeddingDimensions),
            config.supportsEmbedding == true || trimOrNull(config.embeddingModel) != null,
            config.temperature
        )
    }

    /**
     * 按 ID 查询 Provider 实体，不存在时抛业务异常
     *
     * @param id Provider ID
     * @return Provider 实体
     */
    internal fun getProviderEntityOrThrow(id: String): LlmProviderEntity {
        return providerRepo().findById(id)
            .orElseThrow {
                BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "Provider '" + id + "' 不存在")
            }
    }

    /**
     * 查询全局默认设置，未初始化时抛业务异常
     *
     * @return 全局设置实体
     */
    private fun getGlobalSettingOrThrow(): LlmGlobalSettingEntity {
        return settingRepo().findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .orElseThrow {
                BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "默认 Provider 配置未初始化")
            }
    }

    /**
     * 查询 Provider 运行时配置（含解密后的 API Key）
     *
     * @param id Provider ID
     * @return 运行时配置
     */
    private fun getProviderRuntimeConfigOrThrow(id: String): ProviderRuntimeConfig {
        val provider = getProviderEntityOrThrow(id)
        return ProviderRuntimeConfig(
            provider.baseUrl,
            decryptApiKey(provider),
            provider.model,
            provider.embeddingModel,
            resolveEmbeddingDimensions(provider.embeddingDimensions),
            provider.supportsEmbedding,
            provider.temperature
        )
    }

    /**
     * 解密 Provider 的 API Key
     *
     * @param provider Provider 实体
     * @return 明文 API Key
     */
    private fun decryptApiKey(provider: LlmProviderEntity): String {
        return encryption().decrypt(provider.apiKeyNonce.orEmpty(), provider.apiKeyCiphertext.orEmpty())
    }

    /**
     * API Key 脱敏：长度不足 7 时全掩码，否则保留前 3 后 3
     *
     * @param apiKey 明文 Key // 可为 null
     * @return 脱敏后的 Key
     */
    internal fun maskApiKey(apiKey: String?): String {
        if (apiKey == null || apiKey.length <= 6) {
            return "***"
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length - 3)
    }

    /**
     * 截断响应正文用于日志/错误提示
     *
     * @param text 原始正文 // 可为 null
     * @return 归一化后的短文本
     */
    private fun abbreviate(text: String?): String {
        if (text == null || text.isBlank()) {
            return "[no body]"
        }
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= 200) {
            return normalized
        }
        return normalized.substring(0, 200) + "..."
    }

    /**
     * 构建连通性测试候选 URL：优先 baseUrl + /chat/completions，未带版本号时追加 /v1 版本
     *
     * @param baseUrl 服务基础地址
     * @return 候选 URL 列表 // 保持插入顺序且去重
     */
    private fun buildConnectivityTestUrls(baseUrl: String?): List<String> {
        val normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl)
        val candidateUrls = LinkedHashSet<String>()

        candidateUrls.add("$normalizedBaseUrl/chat/completions")
        if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
            candidateUrls.add("$normalizedBaseUrl/v1/chat/completions")
        }

        return candidateUrls.toList()
    }

    /**
     * 构建连通性测试请求体
     *
     * @param model 模型名称 // 可为 null（由配置保证非空）
     * @return 请求体 Map
     */
    private fun buildConnectivityTestRequestBody(model: String?): Map<String, Any?> {
        val requestBody = LinkedHashMap<String, Any?>()
        requestBody["model"] = model
        requestBody["messages"] = listOf(mapOf("role" to "user", "content" to "Reply with OK only."))
        requestBody["max_tokens"] = 1
        return requestBody
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
        val normalized = value.trim()
        return if (normalized.isEmpty()) null else normalized
    }

    /**
     * 要求字段非空白
     *
     * @param value 原始值 // 可为 null
     * @param fieldName 字段名 // 用于异常提示
     * @return 去除首尾空白后的值
     */
    private fun requireNonBlank(value: String?, fieldName: String): String {
        return trimOrNull(value)
            ?: throw BusinessException(ErrorCode.BAD_REQUEST, "$fieldName 不能为空")
    }

    /**
     * 校验 Embedding 配置：声明支持向量时必须有模型名与正整数维度
     *
     * @param supportsEmbedding 是否支持向量化
     * @param embeddingModel 向量模型 // 可为 null
     * @param embeddingDimensions 向量维度 // 可为 null
     */
    private fun validateEmbeddingConfig(
        supportsEmbedding: Boolean,
        embeddingModel: String?,
        embeddingDimensions: Int?
    ) {
        val normalizedModel = trimOrNull(embeddingModel)
        if (!supportsEmbedding) {
            return
        }
        if (normalizedModel == null) {
            throw BusinessException(
                ErrorCode.BAD_REQUEST,
                "支持 Embedding 的 Provider 必须填写 embeddingModel"
            )
        }
        // 模型名和自定义别名不能证明能力；实际支持情况由 Embedding 接口确认。
        if (embeddingDimensions == null || embeddingDimensions <= 0) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数")
        }
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
     * Provider ID 转环境变量 Key：PROVIDER_{ID}_API_KEY
     *
     * @param providerId Provider ID
     * @return 环境变量 Key
     */
    private fun toEnvKey(providerId: String): String {
        return "PROVIDER_" + providerId.uppercase().replace("-", "_") + "_API_KEY"
    }

    // ===== Provider test logic (called under read lock) =====

    /**
     * 执行 Provider 连通性测试（真实调用 chat/completions）
     *
     * @param config 运行时配置
     * @param id Provider ID // 仅用于日志
     * @return 测试结果
     */
    private fun doTestProvider(config: ProviderRuntimeConfig, id: String): ProviderTestResult {
        try {
            val settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10))
                .withInetAddressFilter(
                    InetAddressFilter.externalAddresses()
                        .or(InetAddressFilter.adapt { address: InetAddress? -> address?.isLoopbackAddress == true })
                        .or("198.18.0.0/15")
                )

            val restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + config.apiKey)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build()

            val requestBody = buildConnectivityTestRequestBody(config.model)

            val candidateUrls = buildConnectivityTestUrls(config.baseUrl)
            var lastFailureMessage = "Unknown error"

            for (targetUrl in candidateUrls) {
                try {
                    restClient.post()
                        .uri(URI.create(targetUrl))
                        .body(requestBody)
                        .retrieve()
                        .toEntity(String::class.java)
                    log.info(
                        "Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
                        id, config.baseUrl, targetUrl, config.model
                    )
                    return ProviderTestResult(
                        success = true,
                        message = "连接成功",
                        model = config.model
                    )
                } catch (e: RestClientResponseException) {
                    val responseBody = abbreviate(e.responseBodyAsString)
                    lastFailureMessage = "HTTP ${e.statusCode.value()} on $targetUrl, body=$responseBody"
                    log.warn(
                        "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
                        id,
                        config.baseUrl,
                        targetUrl,
                        config.model,
                        e.statusCode.value(),
                        responseBody,
                        e
                    )
                } catch (e: Exception) {
                    lastFailureMessage = "${e.javaClass.simpleName} on $targetUrl: ${e.message}"
                    log.warn(
                        "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
                        id,
                        config.baseUrl,
                        targetUrl,
                        config.model,
                        e.message,
                        e
                    )
                }
            }
            return ProviderTestResult(
                success = false,
                message = "连接失败: $lastFailureMessage",
                model = config.model
            )
        } catch (e: Exception) {
            log.warn(
                "Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
                id, config.baseUrl, config.model, e.message, e
            )
            return ProviderTestResult(
                success = false,
                message = "连接失败: ${e.message}",
                model = config.model
            )
        }
    }

    // ===== YAML text editing (preserves comments & formatting) =====

    /**
     * 写入单个 Provider 配置块到 YAML
     *
     * @param id Provider ID
     * @param config Provider 配置
     * @param envKey API Key 对应的环境变量名
     */
    private fun writeProviderToYaml(id: String, config: ProviderConfig, envKey: String) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败") { editor ->
            val values = LinkedHashMap<String, Any?>()
            values["base-url"] = config.baseUrl
            values["api-key"] = "\${$envKey}"
            values["model"] = config.model
            if (config.embeddingModel != null) {
                values["embedding-model"] = config.embeddingModel
            }
            if (config.embeddingDimensions != null) {
                values["embedding-dimensions"] = config.embeddingDimensions
            }
            if (config.temperature != null) {
                values["temperature"] = config.temperature
            }
            editor.setBlock(arrayOf("app", "ai", "providers"), id, values)
        }
    }

    /**
     * 从 YAML 删除 Provider 配置块
     *
     * @param id Provider ID
     */
    private fun removeProviderFromYaml(id: String) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败") { editor ->
            editor.removeSection(arrayOf("app", "ai", "providers"), id)
        }
    }

    /**
     * 写入默认 Provider 到 YAML，并移除历史 module-defaults 段
     *
     * @param defaultProvider 默认 Provider ID
     */
    private fun writeDefaultProviderToYaml(defaultProvider: String) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败") { editor ->
            editor.setScalar(arrayOf("app", "ai", "default-provider"), defaultProvider)
            editor.removeSection(arrayOf("app", "ai"), "module-defaults")
        }
    }

    /**
     * 写入 ASR 配置到 YAML
     *
     * @param asr ASR 配置
     */
    private fun writeAsrConfigToYaml(asr: VoiceInterviewProperties.AsrConfig) {
        mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败") { editor ->
            val values = LinkedHashMap<String, Any?>()
            values["url"] = asr.url
            values["model"] = asr.model
            values["api-key"] = "\${AI_BAILIAN_API_KEY}"
            values["language"] = asr.language
            values["format"] = asr.format
            values["sample-rate"] = asr.sampleRate
            values["enable-turn-detection"] = asr.enableTurnDetection
            values["turn-detection-type"] = asr.turnDetectionType
            values["turn-detection-threshold"] = asr.turnDetectionThreshold
            values["turn-detection-silence-duration-ms"] = asr.turnDetectionSilenceDurationMs
            editor.setBlock(arrayOf("app", "voice-interview", "qwen"), "asr", values)
        }
    }

    /**
     * 写入 TTS 配置到 YAML
     *
     * @param tts TTS 配置
     */
    private fun writeTtsConfigToYaml(tts: VoiceInterviewProperties.QwenTtsConfig) {
        mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败") { editor ->
            val values = LinkedHashMap<String, Any?>()
            values["model"] = tts.model
            values["api-key"] = "\${AI_BAILIAN_API_KEY}"
            values["voice"] = tts.voice
            values["format"] = tts.format
            values["sample-rate"] = tts.sampleRate
            values["mode"] = tts.mode
            values["language-type"] = tts.languageType
            values["speech-rate"] = tts.speechRate
            values["volume"] = tts.volume
            editor.setBlock(arrayOf("app", "voice-interview", "qwen"), "tts", values)
        }
    }

    /**
     * 基于文本行编辑 YAML 并写回文件（保留注释与原有格式）
     *
     * @param errorCode 失败错误码
     * @param errorMessage 失败提示前缀
     * @param mutator 编辑动作 // 接收 YamlTextEditor
     */
    private fun mutateYamlText(
        errorCode: ErrorCode,
        errorMessage: String,
        mutator: (YamlTextEditor) -> Unit
    ) {
        val configuredYamlPath = yamlPath
        if (configuredYamlPath == null || configuredYamlPath.isBlank()) {
            log.warn("YAML path not configured, skip writing")
            return
        }
        try {
            val path = Path.of(configuredYamlPath)
            val lines: MutableList<String> = if (Files.exists(path)) {
                ArrayList(Files.readAllLines(path, StandardCharsets.UTF_8))
            } else {
                ArrayList()
            }

            val editor = YamlTextEditor(lines)
            mutator(editor)

            var content = editor.getLines().joinToString("\n")
            if (!content.endsWith("\n")) {
                content += "\n"
            }
            Files.writeString(path, content, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw BusinessException(errorCode, errorMessage + ": " + e.message)
        }
    }

    // ===== .env file operations =====

    /**
     * 写入 .env 键值：已存在则替换该行，否则追加
     *
     * @param key 环境变量名
     * @param value 环境变量值
     */
    private fun writeEnvValue(key: String, value: String) {
        val configuredEnvPath = envPath
        if (configuredEnvPath == null || configuredEnvPath.isBlank()) {
            return
        }
        try {
            val path = Path.of(configuredEnvPath)
            if (!Files.exists(path)) {
                Files.writeString(
                    path, "$key=$value\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                )
                return
            }
            var content = Files.readString(path, StandardCharsets.UTF_8)
            if (content.contains("$key=")) {
                content = Regex("(?m)^" + Pattern.quote(key) + "=.*")
                    .replace(content, Matcher.quoteReplacement("$key=$value"))
            } else {
                if (!content.endsWith("\n")) {
                    content += "\n"
                }
                content += "$key=$value\n"
            }
            Files.writeString(path, content, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("写入 .env 失败: {}", e.message)
        }
    }

    /**
     * 更新 .env 键值（与 writeEnvValue 等价，语义化别名）
     *
     * @param key 环境变量名
     * @param value 环境变量值
     */
    private fun updateEnvValue(key: String, value: String) {
        writeEnvValue(key, value)
    }

    /**
     * 从 .env 删除指定键
     *
     * @param key 环境变量名
     */
    private fun removeFromEnv(key: String) {
        val configuredEnvPath = envPath
        if (configuredEnvPath == null || configuredEnvPath.isBlank()) {
            return
        }
        try {
            val path = Path.of(configuredEnvPath)
            if (!Files.exists(path)) {
                return
            }
            var content = Files.readString(path, StandardCharsets.UTF_8)
            content = Regex("(?m)^" + Pattern.quote(key) + "=.*\\R?").replace(content, "")
            Files.writeString(path, content, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            log.warn("删除 .env 条目失败: {}", e.message)
        }
    }

    // ===== YamlTextEditor: text-based YAML editing that preserves comments & formatting =====

    /**
     * 基于行的 YAML 文本编辑器：仅按缩进层级定位并替换/插入目标行，保留注释与格式
     *
     * @property lines YAML 文本行 // 可变列表
     */
    internal class YamlTextEditor(lines: List<String>) {

        private val lines: MutableList<String> = ArrayList(lines) // YAML 文本行

        /**
         * 获取编辑后的行列表
         *
         * @return 文本行
         */
        fun getLines(): List<String> {
            return lines
        }

        /**
         * 设置标量值：已存在则替换整行，否则插入到父段落末尾
         *
         * @param path 键路径 // 例如 [app, ai, default-provider]
         * @param value 标量值
         */
        fun setScalar(path: Array<String>, value: String) {
            val searchFrom = if (path.size > 1) {
                ensureParents(path.take(path.size - 1).toTypedArray())
            } else {
                0
            }
            val indent = (path.size - 1) * 2
            val key = path[path.size - 1]
            val newLine = " ".repeat(indent) + key + ": " + value

            val found = findKey(key, indent, searchFrom)
            if (found >= 0) {
                lines[found] = newLine
            } else {
                val parentIndent = if (indent >= 2) indent - 2 else -1
                val insertPos = findSectionEnd(searchFrom, parentIndent)
                lines.add(insertPos, newLine)
            }
        }

        /**
         * 设置配置块：确保父路径存在，再逐项写入/更新子键
         *
         * @param parentPath 父路径 // 例如 [app, ai, providers]
         * @param blockKey 块键名 // 例如 providerId
         * @param values 子键值 // 保持插入顺序
         */
        fun setBlock(parentPath: Array<String>, blockKey: String, values: LinkedHashMap<String, Any?>) {
            val parentSearchFrom = ensureParents(parentPath)
            val blockIndent = parentPath.size * 2
            val valueIndent = blockIndent + 2

            var blockLine = findKey(blockKey, blockIndent, parentSearchFrom)
            if (blockLine < 0) {
                val parentEnd = if (parentPath.size >= 1) blockIndent - 2 else -1
                val insertPos = findSectionEnd(parentSearchFrom, parentEnd)
                lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":")
                blockLine = insertPos
            }

            var blockEnd = findSectionEnd(blockLine + 1, blockIndent)

            for (entry in values.entries) {
                val valueLine = " ".repeat(valueIndent) + entry.key + ": " + formatValue(entry.value)
                val existing = findKeyInRange(entry.key, valueIndent, blockLine + 1, blockEnd)
                if (existing >= 0) {
                    lines[existing] = valueLine
                } else {
                    lines.add(blockEnd, valueLine)
                    blockEnd++
                }
            }
        }

        /**
         * 删除整个配置段（含其子行）
         *
         * @param parentPath 父路径
         * @param sectionKey 段键名
         */
        fun removeSection(parentPath: Array<String>, sectionKey: String) {
            val parentSearchFrom = navigateTo(parentPath)
            if (parentSearchFrom < 0) {
                return
            }

            val sectionIndent = parentPath.size * 2
            val sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom)
            if (sectionLine < 0) {
                return
            }

            var endLine = sectionLine + 1
            while (endLine < lines.size) {
                val line = lines[endLine]
                if (line.isBlank()) {
                    endLine++
                    continue
                }
                if (indentOf(line) <= sectionIndent) {
                    break
                }
                endLine++
            }

            for (i in endLine - 1 downTo sectionLine) {
                lines.removeAt(i)
            }
        }

        /**
         * 确保路径上的父级键存在，缺失时按缩进层级插入
         *
         * @param path 父路径
         * @return 父段落之后的搜索起点 // 用于后续查找
         */
        private fun ensureParents(path: Array<String>): Int {
            var searchFrom = 0
            for (i in path.indices) {
                val indent = i * 2
                val found = findKey(path[i], indent, searchFrom)
                if (found < 0) {
                    val parentIndent = if (i > 0) indent - 2 else -1
                    val insertPos = findSectionEnd(searchFrom, parentIndent)
                    lines.add(insertPos, " ".repeat(indent) + path[i] + ":")
                    searchFrom = insertPos + 1
                } else {
                    searchFrom = found + 1
                }
            }
            return searchFrom
        }

        /**
         * 导航到指定路径（不创建缺失节点）
         *
         * @param path 路径
         * @return 路径之后的搜索起点 // 任一层级缺失返回 -1
         */
        private fun navigateTo(path: Array<String>): Int {
            var searchFrom = 0
            for (i in path.indices) {
                val indent = i * 2
                val found = findKey(path[i], indent, searchFrom)
                if (found < 0) {
                    return -1
                }
                searchFrom = found + 1
            }
            return searchFrom
        }

        /**
         * 在指定缩进层级查找键所在行
         *
         * @param key 键名
         * @param indent 目标缩进
         * @param searchFrom 起始搜索行
         * @return 行号 // 未找到返回 -1
         */
        private fun findKey(key: String, indent: Int, searchFrom: Int): Int {
            val prefix = " ".repeat(indent) + key + ":"
            for (i in searchFrom until lines.size) {
                val line = lines[i]
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue
                }
                if (line.startsWith(prefix)) {
                    return i
                }
                if (indentOf(line) < indent) {
                    break
                }
            }
            return -1
        }

        /**
         * 在指定行区间内查找键
         *
         * @param key 键名
         * @param indent 目标缩进
         * @param start 起始行（含）
         * @param end 结束行（不含）
         * @return 行号 // 未找到返回 -1
         */
        private fun findKeyInRange(key: String, indent: Int, start: Int, end: Int): Int {
            val prefix = " ".repeat(indent) + key + ":"
            var i = start
            while (i < end && i < lines.size) {
                val line = lines[i]
                if (line.isBlank() || line.trim().startsWith("#")) {
                    i++
                    continue
                }
                if (line.startsWith(prefix)) {
                    return i
                }
                if (indentOf(line) < indent) {
                    break
                }
                i++
            }
            return -1
        }

        /**
         * 查找段落结束位置（第一个缩进不深于父级的内容行）
         *
         * @param searchFrom 起始搜索行
         * @param parentIndent 父级缩进 // -1 表示顶层
         * @return 插入位置 // 默认文件末尾
         */
        private fun findSectionEnd(searchFrom: Int, parentIndent: Int): Int {
            for (i in searchFrom until lines.size) {
                val line = lines[i]
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue
                }
                if (indentOf(line) <= parentIndent) {
                    return i
                }
            }
            return lines.size
        }

        /**
         * 计算行首空格缩进
         *
         * @param line 文本行
         * @return 缩进空格数
         */
        private fun indentOf(line: String): Int {
            var count = 0
            while (count < line.length && line[count] == ' ') {
                count++
            }
            return count
        }

        /**
         * 格式化 YAML 值
         *
         * @param value 值 // Boolean/Number/其他
         * @return 文本表示
         */
        private fun formatValue(value: Any?): String {
            if (value is Boolean) {
                return value.toString()
            }
            if (value is Number) {
                return value.toString()
            }
            return value?.toString() ?: ""
        }
    }

    /**
     * Provider 运行时配置
     *
     * @property baseUrl 服务基础地址
     * @property apiKey 明文 API Key
     * @property model 对话模型
     * @property embeddingModel 向量模型
     * @property embeddingDimensions 向量维度
     * @property supportsEmbedding 是否支持向量化
     * @property temperature 采样温度
     */
    private data class ProviderRuntimeConfig(
        val baseUrl: String?,
        val apiKey: String,
        val model: String?,
        val embeddingModel: String?,
        val embeddingDimensions: Int,
        val supportsEmbedding: Boolean,
        val temperature: Double?
    )
}

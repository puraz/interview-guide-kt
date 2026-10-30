package interview.guide.modules.llmprovider.controller

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import interview.guide.modules.llmprovider.dto.AsrConfigDTO
import interview.guide.modules.llmprovider.dto.AsrConfigRequest
import interview.guide.modules.llmprovider.dto.CreateProviderRequest
import interview.guide.modules.llmprovider.dto.DefaultProviderVo
import interview.guide.modules.llmprovider.dto.ProviderTestResult
import interview.guide.modules.llmprovider.dto.ProviderVo
import interview.guide.modules.llmprovider.dto.TtsConfigDTO
import interview.guide.modules.llmprovider.dto.TtsConfigRequest
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest
import interview.guide.modules.llmprovider.service.LlmProviderConfigService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * LLM Provider 配置管理接口
 */
@RestController
@RequestMapping("/api/llm-provider")
class LlmProviderController(
    private val configService: LlmProviderConfigService // Provider 配置服务
) {

    private val log = LoggerFactory.getLogger(LlmProviderController::class.java)

    /**
     * 查询全部 Provider
     *
     * @return Provider 列表 // API Key 已脱敏
     */
    @GetMapping("/list")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30.0)
    fun listProviders(): Result<List<ProviderVo>> {
        return Result.success(configService.listProviders())
    }

    /**
     * 查询单个 Provider
     *
     * @param id Provider ID
     * @return Provider 详情 // API Key 已脱敏
     */
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30.0)
    fun getProvider(@PathVariable id: String): Result<ProviderVo> {
        return Result.success(configService.getProvider(id))
    }

    /**
     * 新建 Provider
     *
     * @param request 新建请求 // id/baseUrl/apiKey/model 必填
     * @return 空响应 // 创建结果通过错误码表达
     */
    @PostMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun createProvider(@RequestBody @Valid request: CreateProviderRequest): Result<Void> {
        configService.createProvider(request)
        return Result.success()
    }

    /**
     * 更新 Provider
     *
     * @param id Provider ID
     * @param request 更新请求 // 字段为 null 表示不修改
     * @return 空响应
     */
    @PutMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun updateProvider(@PathVariable id: String, @RequestBody request: UpdateProviderRequest): Result<Void> {
        configService.updateProvider(id, request)
        return Result.success()
    }

    /**
     * 删除 Provider
     *
     * @param id Provider ID
     * @return 空响应 // 默认 Provider 不可删除
     */
    @DeleteMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun deleteProvider(@PathVariable id: String): Result<Void> {
        configService.deleteProvider(id)
        return Result.success()
    }

    /**
     * 测试 Provider 连通性
     *
     * @param id Provider ID
     * @return 测试结果
     */
    @PostMapping("/{id}/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10.0)
    fun testProvider(@PathVariable id: String): Result<ProviderTestResult> {
        return Result.success(configService.testProvider(id))
    }

    /**
     * 手动重载 Provider 缓存
     *
     * @return 空响应
     */
    @PostMapping("/reload")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun reloadProviders(): Result<Void> {
        configService.reloadProviders()
        return Result.success()
    }

    /**
     * 查询默认 Provider 设置
     *
     * @return 默认对话/向量 Provider ID
     */
    @GetMapping("/default-provider")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30.0)
    fun getDefaultProvider(): Result<DefaultProviderVo> {
        return Result.success(configService.getDefaultProvider())
    }

    /**
     * 切换默认对话 Provider
     *
     * @param request 请求体 // defaultProvider 必填
     * @return 空响应
     */
    @PutMapping("/default-provider")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun updateDefaultProvider(@RequestBody request: DefaultProviderVo): Result<Void> {
        configService.updateDefaultProvider(request)
        return Result.success()
    }

    /**
     * 切换默认向量 Provider
     *
     * @param request 请求体 // defaultEmbeddingProvider 必填
     * @return 空响应
     */
    @PutMapping("/default-embedding-provider")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun updateDefaultEmbeddingProvider(@RequestBody request: DefaultProviderVo): Result<Void> {
        configService.updateDefaultEmbeddingProvider(request)
        return Result.success()
    }

    // ===== Voice ASR/TTS Config =====

    /**
     * 查询 ASR 配置
     *
     * @return ASR 配置 // API Key 已脱敏
     */
    @GetMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30.0)
    fun getAsrConfig(): Result<AsrConfigDTO> {
        return Result.success(configService.getAsrConfig())
    }

    /**
     * 更新 ASR 配置
     *
     * @param request ASR 更新请求
     * @return 空响应
     */
    @PutMapping("/voice/asr")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun updateAsrConfig(@RequestBody request: AsrConfigRequest): Result<Void> {
        configService.updateAsrConfig(request)
        return Result.success()
    }

    /**
     * 查询 TTS 配置
     *
     * @return TTS 配置 // API Key 已脱敏
     */
    @GetMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30.0)
    fun getTtsConfig(): Result<TtsConfigDTO> {
        return Result.success(configService.getTtsConfig())
    }

    /**
     * 更新 TTS 配置
     *
     * @param request TTS 更新请求
     * @return 空响应
     */
    @PutMapping("/voice/tts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    fun updateTtsConfig(@RequestBody request: TtsConfigRequest): Result<Void> {
        configService.updateTtsConfig(request)
        return Result.success()
    }

    /**
     * 测试 ASR 服务网络可达性
     *
     * @return 测试结果
     */
    @PostMapping("/voice/asr/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10.0)
    fun testAsrConfig(): Result<ProviderTestResult> {
        return Result.success(configService.testAsrConfig())
    }
}

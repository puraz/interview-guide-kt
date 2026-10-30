package interview.guide.modules.llmprovider.dto

/**
 * 默认 Provider 配置返回/请求体（接口返回按规范以 Vo 结尾）
 *
 * @property defaultProvider 默认对话 Provider ID
 * @property defaultEmbeddingProvider 默认向量 Provider ID
 */
data class DefaultProviderVo(
    val defaultProvider: String? = null,
    val defaultEmbeddingProvider: String? = null
)

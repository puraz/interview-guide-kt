package interview.guide.modules.llmprovider.dto

/**
 * Provider 信息返回体（接口返回，按规范以 Vo 结尾）
 *
 * @property id Provider 唯一标识
 * @property baseUrl 服务基础地址
 * @property maskedApiKey 脱敏后的 API Key
 * @property model 对话模型名称
 * @property embeddingModel 向量模型名称
 * @property embeddingDimensions 向量维度
 * @property supportsEmbedding 是否支持 Embedding
 * @property temperature 采样温度
 * @property defaultChatProvider 是否为默认对话 Provider
 * @property defaultEmbeddingProvider 是否为默认向量 Provider
 */
data class ProviderVo(
    val id: String? = null,
    val baseUrl: String? = null,
    val maskedApiKey: String? = null,
    val model: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimensions: Int? = null,
    val supportsEmbedding: Boolean = false,
    val temperature: Double? = null,
    val defaultChatProvider: Boolean = false,
    val defaultEmbeddingProvider: Boolean = false
)

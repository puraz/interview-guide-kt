package interview.guide.modules.llmprovider.dto

/**
 * 更新 Provider 请求（字段为 null 表示不修改）
 *
 * @property baseUrl 服务基础地址
 * @property apiKey API Key
 * @property model 对话模型
 * @property embeddingModel 向量模型
 * @property embeddingDimensions 向量维度
 * @property supportsEmbedding 是否支持 Embedding
 * @property temperature 采样温度
 */
data class UpdateProviderRequest(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimensions: Int? = null,
    val supportsEmbedding: Boolean? = null,
    val temperature: Double? = null
)

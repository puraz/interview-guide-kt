package interview.guide.modules.llmprovider.dto

import jakarta.validation.constraints.NotBlank

/**
 * 新建 Provider 请求
 *
 * @property id Provider 唯一标识（必填）
 * @property baseUrl 服务基础地址（必填）
 * @property apiKey API Key（必填）
 * @property model 对话模型（必填）
 * @property embeddingModel 向量模型
 * @property embeddingDimensions 向量维度
 * @property supportsEmbedding 是否支持 Embedding
 * @property temperature 采样温度
 */
data class CreateProviderRequest(
    @field:NotBlank
    val id: String? = null,
    @field:NotBlank
    val baseUrl: String? = null,
    @field:NotBlank
    val apiKey: String? = null,
    @field:NotBlank
    val model: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimensions: Int? = null,
    val supportsEmbedding: Boolean? = null,
    val temperature: Double? = null
)

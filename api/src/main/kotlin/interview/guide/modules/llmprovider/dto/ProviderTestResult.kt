package interview.guide.modules.llmprovider.dto

/**
 * Provider 连通性测试结果
 *
 * @property success 是否连通
 * @property message 测试结果说明
 * @property model 测试使用的模型
 */
data class ProviderTestResult(
    val success: Boolean = false,
    val message: String? = null,
    val model: String? = null
)

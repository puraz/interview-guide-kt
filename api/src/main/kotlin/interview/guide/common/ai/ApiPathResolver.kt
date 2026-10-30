package interview.guide.common.ai

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import com.openai.core.Timeout
import com.openai.credential.BearerTokenCredential
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
import java.time.Duration
import java.util.regex.Pattern

/**
 * OpenAI 兼容客户端构建与 baseUrl 版本路径解析工具。
 */
object ApiPathResolver {

    private const val DEFAULT_CONNECT_TIMEOUT: Int = 10000 // 默认连接超时（毫秒）

    private const val DEFAULT_READ_TIMEOUT: Int = 300000 // 默认读超时（毫秒）

    private val TRAILING_VERSION: Pattern = Pattern.compile("/v\\d+[a-zA-Z0-9]*$") // 末尾版本号路径

    /**
     * 使用默认超时构建 OpenAIClient
     *
     * @param baseUrl 服务基础地址 // 例如 https://dashscope.aliyuncs.com/compatible-mode/v1
     * @param apiKey API Key // 明文密钥
     * @return OpenAIClient // 已完成 baseUrl 版本补全的客户端
     */
    fun buildOpenAiClient(baseUrl: String?, apiKey: String): OpenAIClient {
        return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT)
    }

    /**
     * 构建 OpenAIClient
     *
     * @param baseUrl 服务基础地址 // 未带版本号时自动补 /v1
     * @param apiKey API Key // 明文密钥
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout 读取超时（毫秒）
     * @return OpenAIClient // 自定义超时的客户端
     */
    fun buildOpenAiClient(
        baseUrl: String?,
        apiKey: String,
        connectTimeout: Int,
        readTimeout: Int
    ): OpenAIClient {
        val timeout = Timeout.builder()
            .connect(Duration.ofMillis(connectTimeout.toLong()))
            .read(Duration.ofMillis(readTimeout.toLong()))
            .build()
        val options = ClientOptions.builder()
            .apiKey(apiKey)
            .credential(BearerTokenCredential.create(apiKey))
            .baseUrl(resolveVersionedBaseUrl(baseUrl))
            .timeout(timeout)
            .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
            .build()
        return OpenAIClientImpl(options)
    }

    /**
     * 解析带版本号的 baseUrl：已包含版本号则原样返回，否则补 `/v1`
     *
     * @param baseUrl 原始基础地址 // 可为 null
     * @return 补全后的基础地址 // 去除末尾斜杠
     */
    fun resolveVersionedBaseUrl(baseUrl: String?): String {
        val stripped = stripTrailingSlashes(baseUrl)
        if (baseUrlContainsVersion(stripped)) {
            return stripped
        }
        return "$stripped/v1"
    }

    /**
     * 判断 baseUrl 是否已包含版本号段
     *
     * @param baseUrl 基础地址 // 可为 null/空白
     * @return 是否包含版本号 // true=已带 /v{n}
     */
    fun baseUrlContainsVersion(baseUrl: String?): Boolean {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false
        }
        val stripped = stripTrailingSlashes(baseUrl.trim())
        return TRAILING_VERSION.matcher(stripped).find()
    }

    /**
     * 去除字符串末尾所有斜杠
     *
     * @param value 原始字符串 // 可为 null
     * @return 处理后的字符串 // null 返回空串
     */
    fun stripTrailingSlashes(value: String?): String {
        if (value == null) {
            return ""
        }
        var result = value.trim()
        while (result.endsWith("/")) {
            result = result.substring(0, result.length - 1)
        }
        return result
    }
}

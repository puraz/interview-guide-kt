package interview.guide.common.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OpenAI 兼容 baseUrl 解析")
class ApiPathResolverTest {

    @Nested
    @DisplayName("baseUrl 包含版本段识别")
    inner class ContainsVersion {

        @Test
        @DisplayName("OpenAI 原生 base-url（不含版本段）返回 false")
        fun plainBaseUrl() {
            assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.openai.com")).isFalse()
            assertThat(ApiPathResolver.baseUrlContainsVersion("http://localhost:1234")).isFalse()
        }

        @Test
        @DisplayName("以 /v1 结尾（Kimi / DeepSeek / SiliconFlow / DashScope 兼容模式）返回 true")
        fun trailingV1() {
            assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.moonshot.cn/v1")).isTrue()
            assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.deepseek.com/v1")).isTrue()
            assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.siliconflow.cn/v1")).isTrue()
            assertThat(
                ApiPathResolver.baseUrlContainsVersion(
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"
                )
            ).isTrue()
        }

        @Test
        @DisplayName("以 /v3 /v4 等非 v1 版本段结尾（豆包 Ark / 智谱 GLM）返回 true")
        fun trailingOtherVersion() {
            assertThat(
                ApiPathResolver.baseUrlContainsVersion("https://ark.cn-beijing.volces.com/api/v3")
            ).isTrue()
            assertThat(
                ApiPathResolver.baseUrlContainsVersion("https://open.bigmodel.cn/api/coding/paas/v4")
            ).isTrue()
        }

        @Test
        @DisplayName("末尾带 / 或空白不影响识别")
        fun trailingSlashOrWhitespace() {
            assertThat(ApiPathResolver.baseUrlContainsVersion("https://api.moonshot.cn/v1/")).isTrue()
            assertThat(ApiPathResolver.baseUrlContainsVersion("  https://api.moonshot.cn/v1  ")).isTrue()
        }

        @Test
        @DisplayName("null / 空串返回 false")
        fun nullOrBlank() {
            assertThat(ApiPathResolver.baseUrlContainsVersion(null)).isFalse()
            assertThat(ApiPathResolver.baseUrlContainsVersion("")).isFalse()
            assertThat(ApiPathResolver.baseUrlContainsVersion("   ")).isFalse()
        }

        @Test
        @DisplayName("版本段在中段而不在末尾（后面还有其它 path）返回 false")
        fun versionNotAtTail() {
            assertThat(
                ApiPathResolver.baseUrlContainsVersion("https://api.example.com/v1/proxy")
            ).isFalse()
        }
    }

    @Nested
    @DisplayName("stripTrailingSlashes")
    inner class StripTrailingSlashes {

        @Test
        @DisplayName("null 返回空串")
        fun nullInput() {
            assertThat(ApiPathResolver.stripTrailingSlashes(null)).isEmpty()
        }

        @Test
        @DisplayName("空串返回空串")
        fun emptyInput() {
            assertThat(ApiPathResolver.stripTrailingSlashes("")).isEmpty()
        }

        @Test
        @DisplayName("单个尾部斜杠被移除")
        fun singleTrailingSlash() {
            assertThat(ApiPathResolver.stripTrailingSlashes("https://example.com/"))
                .isEqualTo("https://example.com")
        }

        @Test
        @DisplayName("多个尾部斜杠全部移除")
        fun multipleTrailingSlashes() {
            assertThat(ApiPathResolver.stripTrailingSlashes("https://example.com///"))
                .isEqualTo("https://example.com")
        }

        @Test
        @DisplayName("无尾部斜杠保持不变")
        fun noTrailingSlash() {
            assertThat(ApiPathResolver.stripTrailingSlashes("https://example.com"))
                .isEqualTo("https://example.com")
        }

        @Test
        @DisplayName("前后空白被 trim")
        fun whitespacePadded() {
            assertThat(ApiPathResolver.stripTrailingSlashes("  https://example.com/  "))
                .isEqualTo("https://example.com")
        }
    }

    @Nested
    @DisplayName("resolveVersionedBaseUrl")
    inner class ResolveVersionedBaseUrl {

        @Test
        @DisplayName("带版本段的 base-url 保持原样")
        fun versionedBaseUrlStaysUnchanged() {
            assertThat(ApiPathResolver.resolveVersionedBaseUrl("https://api.moonshot.cn/v1/"))
                .isEqualTo("https://api.moonshot.cn/v1")
        }

        @Test
        @DisplayName("不带版本段的 base-url 自动追加 /v1")
        fun plainBaseUrlGetsV1() {
            assertThat(ApiPathResolver.resolveVersionedBaseUrl("https://api.openai.com"))
                .isEqualTo("https://api.openai.com/v1")
        }
    }

    @Nested
    @DisplayName("buildOpenAiClient")
    inner class BuildOpenAiClient {

        @Test
        @DisplayName("带版本段的 base-url 可构建客户端")
        fun versionedBaseUrlBuildsClient() {
            val client = ApiPathResolver.buildOpenAiClient(
                "https://api.moonshot.cn/v1", "test-key"
            )
            assertThat(client).isNotNull()
        }

        @Test
        @DisplayName("不带版本段的 base-url 可构建客户端")
        fun plainBaseUrlBuildsClient() {
            val client = ApiPathResolver.buildOpenAiClient(
                "https://api.openai.com", "test-key"
            )
            assertThat(client).isNotNull()
        }

        @Test
        @DisplayName("自定义超时参数生效")
        fun customTimeouts() {
            val client = ApiPathResolver.buildOpenAiClient(
                "https://api.openai.com", "test-key", 5000, 60000
            )
            assertThat(client).isNotNull()
        }
    }
}

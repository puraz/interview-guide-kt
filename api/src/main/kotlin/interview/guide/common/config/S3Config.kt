package interview.guide.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * S3客户端配置（用于RustFS）
 */
@Configuration
class S3Config(
    private val storageConfig: StorageConfigProperties // 存储配置属性
) {

    /**
     * 构建 S3Client
     *
     * @return S3Client 实例 // RustFS 访问客户端
     */
    @Bean
    fun s3Client(): S3Client {
        val accessKey = requireNotNull(storageConfig.accessKey) { "app.storage.access-key 未配置" }
        val secretKey = requireNotNull(storageConfig.secretKey) { "app.storage.secret-key 未配置" }
        val endpoint = requireNotNull(storageConfig.endpoint) { "app.storage.endpoint 未配置" }

        val credentials = AwsBasicCredentials.create(accessKey, secretKey)

        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(storageConfig.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .overrideConfiguration(clientOverrideConfiguration())
            // 使用路径风格访问，避免 SDK 使用 bucket.endpoint 导致 DNS 解析失败。
            .forcePathStyle(true)
            .build()
    }

    /**
     * 构建客户端超时覆盖配置
     *
     * @return ClientOverrideConfiguration // 包含 API 调用超时设置
     */
    private fun clientOverrideConfiguration(): ClientOverrideConfiguration {
        val builder = ClientOverrideConfiguration.builder()
        val apiCallTimeout: Duration? = storageConfig.apiCallTimeout
        val apiCallAttemptTimeout: Duration? = storageConfig.apiCallAttemptTimeout
        if (apiCallTimeout != null) {
            builder.apiCallTimeout(apiCallTimeout)
        }
        if (apiCallAttemptTimeout != null) {
            builder.apiCallAttemptTimeout(apiCallAttemptTimeout)
        }
        return builder.build()
    }
}

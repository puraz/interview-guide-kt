package interview.guide.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

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
        val credentials = AwsBasicCredentials.create(
            storageConfig.accessKey,
            storageConfig.secretKey
        )

        return S3Client.builder()
            .endpointOverride(URI.create(storageConfig.endpoint))
            .region(Region.of(storageConfig.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .forcePathStyle(true) // 使用路径风格访问，避免 DNS 解析失败
            .build()
    }
}

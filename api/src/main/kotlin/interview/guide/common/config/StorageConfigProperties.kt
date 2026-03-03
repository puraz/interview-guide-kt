package interview.guide.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * RustFS (S3兼容) 存储配置属性
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
class StorageConfigProperties {
    var endpoint: String? = null // 存储服务地址
    var accessKey: String? = null // 访问Key
    var secretKey: String? = null // 密钥
    var bucket: String? = null // 存储桶名称
    var region: String = "us-east-1" // 区域
}

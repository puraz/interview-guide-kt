package interview.guide.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 应用配置属性
 */
@Component
@ConfigurationProperties(prefix = "app.resume")
class AppConfigProperties {

    var uploadDir: String? = null // 简历上传目录
    var allowedTypes: List<String> = emptyList() // 允许的简历 MIME 类型列表
}

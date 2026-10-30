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

    // Java HEAD 默认 null；Kotlin 侧 FileValidationService 以非空 List 接收，故默认空列表（未配置时拒绝全部上传，不伪造放行）
    var allowedTypes: List<String> = emptyList() // 允许的简历 MIME 类型列表
}

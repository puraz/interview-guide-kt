package interview.guide.common.config

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * 知识库同步上传链路的单实例并发配置，修改后需重启应用。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.knowledge-base-upload")
class KnowledgeBaseUploadProperties {

    @field:Min(1)
    var maxConcurrent: Int = 4 // 单实例允许同时处理的上传数
}

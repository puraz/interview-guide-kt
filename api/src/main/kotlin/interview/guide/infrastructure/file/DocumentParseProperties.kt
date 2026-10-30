package interview.guide.infrastructure.file

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * 文档解析的超时与有界线程池配置。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.document-parse")
class DocumentParseProperties {

    /**
     * 单次文档解析的超时时间
     */
    @field:NotNull
    var timeout: Duration = Duration.ofMinutes(2)

    /**
     * 解析线程池的核心/最大线程数
     */
    @field:Min(1)
    @field:Max(16)
    var poolSize: Int = 2

    /**
     * 解析任务等待队列容量
     */
    @field:Min(1)
    var queueCapacity: Int = 20
}

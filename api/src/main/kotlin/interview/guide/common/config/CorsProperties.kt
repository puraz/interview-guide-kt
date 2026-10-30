package interview.guide.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * CORS 跨域属性
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
class CorsProperties {

    var allowedOrigins: String = "http://localhost:5173" // 允许的域名列表，多个用逗号分隔
}

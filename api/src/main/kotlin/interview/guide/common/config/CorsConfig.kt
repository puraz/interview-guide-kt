package interview.guide.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CORS跨域配置
 */
@Configuration
class CorsConfig(
    @Value("\${app.cors.allowed-origins:http://localhost:5173}") private val allowedOrigins: String // 允许的域名列表
) {

    /**
     * 构建 CORS 过滤器
     *
     * @return CorsFilter // 跨域过滤器
     */
    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()
        allowedOrigins.split(",").map { it.trim() }.forEach { config.addAllowedOrigin(it) }
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = true
        config.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return CorsFilter(source)
    }
}

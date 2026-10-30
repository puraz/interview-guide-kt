package interview.guide.common.config

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
    private val corsProperties: CorsProperties // 跨域配置属性
) {

    /**
     * 构建 CORS 过滤器
     *
     * @return CorsFilter // 跨域过滤器
     */
    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()

        // 允许的源：配置项按逗号拆分，逐项 trim 后注册
        corsProperties.allowedOrigins.split(",")
            .map { it.trim() }
            .forEach { config.addAllowedOrigin(it) }

        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS") // 允许的请求方法
        config.allowedHeaders = listOf("*") // 允许的请求头
        config.allowCredentials = true // 允许携带凭证
        config.maxAge = 3600L // 预检结果缓存时间（秒）

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)

        return CorsFilter(source)
    }
}

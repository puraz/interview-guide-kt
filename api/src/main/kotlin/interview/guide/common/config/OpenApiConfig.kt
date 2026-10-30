package interview.guide.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 文档配置
 */
@Configuration
class OpenApiConfig {

    /**
     * 构建 OpenAPI 元信息
     *
     * @return OpenAPI // Swagger 文档描述信息
     */
    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("智能 AI 面试官平台 API")
                    .description("简历分析、模拟面试、知识库管理 RESTful API 文档")
                    .version("1.0.0")
            )
    }
}

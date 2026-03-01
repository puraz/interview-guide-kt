package com.example.framework.core.swagger

import com.example.framework.core.swagger.properties.SwaggerProperties
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 3 配置（Spring Boot 3 官方推荐：springdoc-openapi）
 *
 * 说明：
 * 1. 生成 OpenAPI 文档：`/v3/api-docs`
 * 2. Swagger UI：`/swagger-ui.html`（会重定向到 `/swagger-ui/index.html`）
 */
@Configuration
@EnableConfigurationProperties(SwaggerProperties::class)
class OpenApiConfiguration(
    private val swaggerProperties: SwaggerProperties,
) {

    @Bean
    fun openApi(): OpenAPI {
        val info = Info()
            .title(swaggerProperties.title) // 文档标题
            .description(swaggerProperties.description) // 文档描述
            .version(swaggerProperties.version) // 文档版本
            .termsOfService(swaggerProperties.termsOfServiceUrl) // 服务条款地址
            .license(
                License()
                    .name(swaggerProperties.license) // 许可证名称
                    .url(swaggerProperties.licenseUrl) // 许可证URL
            )

        swaggerProperties.contact?.let { contact ->
            info.contact(
                io.swagger.v3.oas.models.info.Contact()
                    .name(contact.name) // 联系人名称
                    .url(contact.url) // 联系人URL
                    .email(contact.email) // 联系人邮箱
            )
        }

        val openApi = OpenAPI().info(info)

        // 如果配置了host，则作为默认Server展示（用于在Swagger UI里“Try it out”时拼接请求地址）
        val host = swaggerProperties.host?.trim()
        if (!host.isNullOrBlank()) {
            openApi.servers(listOf(Server().url(host)))
        }

        return openApi
    }

    @Bean
    fun defaultGroupedOpenApi(): GroupedOpenApi {
        val builder = GroupedOpenApi.builder()
            .group(swaggerProperties.group) // 分组名称
            .pathsToMatch("/**") // 匹配全部接口

        val basePackage = swaggerProperties.basePackage?.trim()
        if (!basePackage.isNullOrBlank()) {
            builder.packagesToScan(basePackage) // 仅扫描指定包（减少不必要的接口暴露）
        }

        return builder.build()
    }
}


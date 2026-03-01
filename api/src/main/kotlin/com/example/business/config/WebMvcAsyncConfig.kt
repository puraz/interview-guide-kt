package com.example.business.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MVC 异步请求超时配置。
 *
 * 作用说明：
 * - 解决 suspend 接口在首刷生成餐单时耗时较长导致的 AsyncRequestTimeoutException。
 * - 仅调整默认异步超时，不影响业务逻辑与响应结构。
 */
@Configuration
class WebMvcAsyncConfig : WebMvcConfigurer {

    /**
     * 配置 MVC 异步处理参数。
     *
     * @param configurer AsyncSupportConfigurer 异步支持配置器 // 用于设置默认超时、线程池等参数
     */
    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.setDefaultTimeout(DEFAULT_ASYNC_TIMEOUT_MS) // 默认异步超时（毫秒）// 覆盖 Spring MVC 默认值
    }

    companion object {
        private const val DEFAULT_ASYNC_TIMEOUT_MS: Long = 120_000L // 默认异步超时：2分钟 // 覆盖首刷生成耗时
    }
}

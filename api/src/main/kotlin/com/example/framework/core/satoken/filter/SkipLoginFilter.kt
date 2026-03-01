package com.example.framework.core.satoken.filter

import com.example.framework.core.satoken.annotation.SkipLogin
import com.example.framework.core.satoken.utils.SkipLoginUtil
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.DispatcherServlet

/**
 * 免登录过滤器配置
 *
 * @author gcc
 */
@Configuration
class SkipLoginFilterConfig {

    @Bean
    fun skipLoginFilterRegistration(): FilterRegistrationBean<SkipLoginFilter> {
        val registration = FilterRegistrationBean(SkipLoginFilter())
        registration.order = 0 // 确保在Sa-Token过滤器之前执行
        registration.addUrlPatterns("/*")
        return registration
    }
}

/**
 * 免登录过滤器
 *
 * 说明：
 * 在Sa-Token过滤器之前执行，检查接口是否有@SkipLogin注解
 * 如果有则在请求属性中设置标记
 *
 * @author gcc
 */
class SkipLoginFilter : Filter {

    private val logger = LoggerFactory.getLogger(this::class.java)

    @Volatile
    private var dispatcherServlet: DispatcherServlet? = null

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        if (request is HttpServletRequest && response is HttpServletResponse) {
            try {
                checkSkipLoginAnnotation(request)
            } catch (e: Exception) {
                logger.debug("检查免登录注解时发生异常: ${e.message}")
            }
        }

        chain.doFilter(request, response)
    }

    private fun checkSkipLoginAnnotation(request: HttpServletRequest) {
        try {
            // 获取DispatcherServlet
            if (dispatcherServlet == null) {
                val servletContext = request.servletContext
                val webApplicationContext = org.springframework.web.context.support.WebApplicationContextUtils
                    .getWebApplicationContext(servletContext)
                dispatcherServlet = webApplicationContext?.getBean(DispatcherServlet::class.java)
            }

            dispatcherServlet?.let { servlet ->
                // 获取HandlerMapping
                val handlerMappings = servlet.handlerMappings
                if (handlerMappings != null) {
                    for (handlerMapping in handlerMappings) {
                        try {
                            val handler = handlerMapping.getHandler(request)
                            if (handler?.handler is HandlerMethod) {
                                val handlerMethod = handler.handler as HandlerMethod

                                // 检查方法上的注解
                                if (handlerMethod.method.isAnnotationPresent(SkipLogin::class.java)) {
                                    request.setAttribute(SkipLoginUtil.SKIP_LOGIN_ATTRIBUTE, true)
                                    logger.debug("检测到方法级免登录注解: ${handlerMethod.method.name}")
                                    return
                                }

                                // 检查类上的注解
                                if (handlerMethod.beanType.isAnnotationPresent(SkipLogin::class.java)) {
                                    request.setAttribute(SkipLoginUtil.SKIP_LOGIN_ATTRIBUTE, true)
                                    logger.debug("检测到类级免登录注解: ${handlerMethod.beanType.simpleName}")
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            // 继续尝试下一个HandlerMapping
                            continue
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("获取Handler时发生异常: ${e.message}")
        }
    }
}
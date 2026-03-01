package com.example.framework.core.satoken.interceptor

import com.example.framework.core.satoken.utils.SkipLoginUtil
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 免登录拦截器
 *
 * 说明：
 * 检查接口是否有@SkipLogin注解，如果有则跳过登录验证
 *
 * @author gcc
 */
@Component
class SkipLoginInterceptor : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // 这个拦截器现在主要用于日志记录，实际的注解检查由Filter完成
        if (handler is HandlerMethod) {
            val skipLoginFlag = request.getAttribute(SkipLoginUtil.SKIP_LOGIN_ATTRIBUTE) as? Boolean
            if (skipLoginFlag == true) {
                logger.debug("拦截器确认免登录标记已设置")
            }
        }

        return true
    }
}
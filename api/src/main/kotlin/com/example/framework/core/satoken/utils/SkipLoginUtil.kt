package com.example.framework.core.satoken.utils

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * 免登录工具类
 *
 * @author gcc
 */
@Component
class SkipLoginUtil {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 检查当前请求是否有免登录注解
     * @return Boolean
     */
    fun hasSkipLoginAnnotation(): Boolean {
        return try {
            val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val request = attributes?.request ?: return false

            // 检查请求属性中是否已经设置了免登录标记（由Filter设置）
            val skipLoginFlag = request.getAttribute(SKIP_LOGIN_ATTRIBUTE) as? Boolean
            if (skipLoginFlag == true) {
                logger.debug("检测到免登录标记")
                return true
            }

            false
        } catch (e: Exception) {
            logger.debug("检查免登录注解时发生异常: ${e.message}")
            false
        }
    }

    companion object {
        const val SKIP_LOGIN_ATTRIBUTE = "SKIP_LOGIN_FLAG"
    }
}
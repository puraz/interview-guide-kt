package com.example.framework.core.xss.properties

import cn.hutool.core.text.StrPool
import cn.hutool.core.util.StrUtil
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.AntPathMatcher
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * XSS防护配置参数
 *
 * @author gcc
 */
@ConfigurationProperties(prefix = XssProperties.PREFIX)
class XssProperties {
    companion object {
        const val PREFIX = "zeta.xss"

        /** AntPath规则匹配器 */
        private val ANT_PATH_MATCHER = AntPathMatcher()
    }

    /** XSS防护开关 默认为：false */
    var enabled: Boolean = false

    /** 基础忽略xss防护的地址 */
    private val baseUrl: MutableSet<String> = mutableSetOf(
        "/swagger-ui.html", // Springdoc Swagger UI 入口（重定向到 /swagger-ui/index.html）
        "/swagger-ui/**", // Springdoc Swagger UI 静态资源
        "/v3/api-docs", // OpenAPI 3 JSON
        "/v3/api-docs/**", // OpenAPI 3 分组/扩展路径（如 /v3/api-docs/default）
        "/v3/api-docs.yaml", // OpenAPI 3 YAML
    )

    /** 忽略xss防护的地址 */
    var excludeUrl: MutableSet<String> = mutableSetOf(
        "/**/noxss/**"
    )

    /**
     * 获取忽略xss防护的地址
     */
    fun getNotMatchUrl(): MutableSet<String> {
        return mutableSetOf<String>().apply {
            addAll(baseUrl)
            addAll(excludeUrl)
        }
    }

    /**
     * 是否是忽略xss防护的地址
     *
     * 匹配三种类型的路由地址
     * ```
     * 1.带*号的。用ANT_PATH_MATCHER处理
     * 2.类似“/api/user/save”、“/swagger-ui.html”这样的。用ANT_PATH_MATCHER处理
     * 3.带请求方式的，类似“GET:/api/demo”、“POST:/api/demo”这样的。“:”切割后再用ANT_PATH_MATCHER处理
     * ```
     *
     * @param requestMethod 请求方式 GET、PUT、POST...
     * @param path 请求地址
     * @return boolean
     */
    fun isIgnoreUrl(requestMethod: String, path: String): Boolean {
        return this.getNotMatchUrl().any { url ->
            // 为什么要封装一个方法，因为any{}是inline函数，不能用return
            check(requestMethod, url, path)
        }
    }

    /**
     * 匹配三种类型的路由地址
     *
     * ```
     * 1.带*号的。用ANT_PATH_MATCHER处理
     * 2.类似“/api/user/save”、“/swagger-ui.html”这样的。用ANT_PATH_MATCHER处理
     * 3.带请求方式的，类似“GET:/api/demo”、“POST:/api/demo”这样的。“:”切割后再用ANT_PATH_MATCHER处理
     * ```
     *
     * @param requestMethod 请求方式 GET、PUT、POST...
     * @param url 匹配地址
     * @param path 请求地址
     * @return 是否匹配
     */
    private fun check(requestMethod: String, url: String, path: String): Boolean {
        // 先正常匹配一遍。
        if (ANT_PATH_MATCHER.match(url, path)) return true

        // 没匹配到，就将ignoreUrl中包含“:"的切开来单独匹配
        if (StrUtil.contains(url, StrPool.COLON)) {
            // 将url切开。 例如："GET:/api/demo" -> ["GET", "/api/demo"]
            val array = url.split(StrPool.COLON)

            // 只处理请求方式一致的
            if (requestMethod.equals(array[0], true)) {
                return ANT_PATH_MATCHER.match(array[1], path)
            }
        }

        return false
    }

    /**
     * 是否是忽略xss防护的地址
     *
     * @return boolean
     */
    fun isIgnoreUrl(): Boolean {
        val attributes = RequestContextHolder.getRequestAttributes() as ServletRequestAttributes?
        // 获取当前访问的路由。获取不到直接return false
        return attributes?.request?.let {
            isIgnoreUrl(it.method, it.requestURI)
        } ?: false
    }

}

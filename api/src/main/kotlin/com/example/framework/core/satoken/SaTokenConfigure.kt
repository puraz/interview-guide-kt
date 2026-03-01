package com.example.framework.core.satoken

import cn.dev33.satoken.context.SaHolder
import cn.dev33.satoken.exception.NotLoginException
import cn.dev33.satoken.exception.NotPermissionException
import cn.dev33.satoken.exception.NotRoleException
import cn.dev33.satoken.filter.SaFilterAuthStrategy
import cn.dev33.satoken.filter.SaServletFilter
import cn.dev33.satoken.`fun`.SaFunction
import cn.dev33.satoken.jwt.StpLogicJwtForMixin
import cn.dev33.satoken.jwt.StpLogicJwtForSimple
import cn.dev33.satoken.jwt.StpLogicJwtForStateless
import cn.dev33.satoken.router.SaHttpMethod
import cn.dev33.satoken.router.SaRouter
import cn.dev33.satoken.spring.SpringMVCUtil
import cn.dev33.satoken.stp.StpLogic
import cn.dev33.satoken.stp.StpUtil
import com.example.framework.base.result.ApiResult
import com.example.framework.core.enums.ErrorCodeEnum
import com.example.framework.core.satoken.enums.TokenTypeEnum
import com.example.framework.core.satoken.interceptor.ClearThreadLocalInterceptor
import com.example.framework.core.satoken.interceptor.SkipLoginInterceptor
import com.example.framework.core.satoken.properties.IgnoreProperties
import com.example.framework.core.satoken.properties.TokenProperties
import com.example.framework.core.satoken.utils.SkipLoginUtil
import com.example.framework.core.utils.ContextUtil
import com.example.framework.core.utils.JSONUtil
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.util.AntPathMatcher
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer


/**
 * [Sa-Token 权限认证] 配置类
 * @author gcc
 */
@Configuration
@EnableConfigurationProperties(IgnoreProperties::class, TokenProperties::class)
class SaTokenConfigure(
    private val ignoreProperties: IgnoreProperties,
    private val tokenProperties: TokenProperties,
    private val skipLoginInterceptor: SkipLoginInterceptor,
) : WebMvcConfigurer {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // Ant风格路径匹配器：用于匹配忽略鉴权路由（兼容 /**/*.js、/**/noToken/** 等写法）
    private val antPathMatcher = AntPathMatcher()


    /**
     * 跨域配置
     *
     * 说明：
     * 非saToken拦截的接口的跨域配置
     * @param registry CorsRegistry
     */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedHeaders("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowCredentials(true)
            .maxAge(3600L)
    }

    /**
     * SaToken过滤器[前置函数]：在每次[认证函数]之前执行
     *
     * 说明：
     * saToken拦截的接口的跨域配置
     * BeforeAuth 不受 includeList 与 excludeList 的限制，所有请求都会进入
     */
    private val beforeAuth: SaFilterAuthStrategy = SaFilterAuthStrategy {
        // saToken跨域配置
        SaHolder.getResponse()
            .setHeader("Access-Control-Allow-Origin", "*")
            .setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH")
            .setHeader("Access-Control-Max-Age", "3600")
            .setHeader("Access-Control-Allow-Headers", "*")
            // 是否启用浏览器默认XSS防护： 0=禁用 | 1=启用 | 1; mode=block 启用, 并在检查到XSS攻击时，停止渲染页面
            .setHeader("X-XSS-Protection", "1; mode=block")

        // 如果是预检请求，则立即返回到前端
        SaRouter.match(SaHttpMethod.OPTIONS).back()
    }

    /**
     * SaToken过滤器[认证函数]: 每次请求都会执行
     *
     * 说明：
     * saToken接口拦截并处理
     * 主要是校验token是否有效，对token进行续期，设置用户id和token到ThreadLocal中
     */
    private val auth: SaFilterAuthStrategy = SaFilterAuthStrategy {
        // 需要登录认证的路由:所有, 排除登录认证的路由:/api/login、swagger等
        SaRouter.match("/**").check(SaFunction {
            // 获取当前请求信息（可能为空，例如在非HTTP线程中触发）
            val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            val request = attributes?.request
            val requestMethod = request?.method // 请求方式：GET/POST/... // 用于兼容 "GET:/api/demo" 这类忽略配置
            val requestPath = request?.requestURI // 请求路径：/swagger-ui.html、/api/login ... // 用于判断是否需要鉴权

            // 判断当前请求是否属于放行路由：放行路由不做登录校验（例如 swagger、登录、静态资源等）
            if (requestPath != null && isIgnoreTokenUrl(requestMethod, requestPath)) {
                return@SaFunction
            }

            // 检查是否有免登录标记，如果有则跳过登录验证
            val skipLogin = attributes?.request?.getAttribute(SkipLoginUtil.SKIP_LOGIN_ATTRIBUTE) as? Boolean ?: false

            if (skipLogin) {
                // 对于带有 @SkipLogin 的接口：不强制校验登录，但如果客户端已经登录，则仍然注入上下文
                if (StpUtil.isLogin()) {
                    // 续期（仅当已登录且配置开启）
                    if (tokenProperties.autoRenew) {
                        StpUtil.renewTimeout(tokenProperties.expireTime)
                    }
                    // 将用户信息放入 ThreadLocal，便于业务读取
                    ContextUtil.setUserId(StpUtil.getLoginIdAsLong())
                    ContextUtil.setToken(StpUtil.getTokenValue())
                    logger.debug("检测到免登录标记，但用户已登录，已注入上下文信息")
                } else {
                    logger.debug("检测到免登录标记，且用户未登录，跳过登录验证与上下文注入")
                }
                return@SaFunction
            }

            // 需要登录的接口
            StpUtil.checkLogin()

            // token续期
            if (tokenProperties.autoRenew) {
                StpUtil.renewTimeout(tokenProperties.expireTime)
            }

            // 获取用户id，并设置到ThreadLocal中
            ContextUtil.setUserId(StpUtil.getLoginIdAsLong())
            ContextUtil.setToken(StpUtil.getTokenValue())
        })
    }

    /**
     * 判断是否为 Sa-Token 放行路由
     *
     * 说明：
     * 1.这里必须使用 AntPathMatcher 来匹配 ignore 配置，因为 Spring 2.6+ 的 PathPatternParser 对 `**` 规则更严格
     * 2.如果直接把 ignore 列表交给 SaServletFilter 的 excludeList，可能会触发：
     *   "No more pattern data allowed after {*...} or ** pattern element"
     *
     * 支持的忽略配置形式：
     * - `"/swagger-ui/"` // swagger 静态资源
     * - `"/**/*.js"` // 任意层级静态资源
     * - `"GET:/api/demo"` // 带请求方式的忽略配置
     *
     * @param requestMethod 请求方式（可能为null） // 用于匹配 "GET:/xxx" 形式
     * @param path 请求路径（requestURI） // 用于匹配 ignore 列表
     * @return 是否放行（true=不做登录校验）
     */
    private fun isIgnoreTokenUrl(requestMethod: String?, path: String): Boolean {
        return ignoreProperties.getNotMatchUrl().any { url ->
            // 先按普通Ant规则匹配（例如 "/swagger-ui/**"、"/**/*.js"）
            if (antPathMatcher.match(url, path)) return@any true

            // 兼容 "GET:/api/demo" 这种带请求方式的写法：请求方式一致时再匹配路径部分
            val index = url.indexOf(':')
            if (index > 0 && requestMethod != null) {
                val methodInRule = url.substring(0, index)
                val pathInRule = url.substring(index + 1)
                return@any requestMethod.equals(methodInRule, ignoreCase = true) && antPathMatcher.match(
                    pathInRule,
                    path
                )
            }

            false
        }
    }

    /**
     * 拦截器配置
     *
     * 说明：
     * 可以在这里使用[拦截器鉴权](https://sa-token.cc/doc.html#/use/route-check)
     * 针对某个接口，某些接口单独进行权限校验
     * @param registry InterceptorRegistry
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        // 免登录注解检查拦截器，需要在sa-token过滤器之前执行
        registry.addInterceptor(skipLoginInterceptor).addPathPatterns("/**").order(-1)
        // 清空ThreadLocal数据拦截器。
        registry.addInterceptor(ClearThreadLocalInterceptor()).addPathPatterns("/api/**")
    }

    /**
     * 注册 [Sa-Token全局过滤器]
     * @return FilterRegistrationBean<SaServletFilter>
     */
    @Bean
    fun saServletFilterRegistration(): FilterRegistrationBean<SaServletFilter> {
        val filter = SaServletFilter()
            // 指定拦截路由
            .addInclude("/**")
            // 放行路由在 auth 中手动判断：避免 excludeList 使用严格的 PathPattern 规则导致 swagger 资源访问报错
            .setBeforeAuth(beforeAuth)
            .setAuth(auth)
            .setError(this::returnFail)

        val registration = FilterRegistrationBean(filter)
        registration.order = 1 // 设置较低的优先级，让拦截器先执行
        registration.addUrlPatterns("/*")
        return registration
    }


    /**
     * Sa-Token token风格配置
     * @return StpLogic
     */
    @Bean
    fun stpLogic(): StpLogic {
        return when (tokenProperties.type) {
            TokenTypeEnum.SIMPLE -> {
                logger.info("检测到sa-token采用了[jwt-simple模式]")
                StpLogicJwtForSimple()
            }

            TokenTypeEnum.MIXIN -> {
                logger.info("检测到sa-token采用了[jwt-mixin模式]")
                StpLogicJwtForMixin()
            }

            TokenTypeEnum.STATELESS -> {
                logger.info("检测到sa-token采用了[jwt-stateless模式]")
                StpLogicJwtForStateless()
            }

            else -> {
                logger.info("检测到sa-token采用了default模式")
                StpLogic(StpUtil.TYPE)
            }
        }
    }


    /**
     * return 错误消息
     *
     * 注意：这里的异常不会被GlobalExceptionHandler(全局异常处理器)捕获处理
     * @param e
     * @return
     */
    private fun returnFail(e: Throwable): String? {
        // 初始化错误码和错误信息
        var statusCode: Int = HttpStatus.BAD_REQUEST.value()
        var code: Int = ErrorCodeEnum.FAIL.code
        var message: String? = ""

        when (e) {
            // 处理NotLoginException异常的错误信息
            is NotLoginException -> {
                message = when (e.type) {
                    NotLoginException.NOT_TOKEN -> NotLoginException.NOT_TOKEN_MESSAGE
                    NotLoginException.INVALID_TOKEN -> NotLoginException.INVALID_TOKEN_MESSAGE
                    NotLoginException.TOKEN_TIMEOUT -> NotLoginException.TOKEN_TIMEOUT_MESSAGE
                    NotLoginException.BE_REPLACED -> NotLoginException.BE_REPLACED_MESSAGE
                    NotLoginException.KICK_OUT -> NotLoginException.KICK_OUT_MESSAGE
                    NotLoginException.TOKEN_FREEZE -> NotLoginException.TOKEN_FREEZE_MESSAGE
                    NotLoginException.NO_PREFIX -> NotLoginException.NO_PREFIX_MESSAGE
                    else -> NotLoginException.DEFAULT_MESSAGE
                }
                code = ErrorCodeEnum.UNAUTHORIZED.code
                statusCode = HttpStatus.UNAUTHORIZED.value()
            }
            // 处理NotRoleException和NotPermissionException异常的错误信息
            is NotRoleException, is NotPermissionException -> {
                message = ErrorCodeEnum.FORBIDDEN.msg
                code = ErrorCodeEnum.FORBIDDEN.code
                statusCode = HttpStatus.FORBIDDEN.value()
            }
            // 处理其它异常的错误信息
            else -> message = e.message
        }

        // 手动设置Content-Type为json格式，替换之前重写SaServletFilter.doFilter方法的写法
        SpringMVCUtil.getResponse().apply {
            this.setHeader("Content-Type", "application/json;charset=utf-8")
            this.status = statusCode
        }
        return JSONUtil.toJsonStr(ApiResult<Boolean>(code, message))
    }

}

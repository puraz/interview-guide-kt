package interview.guide.common.aspect

import interview.guide.common.annotation.RateLimit
import interview.guide.common.exception.RateLimitExceededException
import jakarta.annotation.PostConstruct
import jakarta.servlet.http.HttpServletRequest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 限流 AOP 切面
 * 支持可重复注解，同一方法上的多条限流规则在一次 Lua 调用中原子检查和扣减
 */
@Aspect
@Component
class RateLimitAspect(
    private val redissonClient: RedissonClient // Redisson 客户端
) {

    private val log = LoggerFactory.getLogger(RateLimitAspect::class.java)

    private lateinit var rScript: RScript // Redis 脚本执行器

    private var luaScriptSha: String? = null // 已加载 Lua 脚本的 SHA1

    companion object {
        private const val SCRIPT_PATH = "scripts/rate_limit_single.lua" // 限流 Lua 脚本路径

        // Lua 脚本内容：类加载时一次性读取（与 Java HEAD 的静态初始化一致）
        private val LUA_SCRIPT: String = try {
            val resource = ClassPathResource(SCRIPT_PATH)
            String(resource.contentAsByteArray, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("加载限流 Lua 脚本失败", e)
        }
    }

    /**
     * 初始化：获取脚本执行器并预加载脚本，减少首次调用开销
     */
    @PostConstruct
    fun init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE)
        loadScript()
    }

    /**
     * 加载脚本到 Redis 并记录 SHA1
     *
     * @return 脚本 SHA1 // 供 evalSha 使用
     */
    private fun loadScript(): String {
        val sha = rScript.scriptLoad(LUA_SCRIPT)
        luaScriptSha = sha
        log.info("限流 Lua 脚本加载完成, SHA1: {}", sha)
        return sha
    }

    /**
     * 环绕通知：拦截带 @RateLimit 或 @RateLimit.Container 注解的方法
     *
     * @param joinPoint 连接点 // 目标方法调用
     * @return 目标方法返回值 / 降级方法返回值 // 限流通过后继续执行
     */
    @Around(
        "@annotation(interview.guide.common.annotation.RateLimit) || " +
            "@annotation(interview.guide.common.annotation.RateLimit.Container)"
    )
    fun around(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val className = method.declaringClass.simpleName
        val methodName = method.name

        // 同一方法上所有限流规则（可重复注解展开）
        val rules = method.getAnnotationsByType(RateLimit::class.java)
        val nowMs = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        val keysList = ArrayList<Any>(rules.size)
        val args = ArrayList<Any>(3 + rules.size * 3)
        val contexts = ArrayList<RateLimitContext>(rules.size)
        args.add(nowMs.toString()) // ARGV[1]: 当前时间戳（毫秒）
        args.add(requestId) // ARGV[2]: 请求唯一标识
        args.add(rules.size.toString()) // ARGV[3]: 规则数量

        for (rule in rules) {
            val intervalMs = calculateIntervalMs(rule.interval, rule.timeUnit)
            val key = generateKey(className, methodName, rule.dimension)

            keysList.add(key)
            args.add("1") // 申请令牌数
            args.add(intervalMs.toString()) // 时间窗口（毫秒）
            args.add(rule.count.toString()) // 窗口内允许的最大令牌数
            contexts.add(RateLimitContext(rule, key))
        }

        val result = executeRateLimitScript(keysList, args.toTypedArray())
        if (result == null || result <= 0) {
            val failedContext = resolveFailedContext(result, contexts)
            return handleRateLimitExceeded(joinPoint, failedContext.rule, failedContext.key)
        }

        return joinPoint.proceed()
    }

    /**
     * 执行限流 Lua 脚本
     *
     * @param keysList 限流维度键列表 // 每个维度一个 Key
     * @param args 脚本参数 // ARGV
     * @return 脚本返回值 // 1=通过，-i=第 i 条规则被拒绝
     */
    private fun executeRateLimitScript(keysList: List<Any>, args: Array<Any>): Long? {
        var sha = luaScriptSha ?: loadScript()
        return try {
            val resultObj: Any? = rScript.evalSha<Any>(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.VALUE,
                keysList,
                *args
            )
            convertToLong(resultObj)
        } catch (e: RedisException) {
            // Redis 重启后脚本缓存丢失，重新加载并重试
            val errorMessage = e.message
            if (errorMessage != null && errorMessage.contains("NOSCRIPT")) {
                sha = loadScript()
                val resultObj: Any? = rScript.evalSha<Any>(
                    RScript.Mode.READ_WRITE,
                    sha,
                    RScript.ReturnType.VALUE,
                    keysList,
                    *args
                )
                convertToLong(resultObj)
            } else {
                throw e
            }
        }
    }

    /**
     * 根据脚本返回值定位被拒绝的规则
     *
     * @param result 脚本返回值 // 负数表示第几条规则被拒绝
     * @param contexts 规则上下文列表 // 与脚本规则顺序一致
     * @return 失败规则上下文 // 无法定位时返回第一条规则
     */
    private fun resolveFailedContext(result: Long?, contexts: List<RateLimitContext>): RateLimitContext {
        if (result != null && result < 0) {
            val failedIndex = minOf(Math.abs(result) - 1, (contexts.size - 1).toLong()).toInt()
            if (failedIndex >= 0) {
                return contexts[failedIndex]
            }
        }
        return contexts[0]
    }

    /**
     * 计算时间窗口毫秒数
     *
     * @param interval 时间窗口大小 // 数量
     * @param unit 时间单位 // 单位枚举
     * @return 毫秒数 // 换算后的时间窗口
     */
    private fun calculateIntervalMs(interval: Long, unit: RateLimit.TimeUnit): Long {
        return when (unit) {
            RateLimit.TimeUnit.MILLISECONDS -> interval
            RateLimit.TimeUnit.SECONDS -> interval * 1000
            RateLimit.TimeUnit.MINUTES -> interval * 60 * 1000
            RateLimit.TimeUnit.HOURS -> interval * 3600 * 1000
            RateLimit.TimeUnit.DAYS -> interval * 86400 * 1000
        }
    }

    /**
     * 将脚本返回值安全转换为 Long
     *
     * @param obj 脚本原始返回值 // Number / String / null
     * @return 转换结果 // 无法转换时返回 null
     */
    private fun convertToLong(obj: Any?): Long? {
        if (obj is Number) {
            return obj.toLong()
        }
        if (obj is String) {
            return try {
                obj.toLong()
            } catch (e: NumberFormatException) {
                log.warn("无法将字符串转换为Long: {}", obj)
                null
            }
        }
        log.warn("不支持的对象类型转换为Long: {}", obj?.javaClass?.name ?: "null")
        return null
    }

    /**
     * 生成限流 Redis Key
     *
     * @param className 目标类名 // 用于隔离不同类
     * @param methodName 目标方法名 // 用于隔离不同方法
     * @param dimension 限流维度 // GLOBAL/IP/USER
     * @return Redis Key // 使用 HashTag 保证同方法规则落在同一 Slot
     */
    private fun generateKey(className: String, methodName: String, dimension: RateLimit.Dimension): String {
        val hashTag = "{$className:$methodName}"
        val keyPrefix = "ratelimit:$hashTag"

        return when (dimension) {
            RateLimit.Dimension.GLOBAL -> "$keyPrefix:global"
            RateLimit.Dimension.IP -> "$keyPrefix:ip:${getClientIp()}"
            RateLimit.Dimension.USER -> "$keyPrefix:user:${getCurrentUserId()}"
        }
    }

    /**
     * 处理限流触发场景：优先执行降级方法，否则抛出限流异常
     *
     * @param joinPoint 连接点 // 目标方法调用
     * @param rateLimit 触发的限流规则 // 含降级方法名
     * @param key 触发的限流 Key // 日志用
     * @return 降级方法返回值 // 无降级方法时抛出异常
     */
    private fun handleRateLimitExceeded(
        joinPoint: ProceedingJoinPoint,
        rateLimit: RateLimit,
        key: String
    ): Any? {
        val methodName = joinPoint.signature.name

        if (rateLimit.fallback.isNotEmpty()) {
            try {
                val fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback)
                if (fallbackMethod != null) {
                    log.debug(
                        "限流触发，执行降级方法: {}.{} -> {}",
                        joinPoint.target.javaClass.simpleName,
                        methodName,
                        rateLimit.fallback
                    )
                    return if (fallbackMethod.parameterCount > 0) {
                        fallbackMethod.invoke(joinPoint.target, *joinPoint.args)
                    } else {
                        fallbackMethod.invoke(joinPoint.target)
                    }
                }
            } catch (e: Exception) {
                log.error("降级方法执行失败: {}", rateLimit.fallback, e)
            }
        }

        log.debug(
            "限流触发，拒绝请求: key={}, count={} per {} {}",
            key, rateLimit.count, rateLimit.interval, rateLimit.timeUnit
        )
        throw RateLimitExceededException("请求过于频繁，请稍后再试")
    }

    /**
     * 查找降级方法：先找参数列表一致的方法，再找无参方法
     *
     * @param joinPoint 连接点 // 目标方法调用
     * @param fallbackName 降级方法名 // 注解配置
     * @return 降级方法 // 未找到返回 null
     */
    private fun findFallbackMethod(joinPoint: ProceedingJoinPoint, fallbackName: String): Method? {
        val targetClass = joinPoint.target.javaClass
        val signature = joinPoint.signature as MethodSignature
        val parameterTypes = signature.parameterTypes

        try {
            val method = targetClass.getDeclaredMethod(fallbackName, *parameterTypes)
            method.isAccessible = true
            return method
        } catch (e: NoSuchMethodException) {
            try {
                val method = targetClass.getDeclaredMethod(fallbackName)
                method.isAccessible = true
                return method
            } catch (ex: NoSuchMethodException) {
                log.warn(
                    "未找到降级方法: {}.{} (需无参或参数列表一致)",
                    targetClass.simpleName, fallbackName
                )
                return null
            }
        }
    }

    /**
     * 获取客户端真实 IP
     *
     * @return 客户端 IP // 无法获取时返回 unknown
     */
    private fun getClientIp(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return "unknown"

        val request: HttpServletRequest = attributes.request
        var ip = request.getHeader("X-Forwarded-For")

        if (ip.isNullOrEmpty() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("X-Real-IP")
        }
        if (ip.isNullOrEmpty() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("Proxy-Client-IP")
        }
        if (ip.isNullOrEmpty() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ip.isNullOrEmpty() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.remoteAddr
        }

        // 多级代理时取第一个地址
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim()
        }

        return ip ?: "unknown"
    }

    /**
     * 获取当前用户 ID
     *
     * @return 用户 ID // 无法获取时返回 anonymous
     */
    private fun getCurrentUserId(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return "anonymous"

        val request: HttpServletRequest = attributes.request

        val userId = request.getAttribute("userId")
        if (userId != null) {
            return userId.toString()
        }

        val userIdFromHeader = request.getHeader("X-User-Id")
        if (userIdFromHeader != null) {
            return userIdFromHeader
        }

        return "anonymous"
    }

    /**
     * 限流规则上下文：记录规则与其对应的 Redis Key
     *
     * @property rule 限流规则 // 注解实例
     * @property key 限流 Key // 用于定位失败维度
     */
    private data class RateLimitContext(val rule: RateLimit, val key: String)
}

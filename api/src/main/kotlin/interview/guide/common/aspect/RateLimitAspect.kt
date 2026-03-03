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
 * 基于滑动时间窗口实现的多维度原子限流
 */
@Aspect
@Component
class RateLimitAspect(
    private val redissonClient: RedissonClient // Redisson 客户端
) {

    private val log = LoggerFactory.getLogger(RateLimitAspect::class.java)

    private var luaScriptSha: String? = null // Lua 脚本 SHA

    companion object {
        private var LUA_SCRIPT: String

        init {
            try {
                val resource = ClassPathResource("scripts/rate_limit.lua")
                LUA_SCRIPT = String(resource.contentAsByteArray, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                throw RuntimeException("加载限流 Lua 脚本失败", e)
            }
        }
    }

    /**
     * 初始化：预加载脚本到 Redis 提高性能
     */
    @PostConstruct
    fun init() {
        luaScriptSha = redissonClient.getScript(StringCodec.INSTANCE).scriptLoad(LUA_SCRIPT)
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha)
    }

    /**
     * 环绕通知：拦截带 @RateLimit 注解的方法
     */
    @Around("@annotation(rateLimit)")
    fun around(joinPoint: ProceedingJoinPoint, rateLimit: RateLimit): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val className = method.declaringClass.simpleName
        val methodName = method.name

        // 1. 计算时间窗口（毫秒）
        val intervalMs = calculateIntervalMs(rateLimit.interval, rateLimit.timeUnit)

        // 2. 根据配置维度动态生成 Redis Keys
        val keys = generateKeys(className, methodName, rateLimit.dimensions)

        // 3. 调用 Lua 脚本执行原子限流
        val script = redissonClient.getScript(StringCodec.INSTANCE)
        val keysList = ArrayList<Any>(keys)
        val args = arrayOf(
            System.currentTimeMillis().toString(), // ARGV[1]: 当前时间戳
            "1", // ARGV[2]: 申请令牌数
            intervalMs.toString(), // ARGV[3]: 时间窗口
            rateLimit.count.toString(), // ARGV[4]: 最大令牌数
            UUID.randomUUID().toString() // ARGV[5]: 请求唯一标识
        )

        val resultObj: Any? = script.evalSha<Any>(
            RScript.Mode.READ_WRITE,
            luaScriptSha,
            RScript.ReturnType.VALUE,
            keysList,
            *args
        )

        val result = convertToLong(resultObj)

        // 4. 处理限流结果
        if (result == null || result == 0L) {
            return handleRateLimitExceeded(joinPoint, rateLimit, keys)
        }

        // 5. 执行原方法
        return joinPoint.proceed()
    }

    /**
     * 计算时间窗口毫秒数
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
     * 将结果对象安全转换为 Long
     */
    private fun convertToLong(obj: Any?): Long? {
        return when (obj) {
            null -> null
            is Long -> obj
            is Int -> obj.toLong()
            is Short -> obj.toLong()
            is Byte -> obj.toLong()
            is String -> obj.toLongOrNull()
            else -> {
                log.warn("不支持的对象类型转换为Long: {}", obj.javaClass.name)
                null
            }
        }
    }

    /**
     * 生成限流键列表
     */
    private fun generateKeys(className: String, methodName: String, dimensions: Array<RateLimit.Dimension>): List<String> {
        val keys = ArrayList<String>()
        val hashTag = "{$className:$methodName}" // HashTag 保证落在同一 Slot
        val keyPrefix = "ratelimit:$hashTag"

        for (dimension in dimensions) {
            when (dimension) {
                RateLimit.Dimension.GLOBAL -> keys.add("$keyPrefix:global")
                RateLimit.Dimension.IP -> keys.add("$keyPrefix:ip:${getClientIp()}")
                RateLimit.Dimension.USER -> keys.add("$keyPrefix:user:${getCurrentUserId()}")
            }
        }
        return keys
    }

    /**
     * 处理限流超出情况
     */
    private fun handleRateLimitExceeded(
        joinPoint: ProceedingJoinPoint,
        rateLimit: RateLimit,
        keys: List<String>
    ): Any? {
        val methodName = joinPoint.signature.name

        // 如果配置了降级方法，则调用降级方法
        if (rateLimit.fallback.isNotBlank()) {
            try {
                val fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback)
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                        joinPoint.target.javaClass.simpleName, methodName, rateLimit.fallback)
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

        log.debug("限流触发，拒绝请求: keys={}, count={} per {} {}",
            keys, rateLimit.count, rateLimit.interval, rateLimit.timeUnit)
        throw RateLimitExceededException("请求过于频繁，请稍后再试")
    }

    /**
     * 查找降级方法
     */
    private fun findFallbackMethod(joinPoint: ProceedingJoinPoint, fallbackName: String): Method? {
        val targetClass = joinPoint.target.javaClass
        val signature = joinPoint.signature as MethodSignature
        val parameterTypes = signature.parameterTypes

        return try {
            targetClass.getDeclaredMethod(fallbackName, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            try {
                targetClass.getDeclaredMethod(fallbackName).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                log.warn("未找到降级方法: {}.{} (需无参或参数列表一致)", targetClass.simpleName, fallbackName)
                null
            }
        }
    }

    /**
     * 获取客户端真实 IP
     */
    private fun getClientIp(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return "unknown"
        val request = attributes.request
        var ip = request.getHeader("X-Forwarded-For")

        if (ip.isNullOrBlank() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("X-Real-IP")
        }
        if (ip.isNullOrBlank() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || ip.equals("unknown", ignoreCase = true)) {
            ip = request.remoteAddr
        }

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim()
        }

        return ip ?: "unknown"
    }

    /**
     * 获取当前用户 ID
     */
    private fun getCurrentUserId(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return "anonymous"
        val request: HttpServletRequest = attributes.request

        val userIdFromAttr = request.getAttribute("userId")
        if (userIdFromAttr != null) {
            return userIdFromAttr.toString()
        }

        val userIdFromHeader = request.getHeader("X-User-Id")
        if (userIdFromHeader != null) {
            return userIdFromHeader
        }

        return "anonymous"
    }
}

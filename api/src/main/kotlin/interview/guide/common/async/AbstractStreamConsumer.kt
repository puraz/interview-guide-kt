package interview.guide.common.async

import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.infrastructure.redis.RedisService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redis Stream 消费者模板基类
 * 将消费循环、ACK、重试与生命周期管理收敛到统一模板
 */
abstract class AbstractStreamConsumer<T>(
    private val redisService: RedisService // Redis 操作封装
) {

    private val log = LoggerFactory.getLogger(AbstractStreamConsumer::class.java)
    private val running = AtomicBoolean(false) // 运行标识
    private var executorService: ExecutorService? = null // 消费线程池
    private var consumerName: String? = null // 消费者名称

    /**
     * 初始化消费者
     */
    @PostConstruct
    fun init() {
        consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8)

        try {
            redisService.createStreamGroup(streamKey(), groupName())
            log.info("Redis Stream 消费者组已创建或已存在: {}", groupName())
        } catch (e: Exception) {
            log.warn("创建消费者组时发生异常（可能已存在）: {}", e.message)
        }

        executorService = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, threadName())
            t.isDaemon = true
            t
        }

        running.set(true)
        executorService?.submit { consumeLoop() }
        log.info("{}消费者已启动: consumerName={}", taskDisplayName(), consumerName)
    }

    /**
     * 关闭消费者
     */
    @PreDestroy
    fun shutdown() {
        running.set(false)
        executorService?.shutdown()
        log.info("{}消费者已关闭: consumerName={}", taskDisplayName(), consumerName)
    }

    private fun consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName ?: "consumer",
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS
                ) { messageId, data -> processMessage(messageId, data) }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    log.info("消费者线程被中断")
                    break
                }
                log.error("消费消息时发生错误: {}", e.message, e)
            }
        }
    }

    private fun processMessage(messageId: StreamMessageId, data: Map<String, String>) {
        val payload = parsePayload(messageId, data) ?: run {
            ackMessage(messageId)
            return
        }

        val retryCount = parseRetryCount(data)
        log.info("开始处理{}任务: {}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount)

        try {
            markProcessing(payload)
            processBusiness(payload)
            markCompleted(payload)
            ackMessage(messageId)
            log.info("{}任务完成: {}", taskDisplayName(), payloadIdentifier(payload))
        } catch (e: Exception) {
            log.error("{}任务失败: {}, error={}", taskDisplayName(), payloadIdentifier(payload), e.message, e)
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1)
            } else {
                markFailed(payload, truncateError(
                    "${taskDisplayName()}失败(已重试${retryCount}次): ${e.message}"
                ))
            }
            ackMessage(messageId)
        }
    }

    /**
     * 解析重试次数
     */
    protected fun parseRetryCount(data: Map<String, String>): Int {
        return try {
            data[AsyncTaskStreamConstants.FIELD_RETRY_COUNT]?.toInt() ?: 0
        } catch (_: NumberFormatException) {
            0
        }
    }

    /**
     * 截断错误信息
     */
    protected fun truncateError(error: String?): String? {
        if (error == null) {
            return null
        }
        return if (error.length > 500) error.substring(0, 500) else error
    }

    private fun ackMessage(messageId: StreamMessageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId)
        } catch (e: Exception) {
            log.error("确认消息失败: messageId={}, error={}", messageId, e.message, e)
        }
    }

    protected fun redisService(): RedisService {
        return redisService
    }

    protected abstract fun taskDisplayName(): String // 任务展示名称

    protected abstract fun streamKey(): String // Stream Key

    protected abstract fun groupName(): String // 消费者组名

    protected abstract fun consumerPrefix(): String // 消费者名前缀

    protected abstract fun threadName(): String // 消费线程名

    protected abstract fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): T? // 解析载荷

    protected abstract fun payloadIdentifier(payload: T): String // 载荷标识

    protected abstract fun markProcessing(payload: T) // 标记处理中

    protected abstract fun processBusiness(payload: T) // 处理业务逻辑

    protected abstract fun markCompleted(payload: T) // 标记完成

    protected abstract fun markFailed(payload: T, error: String?) // 标记失败

    protected abstract fun retryMessage(payload: T, retryCount: Int) // 重试入队
}

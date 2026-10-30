package interview.guide.common.async

import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.infrastructure.redis.RedisService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redis Stream 消费者模板基类。
 * 统一消费循环、ACK、重试与生命周期管理。
 */
abstract class AbstractStreamConsumer<T>(
    private val redisService: RedisService // Redis 操作封装
) {

    companion object {
        private val log = LoggerFactory.getLogger(AbstractStreamConsumer::class.java)
    }

    private val running = AtomicBoolean(false) // 运行标识
    private var executorService: ExecutorService? = null // 消费线程池
    private var consumerName: String? = null // 消费者名称

    /**
     * 初始化消费者：创建消费者组并启动单线程消费循环
     */
    @PostConstruct
    fun init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8)
        this.executorService = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable ->
                val thread = Thread(runnable, threadName())
                thread.isDaemon = true
                thread
            },
            ThreadPoolExecutor.AbortPolicy()
        )

        running.set(true)
        executorService?.submit(Runnable { startConsumer() })
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName)
    }

    /**
     * 关闭消费者
     */
    @PreDestroy
    fun shutdown() {
        running.set(false)
        executorService?.shutdown()
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName)
    }

    /**
     * 启动消费：先准备消费者组，再进入消费循环
     */
    private fun startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName())
            log.info("Redis Stream group is ready: {}", groupName())
        } catch (e: Exception) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e)
        }

        consumeLoop()
    }

    /**
     * 消费循环：持续拉取消息，异常时根据中断状态决定是否退出
     */
    private fun consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName ?: "consumer",
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE
                ) { messageId, data -> processMessage(messageId, data) }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    log.info("Consumer thread interrupted")
                    break
                }
                log.error("Failed to consume message", e)
            }
        }
    }

    /**
     * 处理单条消息：解析 → 跳过/领取 → 业务 → 完成/重试
     *
     * @param messageId 消息ID // Stream 消息标识
     * @param data      消息内容 // 字段键值对
     */
    private fun processMessage(messageId: StreamMessageId, data: Map<String, String>) {
        val payload: T? = try {
            parsePayload(messageId, data)
        } catch (e: Exception) {
            val fields: Any? = data.keys
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e)
            ackMessage(messageId)
            return
        }

        if (payload == null) {
            ackMessage(messageId)
            return
        }

        val retryCount = parseRetryCount(data)
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount)

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId)
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload))
                return
            }
            if (!tryMarkProcessing(payload)) {
                ackMessage(messageId)
                log.info("{} task was not claimed: {}", taskDisplayName(), payloadIdentifier(payload))
                return
            }
            processBusiness(payload)
            markCompleted(payload)
            ackMessage(messageId)
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload))
        } catch (e: Exception) {
            log.error("{} task failed: {}, error={}", taskDisplayName(), payloadIdentifier(payload),
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e))
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1)
            } else {
                markFailed(
                    payload,
                    truncateError(taskDisplayName() + " failed after retry " + retryCount)
                )
            }
            ackMessage(messageId)
        }
    }

    /**
     * 解析重试次数
     *
     * @param data 消息内容 // 可能为 null
     * @return 重试次数 // 解析失败返回 0
     */
    protected fun parseRetryCount(data: Map<String, String>?): Int {
        if (data == null) {
            return 0
        }
        return try {
            data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0").toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }

    /**
     * 截断错误信息，避免超出数据库字段长度
     *
     * @param error 原始错误 // 可能为 null
     * @return 截断后的错误 // 最大 500 字符
     */
    protected fun truncateError(error: String?): String? {
        if (error == null) {
            return null
        }
        return if (error.length > 500) error.substring(0, 500) else error
    }

    /**
     * 确认消息已处理
     *
     * @param messageId 消息ID // Stream 消息标识
     */
    private fun ackMessage(messageId: StreamMessageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId)
        } catch (e: Exception) {
            log.error("Failed to ack stream message: messageId={}", messageId, e)
        }
    }

    /**
     * 获取 Redis 服务（供子类重试入队使用）
     */
    protected fun redisService(): RedisService {
        return redisService
    }

    protected abstract fun taskDisplayName(): String // 任务展示名称

    protected abstract fun streamKey(): String // Stream Key

    protected abstract fun groupName(): String // 消费者组名

    protected abstract fun consumerPrefix(): String // 消费者名前缀

    protected abstract fun threadName(): String // 消费线程名

    /**
     * 解析消息载荷
     *
     * @param messageId 消息ID // Stream 消息标识
     * @param data      消息内容 // 字段键值对
     * @return 业务载荷 // null 表示消息无效需直接 ACK
     */
    protected abstract fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): T?

    protected abstract fun payloadIdentifier(payload: T): String // 载荷标识

    /**
     * 是否跳过任务（默认不跳过）
     *
     * @param payload 业务载荷 // 任务数据
     * @return 是否跳过 // true 表示直接 ACK 丢弃
     */
    protected open fun shouldSkip(payload: T): Boolean {
        return false
    }

    protected abstract fun markProcessing(payload: T) // 标记处理中

    /**
     * 尝试领取任务。默认保持原有消费者的状态更新语义。
     *
     * @param payload 业务载荷 // 任务数据
     * @return 是否领取成功 // false 表示任务已被其他消费者处理
     */
    protected open fun tryMarkProcessing(payload: T): Boolean {
        markProcessing(payload)
        return true
    }

    protected abstract fun processBusiness(payload: T) // 处理业务逻辑

    protected abstract fun markCompleted(payload: T) // 标记完成

    protected abstract fun markFailed(payload: T, error: String?) // 标记失败

    protected abstract fun retryMessage(payload: T, retryCount: Int) // 重试入队
}

package interview.guide.common.async

import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.infrastructure.redis.RedisService
import org.slf4j.LoggerFactory

/**
 * Redis Stream 生产者模板基类
 * 统一消息发送骨架与失败处理逻辑
 */
abstract class AbstractStreamProducer<T>(
    private val redisService: RedisService // Redis 操作封装
) {

    private val log = LoggerFactory.getLogger(AbstractStreamProducer::class.java)

    /**
     * 发送任务消息
     *
     * @param payload 业务载荷 // 任务数据
     */
    protected fun sendTask(payload: T) {
        try {
            val messageId = redisService.streamAdd(
                streamKey(),
                buildMessage(payload),
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId)
        } catch (e: Exception) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.message, e)
            onSendFailed(payload, "任务入队失败: ${e.message}")
        }
    }

    /**
     * 截断错误信息
     *
     * @param error 原始错误 // 原始错误文本
     * @return 截断后的错误 // 最大500字符
     */
    protected fun truncateError(error: String?): String? {
        if (error == null) {
            return null
        }
        return if (error.length > 500) error.substring(0, 500) else error
    }

    protected abstract fun taskDisplayName(): String // 任务展示名称

    protected abstract fun streamKey(): String // Stream Key

    protected abstract fun buildMessage(payload: T): Map<String, String> // 消息体构建

    protected abstract fun payloadIdentifier(payload: T): String // 载荷标识

    protected abstract fun onSendFailed(payload: T, error: String) // 发送失败处理
}

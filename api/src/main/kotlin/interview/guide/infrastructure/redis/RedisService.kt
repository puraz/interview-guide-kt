package interview.guide.infrastructure.redis

import org.redisson.api.*
import org.redisson.api.options.KeysScanOptions
import org.redisson.api.stream.StreamAddArgs
import org.redisson.api.stream.StreamCreateGroupArgs
import org.redisson.api.stream.StreamMessageId
import org.redisson.api.stream.StreamReadGroupArgs
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Redis 服务封装
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等
 */
@Service
class RedisService(
    private val redissonClient: RedissonClient // Redisson 客户端
) {

    private val log = LoggerFactory.getLogger(RedisService::class.java)

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     *
     * @param key 键 // Redis 键
     * @param value 值 // 存储对象
     */
    fun <T> set(key: String, value: T) {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        bucket.set(value)
    }

    /**
     * 设置值（带过期时间）
     *
     * @param key 键 // Redis 键
     * @param value 值 // 存储对象
     * @param ttl 过期时间 // TTL
     */
    fun <T> set(key: String, value: T, ttl: Duration) {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        bucket.set(value, ttl)
    }

    /**
     * 获取值
     *
     * @param key 键 // Redis 键
     * @return 值 // 可能为 null
     */
    fun <T> get(key: String): T? {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        return bucket.get()
    }

    /**
     * 获取值，如果不存在则使用 loader 加载并缓存
     *
     * @param key 键 // Redis 键
     * @param ttl 过期时间 // TTL
     * @param loader 加载函数 // 数据加载逻辑
     * @return 值 // 已缓存的值
     */
    fun <T> getOrLoad(key: String, ttl: Duration, loader: (String) -> T?): T? {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        var value = bucket.get()
        if (value == null) {
            value = loader.invoke(key)
            if (value != null) {
                bucket.set(value, ttl)
            }
        }
        return value
    }

    /**
     * 删除键
     */
    fun delete(key: String): Boolean {
        return redissonClient.getBucket<Any>(key).delete()
    }

    /**
     * 检查键是否存在
     */
    fun exists(key: String): Boolean {
        return redissonClient.getBucket<Any>(key).isExists
    }

    /**
     * 设置过期时间
     */
    fun expire(key: String, ttl: Duration): Boolean {
        return redissonClient.getBucket<Any>(key).expire(ttl)
    }

    /**
     * 获取剩余过期时间（毫秒）
     */
    fun getTimeToLive(key: String): Long {
        return redissonClient.getBucket<Any>(key).remainTimeToLive()
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段
     */
    fun <K, V> hSet(key: String, field: K, value: V) {
        val map: RMap<K, V> = redissonClient.getMap(key)
        map.put(field, value)
    }

    /**
     * 获取 Hash 字段
     */
    fun <K, V> hGet(key: String, field: K): V? {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.get(field)
    }

    /**
     * 获取整个 Hash
     */
    fun <K, V> hGetAll(key: String): Map<K, V> {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.readAllMap()
    }

    /**
     * 删除 Hash 字段
     */
    fun <K, V> hDelete(key: String, field: K): Boolean {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.remove(field) != null
    }

    /**
     * 检查 Hash 字段是否存在
     */
    fun <K> hExists(key: String, field: K): Boolean {
        val map: RMap<K, Any> = redissonClient.getMap(key)
        return map.containsKey(field)
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取锁（阻塞等待）
     */
    fun getLock(lockKey: String): RLock {
        return redissonClient.getLock(lockKey)
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    fun tryLock(lockKey: String, waitTime: Long, leaseTime: Long, unit: TimeUnit): Boolean {
        val lock = redissonClient.getLock(lockKey)
        return try {
            lock.tryLock(waitTime, leaseTime, unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * 释放锁
     */
    fun unlock(lockKey: String) {
        val lock = redissonClient.getLock(lockKey)
        if (lock.isHeldByCurrentThread) {
            lock.unlock()
        }
    }

    /**
     * 执行带锁的操作
     */
    fun <T> executeWithLock(
        lockKey: String,
        waitTime: Long,
        leaseTime: Long,
        unit: TimeUnit,
        operation: () -> T
    ): T {
        val lock = redissonClient.getLock(lockKey)
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                return try {
                    operation.invoke()
                } finally {
                    lock.unlock()
                }
            }
            throw RuntimeException("获取锁失败: $lockKey")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("获取锁被中断: $lockKey", e)
        }
    }

    // ==================== Stream 消息队列 ====================

    /**
     * 消费 Stream 消息（阻塞模式）
     */
    fun streamConsumeMessages(
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int,
        blockTimeoutMs: Long,
        processor: (StreamMessageId, Map<String, String>) -> Unit
    ): Boolean {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        val messages = stream.readGroup(
            groupName,
            consumerName,
            StreamReadGroupArgs.neverDelivered()
                .count(count)
                .timeout(Duration.ofMillis(blockTimeoutMs))
        )

        if (messages == null || messages.isEmpty()) {
            return false
        }

        for ((id, data) in messages) {
            processor.invoke(id, data)
        }

        return true
    }

    /**
     * 创建消费者组（如果不存在）
     */
    fun createStreamGroup(streamKey: String, groupName: String) {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream())
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName)
        } catch (e: Exception) {
            if (e.message?.contains("BUSYGROUP") != true) {
                log.warn("创建消费者组失败: {}", e.message)
            }
        }
    }

    /**
     * 发送消息到 Stream
     */
    fun streamAdd(streamKey: String, message: Map<String, String>): String {
        return streamAdd(streamKey, message, 0)
    }

    /**
     * 发送消息到 Stream（带长度限制）
     */
    fun streamAdd(streamKey: String, message: Map<String, String>, maxLen: Int): String {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        val args = StreamAddArgs.entries(message)
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen)
        }
        val messageId = stream.add(args)
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen)
        return messageId.toString()
    }

    /**
     * 从 Stream 读取消息（消费者组模式）
     */
    fun streamReadGroup(streamKey: String, groupName: String, consumerName: String, count: Int): Map<StreamMessageId, Map<String, String>> {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        return stream.readGroup(groupName, consumerName, StreamReadGroupArgs.neverDelivered().count(count))
    }

    /**
     * 确认消息已处理
     */
    fun streamAck(streamKey: String, groupName: String, vararg ids: StreamMessageId) {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        stream.ack(groupName, *ids)
    }

    /**
     * 获取 Stream 长度
     */
    fun streamLen(streamKey: String): Long {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        return stream.size()
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     */
    fun getAtomicLong(key: String): RAtomicLong {
        return redissonClient.getAtomicLong(key)
    }

    /**
     * 自增并返回
     */
    fun increment(key: String): Long {
        return redissonClient.getAtomicLong(key).incrementAndGet()
    }

    /**
     * 自减并返回
     */
    fun decrement(key: String): Long {
        return redissonClient.getAtomicLong(key).decrementAndGet()
    }

    // ==================== 列表操作 ====================

    /**
     * 从列表右侧添加元素
     */
    fun <T> listRightPush(key: String, value: T) {
        val list: RList<T> = redissonClient.getList(key)
        list.add(value)
    }

    /**
     * 获取列表所有元素
     */
    fun <T> listGetAll(key: String): List<T> {
        val list: RList<T> = redissonClient.getList(key)
        return list.readAll()
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     */
    fun getClient(): RedissonClient {
        return redissonClient
    }

    /**
     * 按模式删除键
     */
    fun deleteByPattern(pattern: String): Long {
        val keys: RKeys = redissonClient.keys
        return keys.deleteByPattern(pattern)
    }

    /**
     * 按模式查找键
     */
    fun findKeysByPattern(pattern: String): Iterable<String> {
        val keys: RKeys = redissonClient.keys
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern))
    }
}

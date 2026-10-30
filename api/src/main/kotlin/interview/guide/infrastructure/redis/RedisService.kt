package interview.guide.infrastructure.redis

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.redisson.api.RAtomicLong
import org.redisson.api.RBucket
import org.redisson.api.RKeys
import org.redisson.api.RList
import org.redisson.api.RLock
import org.redisson.api.RMap
import org.redisson.api.RStream
import org.redisson.api.RedissonClient
import org.redisson.api.options.KeysScanOptions
import org.redisson.api.stream.AutoClaimResult
import org.redisson.api.stream.StreamAddArgs
import org.redisson.api.stream.StreamCreateGroupArgs
import org.redisson.api.stream.StreamMessageId
import org.redisson.api.stream.StreamReadGroupArgs
import org.redisson.client.RedisException
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

/**
 * Redis 服务封装
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等
 *
 * Redisson 版本兼容说明：
 * - 当前适配 Redisson 4.0.0。
 * - `stream.readGroup` / `stream.autoClaim` 在空结果时可能抛出 [ClassCastException]
 *   （Redisson 内部返回 EmptyList 而非空 Map），两处均已 catch 并做空结果处理。
 * - 升级 Redisson 后如不再抛此异常，可清理对应的 catch 块。
 */
@Service
class RedisService(
    private val redissonClient: RedissonClient // Redisson 客户端
) {

    companion object {
        private val log = LoggerFactory.getLogger(RedisService::class.java)
    }

    /**
     * 各 Stream（stream:group）的 Pending 回收游标，避免每轮都从 MIN 重新扫描
     */
    private val streamReclaimCursors: ConcurrentMap<String, StreamMessageId> = ConcurrentHashMap()

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     *
     * @param key   键 // Redis 键
     * @param value 值 // 存储对象
     */
    fun <T> set(key: String, value: T) {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        bucket.set(value)
    }

    /**
     * 设置值（带过期时间）
     *
     * @param key   键 // Redis 键
     * @param value 值 // 存储对象
     * @param ttl   过期时间 // TTL
     */
    fun <T> set(key: String, value: T, ttl: Duration) {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        bucket.set(value, ttl)
    }

    /**
     * 获取值
     *
     * @param key 键 // Redis 键
     * @return 值 // 不存在时为 null
     */
    fun <T> get(key: String): T? {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        return bucket.get()
    }

    /**
     * 获取值，如果不存在则使用 loader 加载并缓存
     *
     * @param key    键 // Redis 键
     * @param ttl    过期时间 // TTL
     * @param loader 加载函数 // 缓存未命中时的加载逻辑
     * @return 值 // 已缓存或新加载的值
     */
    fun <T> getOrLoad(key: String, ttl: Duration, loader: (String) -> T?): T? {
        val bucket: RBucket<T> = redissonClient.getBucket(key)
        var value = bucket.get()
        if (value == null) {
            value = loader(key)
            if (value != null) {
                bucket.set(value, ttl)
            }
        }
        return value
    }

    /**
     * 删除键
     *
     * @param key 键 // Redis 键
     * @return 是否删除成功 // 键不存在时返回 false
     */
    fun delete(key: String): Boolean {
        return redissonClient.getBucket<Any>(key).delete()
    }

    /**
     * 检查键是否存在
     *
     * @param key 键 // Redis 键
     * @return 是否存在 // 存在为 true
     */
    fun exists(key: String): Boolean {
        return redissonClient.getBucket<Any>(key).isExists
    }

    /**
     * 设置过期时间
     *
     * @param key 键 // Redis 键
     * @param ttl 过期时间 // TTL
     * @return 是否设置成功 // 键不存在时返回 false
     */
    fun expire(key: String, ttl: Duration): Boolean {
        return redissonClient.getBucket<Any>(key).expire(ttl)
    }

    /**
     * 获取剩余过期时间（毫秒）
     *
     * @param key 键 // Redis 键
     * @return 剩余毫秒数 // -1 无过期时间，-2 键不存在
     */
    fun getTimeToLive(key: String): Long {
        return redissonClient.getBucket<Any>(key).remainTimeToLive()
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段
     *
     * @param key   键 // Redis 键
     * @param field 字段 // Hash 字段
     * @param value 值 // 字段值
     */
    fun <K, V> hSet(key: String, field: K, value: V) {
        val map: RMap<K, V> = redissonClient.getMap(key)
        map.put(field, value)
    }

    /**
     * 获取 Hash 字段
     *
     * @param key   键 // Redis 键
     * @param field 字段 // Hash 字段
     * @return 字段值 // 不存在时为 null
     */
    fun <K, V> hGet(key: String, field: K): V? {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.get(field)
    }

    /**
     * 获取整个 Hash
     *
     * @param key 键 // Redis 键
     * @return 全部字段 // 键值映射
     */
    fun <K, V> hGetAll(key: String): Map<K, V> {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.readAllMap()
    }

    /**
     * 删除 Hash 字段
     *
     * @param key   键 // Redis 键
     * @param field 字段 // Hash 字段
     * @return 是否删除成功 // 字段不存在时返回 false
     */
    fun <K, V> hDelete(key: String, field: K): Boolean {
        val map: RMap<K, V> = redissonClient.getMap(key)
        return map.remove(field) != null
    }

    /**
     * 检查 Hash 字段是否存在
     *
     * @param key   键 // Redis 键
     * @param field 字段 // Hash 字段
     * @return 是否存在 // 存在为 true
     */
    fun <K> hExists(key: String, field: K): Boolean {
        val map: RMap<K, Any> = redissonClient.getMap(key)
        return map.containsKey(field)
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取锁（阻塞等待）
     *
     * @param lockKey 锁键 // Redis 锁键
     * @return 锁对象 // Redisson 分布式锁
     */
    fun getLock(lockKey: String): RLock {
        return redissonClient.getLock(lockKey)
    }

    /**
     * 尝试获取锁（非阻塞）
     *
     * @param lockKey   锁键 // Redis 锁键
     * @param waitTime  等待时间 // 获取锁最长等待
     * @param leaseTime 持有时间 // 锁租期
     * @param unit      时间单位 // 时间单位
     * @return 是否获取成功 // 中断时返回 false
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
     *
     * @param lockKey 锁键 // Redis 锁键
     */
    fun unlock(lockKey: String) {
        val lock = redissonClient.getLock(lockKey)
        if (lock.isHeldByCurrentThread) {
            lock.unlock()
        }
    }

    /**
     * 执行带锁的操作
     *
     * @param lockKey   锁键 // Redis 锁键
     * @param waitTime  等待时间 // 获取锁最长等待
     * @param leaseTime 持有时间 // 锁租期
     * @param unit      时间单位 // 时间单位
     * @param operation 业务操作 // 持锁执行的动作
     * @return 操作结果 // 业务返回值
     */
    fun <T> executeWithLock(
        lockKey: String,
        waitTime: Long,
        leaseTime: Long,
        unit: TimeUnit,
        operation: LockedOperation<T>
    ): T {
        val lock = redissonClient.getLock(lockKey)
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                try {
                    return operation.execute()
                } finally {
                    lock.unlock()
                }
            }
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: $lockKey")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: $lockKey", e)
        }
    }

    /**
     * 持锁操作接口
     */
    fun interface LockedOperation<T> {
        /**
         * 执行持锁业务
         *
         * @return 业务结果 // 执行结果
         */
        fun execute(): T
    }

    // ==================== Stream 消息队列 ====================

    /**
     * Stream 消息处理器接口
     */
    fun interface StreamMessageProcessor {
        /**
         * 处理单条消息
         *
         * @param messageId 消息ID // Stream 消息标识
         * @param data      消息内容 // 字段键值对
         */
        fun process(messageId: StreamMessageId, data: Map<String, String>)
    }

    /**
     * 消费 Stream 消息（阻塞模式）
     * 使用 Redis BLOCK 参数，让服务端等待消息，比客户端轮询更高效
     *
     * @param streamKey      Stream 键
     * @param groupName      消费者组名
     * @param consumerName   消费者名
     * @param count          每次读取数量
     * @param blockTimeoutMs 阻塞等待超时时间（毫秒），0 表示无限等待
     * @param processor      消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    fun streamConsumeMessages(
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int,
        blockTimeoutMs: Long,
        processor: StreamMessageProcessor
    ): Boolean {
        return streamConsumeMessages(
            streamKey,
            groupName,
            consumerName,
            count,
            blockTimeoutMs,
            0L,
            processor
        )
    }

    /**
     * 消费 Stream 消息（优先回收超时 Pending，再阻塞读取新消息）
     *
     * @param streamKey            Stream 键
     * @param groupName            消费者组名
     * @param consumerName         消费者名
     * @param count                每次读取数量
     * @param blockTimeoutMs       阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs Pending 消息超过该 idle 时间后可被当前消费者回收
     * @param processor            消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    fun streamConsumeMessages(
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int,
        blockTimeoutMs: Long,
        pendingIdleTimeoutMs: Long,
        processor: StreamMessageProcessor
    ): Boolean {
        return streamConsumeMessages(
            streamKey,
            groupName,
            consumerName,
            count,
            blockTimeoutMs,
            pendingIdleTimeoutMs,
            count,
            processor
        )
    }

    /**
     * 消费 Stream 消息（优先回收超时 Pending，再阻塞读取新消息）
     *
     * @param streamKey             Stream 键
     * @param groupName             消费者组名
     * @param consumerName          消费者名
     * @param count                 每次读取新消息数量
     * @param blockTimeoutMs        阻塞等待超时时间（毫秒），0 表示无限等待
     * @param pendingIdleTimeoutMs  Pending 消息超过该 idle 时间后可被当前消费者回收
     * @param pendingClaimBatchSize 每轮最多回收的 Pending 消息数
     * @param processor             消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    fun streamConsumeMessages(
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int,
        blockTimeoutMs: Long,
        pendingIdleTimeoutMs: Long,
        pendingClaimBatchSize: Int,
        processor: StreamMessageProcessor
    ): Boolean {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        var messages = reclaimPendingMessages(
            stream,
            streamKey,
            groupName,
            consumerName,
            pendingClaimBatchSize,
            pendingIdleTimeoutMs
        )
        if (processMessages(messages, processor)) {
            return true
        }

        // 使用阻塞读取，让 Redis 服务端等待消息
        try {
            messages = stream.readGroup(
                groupName,
                consumerName,
                StreamReadGroupArgs.neverDelivered()
                    .count(count)
                    .timeout(Duration.ofMillis(blockTimeoutMs))
            )
        } catch (_: ClassCastException) {
            // Redisson 4.0.0 bug: 无消息时返回 EmptyList 而非空 Map，内部强转失败。
            // 等价于"本次无消息"，静默返回即可。
            log.debug("Redisson 4.0.0 内部类型转换异常（空结果时触发），等价于本批无消息: stream={}, group={}",
                streamKey, groupName)
            return false
        }

        if (messages == null || messages.isEmpty()) {
            return false
        }

        return processMessages(messages, processor)
    }

    /**
     * 回收超时未 ACK 的 Pending 消息（使用游标按批扫描，避免每轮重扫全量）
     *
     * @param stream               Stream 对象 // Redisson Stream
     * @param streamKey            Stream 键 // 日志与游标键
     * @param groupName            消费者组名 // 消费组
     * @param consumerName         消费者名 // 当前消费者
     * @param count                每轮最多回收数量 // 批量大小
     * @param pendingIdleTimeoutMs Pending 消息空闲阈值（毫秒） // 超过可回收
     * @return 回收到的消息 // 无消息时为空 Map
     */
    private fun reclaimPendingMessages(
        stream: RStream<String, String>,
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int,
        pendingIdleTimeoutMs: Long
    ): Map<StreamMessageId, Map<String, String>> {
        if (pendingIdleTimeoutMs <= 0 || count <= 0) {
            return emptyMap()
        }

        val cursorKey = "$streamKey:$groupName"
        val startId = streamReclaimCursors.getOrDefault(cursorKey, StreamMessageId.MIN)
        val result: AutoClaimResult<String, String>
        try {
            result = stream.autoClaim(
                groupName,
                consumerName,
                pendingIdleTimeoutMs,
                TimeUnit.MILLISECONDS,
                startId,
                count
            )
        } catch (_: ClassCastException) {
            // Redisson 4.0.0 空结果可能触发内部类型转换异常，等价于本轮无可回收消息。
            log.debug("Redisson 4.0.0 内部类型转换异常（无可回收消息）: stream={}, group={}",
                streamKey, groupName)
            return emptyMap()
        }

        val nextId = result.nextId
        if (nextId == null || StreamMessageId.MIN == nextId) {
            streamReclaimCursors.remove(cursorKey)
        } else {
            streamReclaimCursors[cursorKey] = nextId
        }

        val deletedIds = result.deletedIds
        if (deletedIds != null && deletedIds.isNotEmpty()) {
            log.warn("Stream pending messages were trimmed before reclaim: stream={}, group={}, ids={}",
                streamKey, groupName, deletedIds)
        }

        val messages = result.messages
        if (messages != null && messages.isNotEmpty()) {
            log.info("Reclaimed Redis Stream pending messages: stream={}, group={}, consumer={}, count={}",
                streamKey, groupName, consumerName, messages.size)
        }
        return messages ?: emptyMap()
    }

    /**
     * 逐条执行消息处理器
     *
     * @param messages  消息集合 // 待处理消息
     * @param processor 消息处理器 // 业务处理逻辑
     * @return 是否处理了消息 // 空集合返回 false
     */
    private fun processMessages(
        messages: Map<StreamMessageId, Map<String, String>>?,
        processor: StreamMessageProcessor
    ): Boolean {
        if (messages == null || messages.isEmpty()) {
            return false
        }
        for ((messageId, data) in messages) {
            processor.process(messageId, data)
        }

        return true
    }

    /**
     * 创建消费者组（如果不存在）
     *
     * @param streamKey Stream 键 // 消息队列
     * @param groupName 消费者组名 // 消费组
     */
    fun createStreamGroup(streamKey: String, groupName: String) {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream())
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName)
        } catch (e: Exception) {
            val errorMessage = e.message
            // 组已存在，忽略
            if (e is RedisException && errorMessage != null && errorMessage.contains("BUSYGROUP")) {
                return
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}", streamKey, groupName, errorMessage)
        }
    }

    /**
     * 发送消息到 Stream
     *
     * @param streamKey Stream 键 // 消息队列
     * @param message   消息内容 // 字段键值对
     * @return 消息ID // Redis 生成的标识
     */
    fun streamAdd(streamKey: String, message: Map<String, String>): String {
        return streamAdd(streamKey, message, 0)
    }

    /**
     * 发送消息到 Stream（带长度限制）
     *
     * @param streamKey Stream 键
     * @param message   消息内容
     * @param maxLen    最大长度，超过时自动裁剪旧消息，0 表示不限制
     * @return 消息ID
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
     *
     * @param streamKey    Stream 键 // 消息队列
     * @param groupName    消费者组名 // 消费组
     * @param consumerName 消费者名 // 当前消费者
     * @param count        读取数量 // 批量大小
     * @return 消息集合 // 消息ID到字段的映射
     */
    fun streamReadGroup(
        streamKey: String,
        groupName: String,
        consumerName: String,
        count: Int
    ): Map<StreamMessageId, Map<String, String>> {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        return stream.readGroup(
            groupName,
            consumerName,
            StreamReadGroupArgs.neverDelivered().count(count)
        )
    }

    /**
     * 确认消息已处理
     *
     * @param streamKey Stream 键 // 消息队列
     * @param groupName 消费者组名 // 消费组
     * @param ids       消息ID // 待确认的消息标识
     */
    fun streamAck(streamKey: String, groupName: String, vararg ids: StreamMessageId) {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        stream.ack(groupName, *ids)
    }

    /**
     * 获取 Stream 长度
     *
     * @param streamKey Stream 键 // 消息队列
     * @return 消息数量 // Stream 长度
     */
    fun streamLen(streamKey: String): Long {
        val stream: RStream<String, String> = redissonClient.getStream(streamKey, StringCodec.INSTANCE)
        return stream.size()
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     *
     * @param key 键 // 计数器键
     * @return 原子计数器 // Redisson 计数器
     */
    fun getAtomicLong(key: String): RAtomicLong {
        return redissonClient.getAtomicLong(key)
    }

    /**
     * 自增并返回
     *
     * @param key 键 // 计数器键
     * @return 自增后的值 // 最新计数
     */
    fun increment(key: String): Long {
        return redissonClient.getAtomicLong(key).incrementAndGet()
    }

    /**
     * 自减并返回
     *
     * @param key 键 // 计数器键
     * @return 自减后的值 // 最新计数
     */
    fun decrement(key: String): Long {
        return redissonClient.getAtomicLong(key).decrementAndGet()
    }

    // ==================== 列表操作 ====================

    /**
     * 从列表右侧添加元素
     *
     * @param key   键 // 列表键
     * @param value 值 // 列表元素
     */
    fun <T> listRightPush(key: String, value: T) {
        val list: RList<T> = redissonClient.getList(key)
        list.add(value)
    }

    /**
     * 获取列表所有元素
     *
     * @param key 键 // 列表键
     * @return 列表元素 // 全部元素
     */
    fun <T> listGetAll(key: String): List<T> {
        val list: RList<T> = redissonClient.getList(key)
        return list.readAll()
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     *
     * @return Redisson 客户端 // 原生客户端
     */
    fun getClient(): RedissonClient {
        return redissonClient
    }

    /**
     * 按模式删除键
     *
     * @param pattern 匹配模式 // 支持通配符
     * @return 删除的键数量 // 实际删除个数
     */
    fun deleteByPattern(pattern: String): Long {
        val keys: RKeys = redissonClient.keys
        return keys.deleteByPattern(pattern)
    }

    /**
     * 按模式查找键
     *
     * @param pattern 匹配模式 // 支持通配符
     * @return 匹配的键集合 // 迭代器
     */
    fun findKeysByPattern(pattern: String): Iterable<String> {
        val keys: RKeys = redissonClient.keys
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern))
    }
}

package com.example.framework.ai.deepseek

import com.example.framework.core.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class DeepSeekApiKeyPool(
    private val properties: DeepSeekProperties
) {

    private val logger = LoggerFactory.getLogger(DeepSeekApiKeyPool::class.java)

    @Volatile
    private var keys: List<String> = emptyList()
    private val cursor = AtomicInteger(0)
    private val initialized = AtomicInteger(0)

    private fun ensureInitialized() {
        if (initialized.compareAndSet(0, 1)) {
            refresh(properties.apiKeys)
        }
    }

    fun acquire(): String {
        ensureInitialized()
        val snapshot = keys
        if (snapshot.isEmpty()) {
            throw BusinessException("DeepSeek ApiKey 未配置")
        }
        val index = Math.floorMod(cursor.getAndIncrement(), snapshot.size)
        return snapshot[index]
    }

    fun refresh(newKeys: Collection<String?>) {
        val resolved = resolve(newKeys)
        keys = resolved
        cursor.set(0)
        logger.info("DeepSeek ApiKey 池初始化完成，当前可用数量: {}", keys.size)
    }

    fun register(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            return
        }
        val current = keys
        if (!current.contains(trimmed)) {
            keys = current + trimmed
        }
    }

    fun current(): List<String> {
        ensureInitialized()
        return keys
    }

    private fun resolve(raw: Collection<String?>): List<String> {
        val envKey = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
        val normalized = raw.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        val dropped = raw.size - normalized.size
        if (dropped > 0) {
            logger.warn("DeepSeek ApiKey 配置存在 {} 个空值或 null，已忽略", dropped)
        }
        val candidates = buildList {
            addAll(normalized)
            if (envKey.isNotBlank()) {
                add(envKey)
            }
        }
        if (candidates.isEmpty()) {
            logger.warn("DeepSeek ApiKey 列表为空，后续调用将失败")
        }
        return candidates.distinct()
    }
}

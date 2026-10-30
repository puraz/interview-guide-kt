package interview.guide.modules.llmprovider.service

import interview.guide.common.config.LlmProviderProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provider API Key 加解密服务（AES/GCM/NoPadding）
 */
@Service
class ApiKeyEncryptionService(
    private val properties: LlmProviderProperties // 加密相关配置
) {

    private val log = LoggerFactory.getLogger(ApiKeyEncryptionService::class.java)

    private val secureRandom = SecureRandom() // 安全随机数生成器（生成 nonce）

    private lateinit var secretKey: SecretKeySpec // AES 密钥

    companion object {
        private const val NONCE_BYTES = 12 // GCM nonce 字节数
        private const val GCM_TAG_BITS = 128 // GCM 认证标签位数
        private const val CIPHER = "AES/GCM/NoPadding" // 加密算法
        private const val DEV_FALLBACK_KEY = // 开发用 fallback 密钥（需显式开启）
            "interview-guide-dev-only-provider-api-key-encryption"
    }

    /**
     * 初始化加密密钥，未配置且未显式允许 fallback 时启动失败
     */
    @PostConstruct
    fun init() {
        val configuredKey = resolveConfiguredKey(properties.security)
        secretKey = SecretKeySpec(resolveKeyBytes(configuredKey), "AES")
    }

    /**
     * 解析配置的加密主密钥
     *
     * @param security 安全配置 // 可为 null
     * @return 生效的密钥字符串 // 未配置且允许时返回开发 fallback
     */
    private fun resolveConfiguredKey(security: LlmProviderProperties.SecurityConfig?): String {
        val configuredKey = security?.apiKeyEncryptionKey
        if (configuredKey != null && configuredKey.isNotBlank()) {
            return configuredKey
        }

        val requireEncryptionKey = security == null || security.requireEncryptionKey
        if (requireEncryptionKey) {
            throw BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，无法初始化 Provider API Key 加密"
            )
        }

        val allowFallback = security != null && security.allowFallbackEncryptionKey
        if (!allowFallback) {
            throw BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，且未显式允许 Provider API Key 开发 fallback"
            )
        }

        log.warn("APP_AI_CONFIG_ENCRYPTION_KEY is not configured; using explicitly enabled fallback key")
        return DEV_FALLBACK_KEY
    }

    /**
     * 加密明文 API Key
     *
     * @param plainText 明文 Key
     * @return 加密结果 // nonce 与密文均为 Base64
     */
    fun encrypt(plainText: String): EncryptedValue {
        try {
            val nonce = ByteArray(NONCE_BYTES)
            secureRandom.nextBytes(nonce)

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

            return EncryptedValue(
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ciphertext)
            )
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "加密 Provider API Key 失败", e)
        }
    }

    /**
     * 解密 API Key
     *
     * @param nonceBase64 nonce（Base64）
     * @param ciphertextBase64 密文（Base64）
     * @return 明文 Key
     */
    fun decrypt(nonceBase64: String, ciphertextBase64: String): String {
        try {
            val nonce = Base64.getDecoder().decode(nonceBase64)
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plainText = cipher.doFinal(ciphertext)
            return String(plainText, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "解密 Provider API Key 失败，请检查 APP_AI_CONFIG_ENCRYPTION_KEY",
                e
            )
        }
    }

    /**
     * 解析密钥字节：32 字节 Base64 直接使用，否则对字符串做 SHA-256 派生
     *
     * @param configuredKey 配置的密钥
     * @return AES 密钥字节
     */
    private fun resolveKeyBytes(configuredKey: String): ByteArray {
        val trimmed = configuredKey.trim()
        try {
            val decoded = Base64.getDecoder().decode(trimmed)
            if (decoded.size == 32) {
                return decoded
            }
        } catch (ignored: IllegalArgumentException) {
            // 非 Base64 的可读密钥走 SHA-256 派生
        }
        return sha256(trimmed)
    }

    /**
     * 计算 SHA-256 摘要
     *
     * @param value 原始字符串
     * @return 32 字节摘要
     */
    private fun sha256(value: String): ByteArray {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "初始化 Provider API Key 加密密钥失败", e)
        }
    }

    /**
     * 加密结果
     *
     * @property nonce Base64 编码的 nonce
     * @property ciphertext Base64 编码的密文
     */
    data class EncryptedValue(
        val nonce: String,
        val ciphertext: String
    )
}

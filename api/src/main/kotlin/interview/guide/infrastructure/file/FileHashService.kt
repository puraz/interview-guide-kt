package interview.guide.infrastructure.file

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * 文件哈希服务
 * 统一提供文件哈希计算功能，用于文件去重
 */
@Service
class FileHashService {

    private val log = LoggerFactory.getLogger(FileHashService::class.java)

    companion object {
        private const val HASH_ALGORITHM = "SHA-256" // 哈希算法
        private const val BUFFER_SIZE = 8192 // 缓冲区大小
    }

    /**
     * 计算文件的 SHA-256 哈希值
     *
     * @param file MultipartFile 文件 // 上传文件
     * @return 十六进制哈希字符串 // 文件哈希
     */
    fun calculateHash(file: MultipartFile): String {
        return try {
            calculateHash(file.bytes)
        } catch (e: Exception) {
            log.error("读取文件内容失败: {}", e.message)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败")
        }
    }

    /**
     * 计算字节数组的 SHA-256 哈希值
     */
    fun calculateHash(data: ByteArray): String {
        try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            val hashBytes = digest.digest(data)
            return bytesToHex(hashBytes)
        } catch (_: NoSuchAlgorithmException) {
            log.error("哈希算法不支持: {}", HASH_ALGORITHM)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败")
        }
    }

    /**
     * 流式计算文件的 SHA-256 哈希值（适用于大文件）
     */
    fun calculateHash(inputStream: InputStream): String {
        try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            return bytesToHex(digest.digest())
        } catch (_: NoSuchAlgorithmException) {
            log.error("哈希算法不支持: {}", HASH_ALGORITHM)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败")
        } catch (e: Exception) {
            log.error("计算文件哈希失败: {}", e.message)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败")
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            result.append(String.format("%02x", b))
        }
        return result.toString()
    }
}

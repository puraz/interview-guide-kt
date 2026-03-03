package interview.guide.infrastructure.file

import interview.guide.common.config.StorageConfigProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 文件存储服务
 */
@Service
class FileStorageService(
    private val s3Client: S3Client, // S3 客户端
    private val storageConfig: StorageConfigProperties // 存储配置
) {

    private val log = LoggerFactory.getLogger(FileStorageService::class.java)

    /**
     * 上传简历文件
     */
    fun uploadResume(file: MultipartFile): String {
        return uploadFile(file, "resumes")
    }

    /**
     * 删除简历文件
     */
    fun deleteResume(fileKey: String?) {
        deleteFile(fileKey)
    }

    /**
     * 上传知识库文件
     */
    fun uploadKnowledgeBase(file: MultipartFile): String {
        return uploadFile(file, "knowledgebases")
    }

    /**
     * 删除知识库文件
     */
    fun deleteKnowledgeBase(fileKey: String?) {
        deleteFile(fileKey)
    }

    /**
     * 下载文件（通用方法）
     */
    fun downloadFile(fileKey: String?): ByteArray {
        if (fileKey.isNullOrBlank()) {
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在")
        }
        if (!fileExists(fileKey)) {
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: $fileKey")
        }

        try {
            val getRequest = GetObjectRequest.builder()
                .bucket(storageConfig.bucket)
                .key(fileKey)
                .build()
            return s3Client.getObjectAsBytes(getRequest).asByteArray()
        } catch (e: S3Exception) {
            log.error("下载文件失败: {} - {}", fileKey, e.message, e)
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: ${e.message}")
        }
    }

    /**
     * 通用文件上传方法
     */
    private fun uploadFile(file: MultipartFile, prefix: String): String {
        val originalFilename = file.originalFilename
        val fileKey = generateFileKey(originalFilename, prefix)

        try {
            val putRequest = PutObjectRequest.builder()
                .bucket(storageConfig.bucket)
                .key(fileKey)
                .contentType(file.contentType)
                .contentLength(file.size)
                .build()

            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.inputStream, file.size))
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey)
            return fileKey
        } catch (e: Exception) {
            when (e) {
                is S3Exception -> {
                    log.error("上传文件到RustFS失败: {}", e.message, e)
                    throw BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: ${e.message}")
                }
                else -> {
                    log.error("读取上传文件失败: {}", e.message, e)
                    throw BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败")
                }
            }
        }
    }

    /**
     * 检查文件是否存在
     */
    fun fileExists(fileKey: String): Boolean {
        return try {
            val headRequest = HeadObjectRequest.builder()
                .bucket(storageConfig.bucket)
                .key(fileKey)
                .build()
            s3Client.headObject(headRequest)
            true
        } catch (_: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            log.warn("检查文件存在性失败: {} - {}", fileKey, e.message)
            false
        }
    }

    /**
     * 获取文件大小（字节）
     */
    fun getFileSize(fileKey: String): Long {
        try {
            val headRequest = HeadObjectRequest.builder()
                .bucket(storageConfig.bucket)
                .key(fileKey)
                .build()
            return s3Client.headObject(headRequest).contentLength()
        } catch (e: S3Exception) {
            log.error("获取文件大小失败: {} - {}", fileKey, e.message)
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败")
        }
    }

    /**
     * 通用文件删除方法
     */
    private fun deleteFile(fileKey: String?) {
        if (fileKey.isNullOrBlank()) {
            log.debug("文件键为空，跳过删除")
            return
        }
        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: {}", fileKey)
            return
        }

        try {
            val deleteRequest = DeleteObjectRequest.builder()
                .bucket(storageConfig.bucket)
                .key(fileKey)
                .build()
            s3Client.deleteObject(deleteRequest)
            log.info("文件删除成功: {}", fileKey)
        } catch (e: S3Exception) {
            log.error("删除文件失败: {} - {}", fileKey, e.message, e)
            throw BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: ${e.message}")
        }
    }

    /**
     * 获取文件访问 URL
     */
    fun getFileUrl(fileKey: String?): String {
        return "${storageConfig.endpoint}/${storageConfig.bucket}/${fileKey ?: ""}"
    }

    /**
     * 确保存储桶存在
     */
    fun ensureBucketExists() {
        try {
            val headRequest = HeadBucketRequest.builder()
                .bucket(storageConfig.bucket)
                .build()
            s3Client.headBucket(headRequest)
            log.info("存储桶已存在: {}", storageConfig.bucket)
        } catch (_: NoSuchBucketException) {
            log.info("存储桶不存在，正在创建: {}", storageConfig.bucket)
            val createRequest = CreateBucketRequest.builder()
                .bucket(storageConfig.bucket)
                .build()
            s3Client.createBucket(createRequest)
            log.info("存储桶创建成功: {}", storageConfig.bucket)
        } catch (e: S3Exception) {
            log.error("检查存储桶失败: {}", e.message, e)
        }
    }

    /**
     * 生成文件键
     */
    private fun generateFileKey(originalFilename: String?, prefix: String): String {
        val now = LocalDateTime.now()
        val datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val safeName = sanitizeFilename(originalFilename)
        return "$prefix/$datePath/${uuid}_$safeName"
    }

    /**
     * 清理文件名，移除不安全的字符
     */
    private fun sanitizeFilename(filename: String?): String {
        if (filename.isNullOrBlank()) {
            return "unknown"
        }
        return convertToPinyin(filename)
    }

    /**
     * 将字符串中的汉字转换为大驼峰拼音，非汉字字符保持不变
     */
    private fun convertToPinyin(input: String): String {
        val format = HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
        }

        val result = StringBuilder()
        for (ch in input.toCharArray()) {
            try {
                val pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format)
                if (pinyins != null && pinyins.isNotEmpty()) {
                    result.append(capitalize(pinyins[0]))
                } else {
                    result.append(sanitizeChar(ch))
                }
            } catch (_: BadHanyuPinyinOutputFormatCombination) {
                result.append(sanitizeChar(ch))
            }
        }
        return result.toString()
    }

    /**
     * 处理单个字符，保留安全字符，其他替换为下划线
     */
    private fun sanitizeChar(ch: Char): Char {
        return if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '.' || ch == '_' || ch == '-') {
            ch
        } else {
            '_'
        }
    }

    /**
     * 首字母大写
     */
    private fun capitalize(str: String): String {
        if (str.isEmpty()) {
            return str
        }
        return str.substring(0, 1).uppercase() + str.substring(1)
    }
}

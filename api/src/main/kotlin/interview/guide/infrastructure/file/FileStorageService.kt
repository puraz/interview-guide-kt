package interview.guide.infrastructure.file

import interview.guide.common.config.StorageConfigProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import jakarta.annotation.PostConstruct
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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
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

    companion object {
        private val log = LoggerFactory.getLogger(FileStorageService::class.java)

        private val DATE_PATH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd") // 日期目录格式
    }

    /**
     * 启动时按配置检查/创建存储桶
     */
    @PostConstruct
    fun init() {
        if (!storageConfig.autoCreateBucket) {
            log.info("存储桶启动检查已关闭: bucket={}", storageConfig.bucket)
            return
        }
        ensureBucketExists()
    }

    /**
     * 上传简历文件
     *
     * @param file 简历文件 // 上传文件
     * @return 文件Key // 存储路径
     */
    fun uploadResume(file: MultipartFile): String {
        return uploadFile(file, "resumes")
    }

    /**
     * 删除简历文件
     *
     * @param fileKey 文件Key // 存储路径
     */
    fun deleteResume(fileKey: String?) {
        deleteFile(fileKey)
    }

    /**
     * 上传知识库文件
     *
     * @param file 知识库文件 // 上传文件
     * @return 文件Key // 存储路径
     */
    fun uploadKnowledgeBase(file: MultipartFile): String {
        return uploadFile(file, "knowledgebases")
    }

    /**
     * 删除知识库文件
     *
     * @param fileKey 文件Key // 存储路径
     */
    fun deleteKnowledgeBase(fileKey: String?) {
        deleteFile(fileKey)
    }

    /**
     * 下载文件（通用方法）
     *
     * @param fileKey 文件存储键
     * @return 文件字节数组
     */
    fun downloadFile(fileKey: String?): ByteArray {
        if (fileKey.isNullOrBlank()) {
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: $fileKey")
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
     *
     * @param file   上传文件 // 文件内容
     * @param prefix 存储前缀 // 业务目录
     * @return 文件Key // 存储路径
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
        } catch (e: IOException) {
            log.error("读取上传文件失败: {}", e.message, e)
            throw BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败")
        } catch (e: S3Exception) {
            log.error("上传文件到RustFS失败: {}", e.message, e)
            throw BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: ${e.message}")
        }
    }

    /**
     * 检查文件是否存在
     *
     * @param fileKey 文件Key // 存储路径
     * @return 是否存在 // 存在为 true
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
     *
     * @param fileKey 文件Key // 存储路径
     * @return 文件大小 // 字节数
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
     *
     * @param fileKey 文件Key // 存储路径
     */
    private fun deleteFile(fileKey: String?) {
        // 空键直接跳过
        if (fileKey.isNullOrEmpty()) {
            log.debug("文件键为空，跳过删除")
            return
        }

        // 检查文件是否存在，避免不必要的删除操作
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
     *
     * @param fileKey 文件Key // 存储路径
     * @return 文件URL // 可直接访问的地址
     */
    fun getFileUrl(fileKey: String?): String {
        return String.format("%s/%s/%s", storageConfig.endpoint, storageConfig.bucket, fileKey)
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
            createBucket()
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                createBucket()
                return
            }
            log.error("检查存储桶失败: bucket={}, error={}", storageConfig.bucket, e.message, e)
            throw BusinessException(
                ErrorCode.STORAGE_UPLOAD_FAILED,
                "检查存储桶失败: ${e.message}",
                e
            )
        }
    }

    /**
     * 创建存储桶
     */
    private fun createBucket() {
        try {
            log.info("存储桶不存在，正在创建: {}", storageConfig.bucket)
            val createRequest = CreateBucketRequest.builder()
                .bucket(storageConfig.bucket)
                .build()
            s3Client.createBucket(createRequest)
            log.info("存储桶创建成功: {}", storageConfig.bucket)
        } catch (e: S3Exception) {
            if (e.statusCode() == 409) {
                log.info("存储桶已由其他进程创建: {}", storageConfig.bucket)
                return
            }
            log.error("创建存储桶失败: bucket={}, error={}", storageConfig.bucket, e.message, e)
            throw BusinessException(
                ErrorCode.STORAGE_UPLOAD_FAILED,
                "创建存储桶失败: ${e.message}",
                e
            )
        }
    }

    /**
     * 生成文件键
     *
     * @param originalFilename 原始文件名 // 上传文件名
     * @param prefix           存储前缀 // 业务目录
     * @return 文件Key // 前缀/日期/随机名
     */
    private fun generateFileKey(originalFilename: String?, prefix: String): String {
        val now = LocalDateTime.now()
        val datePath = now.format(DATE_PATH_FORMAT)
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val safeName = sanitizeFilename(originalFilename)
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName)
    }

    /**
     * 清理文件名，移除不安全的字符
     *
     * 汉字转换为大驼峰拼音，保留字母、数字、点号、下划线和连字符，
     * 其他字符统一替换为下划线，防止 S3 存储出现问题。
     *
     * @param filename 原始文件名 // 可能为 null
     * @return 清理后的安全文件名 // unknown 兜底
     */
    private fun sanitizeFilename(filename: String?): String {
        if (filename.isNullOrEmpty()) {
            return "unknown"
        }
        return convertToPinyin(filename)
    }

    /**
     * 将字符串中的汉字转换为大驼峰拼音，非汉字字符保持不变
     *
     * @param input 输入字符串 // 原始文件名
     * @return 转换后的字符串 // 安全文件名
     */
    private fun convertToPinyin(input: String): String {
        val format = HanyuPinyinOutputFormat()
        format.caseType = HanyuPinyinCaseType.LOWERCASE
        format.toneType = HanyuPinyinToneType.WITHOUT_TONE

        val result = StringBuilder()
        for (ch in input.toCharArray()) {
            try {
                val pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format)
                if (pinyins != null && pinyins.isNotEmpty()) {
                    // 首字母大写（大驼峰）
                    result.append(capitalize(pinyins[0]))
                } else {
                    // 非汉字字符直接保留，但特殊字符需要处理
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
     *
     * @param ch 原始字符 // 文件名字符
     * @return 安全字符 // 替换后的字符
     */
    private fun sanitizeChar(ch: Char): Char {
        if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9')) {
            return ch
        }
        if (ch == '.' || ch == '_' || ch == '-') {
            return ch
        }
        return '_'
    }

    /**
     * 首字母大写
     *
     * @param str 原始字符串 // 拼音
     * @return 首字母大写结果 // 大驼峰
     */
    private fun capitalize(str: String): String {
        if (str.isEmpty()) {
            return str
        }
        return str.substring(0, 1).uppercase() + str.substring(1)
    }
}

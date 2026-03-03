package interview.guide.infrastructure.file

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 文件验证服务
 * 提供通用的文件验证功能
 */
@Service
class FileValidationService {

    private val log = LoggerFactory.getLogger(FileValidationService::class.java)

    /**
     * 验证文件基本属性（是否为空、文件大小）
     *
     * @param file 上传的文件 // 文件对象
     * @param maxSizeBytes 最大文件大小（字节）// 最大限制
     * @param fileTypeName 文件类型名称 // 例如“简历”
     */
    fun validateFile(file: MultipartFile, maxSizeBytes: Long, fileTypeName: String) {
        if (file.isEmpty) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的${fileTypeName}文件")
        }
        if (file.size > maxSizeBytes) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制")
        }
    }

    /**
     * 验证文件类型（基于MIME类型列表）
     *
     * @param contentType 文件的MIME类型 // 检测结果
     * @param allowedTypes 允许的MIME类型列表 // 白名单
     * @param errorMessage 验证失败时的错误消息 // 自定义错误信息
     */
    fun validateContentTypeByList(contentType: String?, allowedTypes: List<String>, errorMessage: String?) {
        if (!isAllowedType(contentType, allowedTypes)) {
            throw BusinessException(
                ErrorCode.BAD_REQUEST,
                errorMessage ?: "不支持的文件类型: ${contentType ?: "unknown"}"
            )
        }
    }

    /**
     * 验证文件类型（基于MIME类型和文件扩展名）
     */
    fun validateContentType(
        contentType: String?,
        fileName: String?,
        mimeTypeChecker: (String?) -> Boolean,
        extensionChecker: (String?) -> Boolean,
        errorMessage: String?
    ) {
        if (mimeTypeChecker.invoke(contentType)) {
            return
        }
        if (fileName != null && extensionChecker.invoke(fileName)) {
            return
        }
        throw BusinessException(
            ErrorCode.BAD_REQUEST,
            errorMessage ?: "不支持的文件类型: ${contentType ?: "unknown"}"
        )
    }

    /**
     * 检查文件类型是否在允许列表中
     */
    private fun isAllowedType(contentType: String?, allowedTypes: List<String>): Boolean {
        if (contentType == null || allowedTypes.isEmpty()) {
            return false
        }
        val lowerContentType = contentType.lowercase()
        return allowedTypes.any { allowed ->
            val lowerAllowed = allowed.lowercase()
            lowerContentType.contains(lowerAllowed) || lowerAllowed.contains(lowerContentType)
        }
    }

    /**
     * 检查文件扩展名是否为Markdown格式
     */
    fun isMarkdownExtension(fileName: String?): Boolean {
        if (fileName == null) {
            return false
        }
        val lowerFileName = fileName.lowercase()
        return lowerFileName.endsWith(".md") ||
            lowerFileName.endsWith(".markdown") ||
            lowerFileName.endsWith(".mdown")
    }

    /**
     * 检查MIME类型是否为知识库支持的格式
     */
    fun isKnowledgeBaseMimeType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }
        val lowerContentType = contentType.lowercase()
        return lowerContentType.contains("pdf") ||
            lowerContentType.contains("msword") ||
            lowerContentType.contains("wordprocessingml") ||
            lowerContentType.contains("text/plain") ||
            lowerContentType.contains("text/markdown") ||
            lowerContentType.contains("text/x-markdown") ||
            lowerContentType.contains("text/x-web-markdown") ||
            lowerContentType.contains("application/rtf")
    }
}

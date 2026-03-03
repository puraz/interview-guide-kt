package interview.guide.infrastructure.file

import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

/**
 * 文件内容类型检测服务
 * 使用 Apache Tika 进行精确的 MIME 类型检测
 */
@Service
class ContentTypeDetectionService {

    private val log = LoggerFactory.getLogger(ContentTypeDetectionService::class.java)
    private val tika = Tika() // Tika 检测器

    /**
     * 检测文件的 MIME 类型
     *
     * @param file MultipartFile 文件 // 上传文件
     * @return MIME 类型 // 解析出的类型
     */
    fun detectContentType(file: MultipartFile): String? {
        return try {
            file.inputStream.use { input ->
                tika.detect(input, file.originalFilename)
            }
        } catch (e: Exception) {
            log.warn("无法检测文件类型，使用 Content-Type 头部: {}", e.message)
            file.contentType
        }
    }

    /**
     * 检测输入流的 MIME 类型
     */
    fun detectContentType(inputStream: InputStream, fileName: String?): String {
        return try {
            tika.detect(inputStream, fileName)
        } catch (e: Exception) {
            log.warn("无法检测文件类型: {}", e.message)
            "application/octet-stream"
        }
    }

    /**
     * 检测字节数组的 MIME 类型
     */
    fun detectContentType(data: ByteArray, fileName: String?): String {
        return tika.detect(data, fileName)
    }

    /**
     * 判断是否为 PDF 文件
     */
    fun isPdf(contentType: String?): Boolean {
        return contentType != null && contentType.lowercase().contains("pdf")
    }

    /**
     * 判断是否为 Word 文档（DOC/DOCX）
     */
    fun isWordDocument(contentType: String?): Boolean {
        if (contentType == null) return false
        val lower = contentType.lowercase()
        return lower.contains("msword") || lower.contains("wordprocessingml")
    }

    /**
     * 判断是否为纯文本文件
     */
    fun isPlainText(contentType: String?): Boolean {
        return contentType != null && contentType.lowercase().startsWith("text/")
    }

    /**
     * 判断是否为 Markdown 文件
     */
    fun isMarkdown(contentType: String?, fileName: String?): Boolean {
        if (contentType != null) {
            val lower = contentType.lowercase()
            if (lower.contains("markdown") || lower.contains("x-markdown")) {
                return true
            }
        }
        if (fileName != null) {
            val lowerName = fileName.lowercase()
            return lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerName.endsWith(".mdown")
        }
        return false
    }
}

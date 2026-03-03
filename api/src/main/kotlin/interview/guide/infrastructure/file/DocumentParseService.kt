package interview.guide.infrastructure.file

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.apache.tika.exception.TikaException
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 通用文档解析服务
 * 使用 Apache Tika 解析多种文档格式，提取文本内容
 * 供知识库和简历模块共同使用
 */
@Service
class DocumentParseService(
    private val textCleaningService: TextCleaningService // 文本清理服务
) {

    private val log = LoggerFactory.getLogger(DocumentParseService::class.java)

    companion object {
        private const val MAX_TEXT_LENGTH = 5 * 1024 * 1024 // 最大文本长度
    }

    /**
     * 解析上传的文件，提取文本内容
     *
     * @param file 上传的文件 // 支持 PDF、DOCX、DOC、TXT、MD 等
     * @return 提取的文本内容 // 清理后的文本
     */
    fun parseContent(file: MultipartFile): String {
        val fileName = file.originalFilename
        log.info("开始解析文件: {}", fileName)

        if (file.isEmpty || file.size == 0L) {
            log.warn("文件为空: {}", fileName)
            return ""
        }

        try {
            file.inputStream.use { inputStream ->
                val content = parseContent(inputStream)
                val cleanedContent = textCleaningService.cleanText(content)
                log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length)
                return cleanedContent
            }
        } catch (e: Exception) {
            log.error("文件解析失败: {}", e.message, e)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: ${e.message}")
        }
    }

    /**
     * 解析字节数组形式的文件内容
     */
    fun parseContent(fileBytes: ByteArray, fileName: String?): String {
        log.info("开始解析文件（从字节数组）: {}", fileName)

        if (fileBytes.isEmpty()) {
            log.warn("文件字节数组为空: {}", fileName)
            return ""
        }

        return try {
            ByteArrayInputStream(fileBytes).use { inputStream ->
                val content = parseContent(inputStream)
                val cleanedContent = textCleaningService.cleanText(content)
                log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length)
                cleanedContent
            }
        } catch (e: Exception) {
            log.error("文件解析失败: {}", e.message, e)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: ${e.message}")
        }
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档
     */
    @Throws(TikaException::class, SAXException::class)
    private fun parseContent(inputStream: InputStream): String {
        val parser = AutoDetectParser()
        val handler = BodyContentHandler(MAX_TEXT_LENGTH)
        val metadata = Metadata()
        val context = ParseContext()

        context.set(Parser::class.java, parser)
        context.set(EmbeddedDocumentExtractor::class.java, NoOpEmbeddedDocumentExtractor())

        val pdfConfig = PDFParserConfig()
        pdfConfig.isExtractInlineImages = false
        pdfConfig.isSortByPosition = true
        context.set(PDFParserConfig::class.java, pdfConfig)

        parser.parse(inputStream, handler, metadata, context)
        return handler.toString()
    }

    /**
     * 从存储下载文件并解析内容
     */
    fun downloadAndParseContent(storageService: FileStorageService, storageKey: String?, originalFilename: String?): String {
        try {
            val fileBytes = storageService.downloadFile(storageKey)
            if (fileBytes.isEmpty()) {
                throw BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败")
            }
            return parseContent(fileBytes, originalFilename)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey, e.message, e)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败: ${e.message}")
        }
    }
}

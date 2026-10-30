package interview.guide.infrastructure.file

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import org.apache.tika.exception.TikaException
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 通用文档解析服务
 * 使用 Apache Tika 解析多种文档格式，提取文本内容
 * 供知识库和简历模块共同使用
 */
@Service
class DocumentParseService @Autowired constructor(
    private val textCleaningService: TextCleaningService, // 文本清理服务
    @Qualifier("documentParseExecutor")
    private val parseExecutor: ThreadPoolExecutor?, // 文档解析线程池（无参构造时为 null，退化为同步解析）
    private val properties: DocumentParseProperties // 解析超时与线程池配置
) {

    companion object {
        private val log = LoggerFactory.getLogger(DocumentParseService::class.java)

        private const val MAX_TEXT_LENGTH = 5 * 1024 * 1024 // 5MB
    }

    /**
     * 便于单元测试使用的同步构造器（与原实现保持一致）
     *
     * @param textCleaningService 文本清理服务 // 文本清洗依赖
     */
    constructor(textCleaningService: TextCleaningService) : this(
        textCleaningService,
        null,
        DocumentParseProperties()
    )

    /**
     * 解析上传的文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    fun parseContent(file: MultipartFile): String {
        val fileName = file.originalFilename
        log.info("开始解析文件: {}", fileName)

        // 处理空文件
        if (file.isEmpty || file.size == 0L) {
            log.warn("文件为空: {}", fileName)
            return ""
        }

        try {
            val content = parseWithTimeout(file.bytes)
            val cleanedContent = textCleaningService.cleanText(content)
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length)
            return cleanedContent
        } catch (e: BusinessException) {
            throw e
        } catch (e: IOException) {
            log.error("文件读取失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e))
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件读取失败")
        }
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    fun parseContent(fileBytes: ByteArray?, fileName: String?): String {
        log.info("开始解析文件（从字节数组）: {}", fileName)

        // 处理空文件
        if (fileBytes == null || fileBytes.isEmpty()) {
            log.warn("文件字节数组为空: {}", fileName)
            return ""
        }

        try {
            val content = parseWithTimeout(fileBytes)
            val cleanedContent = textCleaningService.cleanText(content)
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length)
            return cleanedContent
        } catch (e: BusinessException) {
            throw e
        }
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档
     *
     * 优化点：
     * 1. 使用 BodyContentHandler 只提取正文内容
     * 2. 禁用 EmbeddedDocumentExtractor，不解析嵌入资源（图片、附件）
     * 3. 配置 PDFParserConfig，关闭图片和注释提取
     * 4. 显式指定 Parser 到 Context，增强健壮性
     *
     * @param inputStream 文件输入流
     * @return 提取的文本内容
     * @throws IOException   IO 异常
     * @throws TikaException Tika 解析异常
     * @throws SAXException  SAX 解析异常
     */
    private fun parseContent(inputStream: InputStream): String {
        // 1. 创建自动检测解析器
        val parser = AutoDetectParser()

        // 2. 创建内容处理器，只接收正文，限制最大长度为 5MB
        val handler = BodyContentHandler(MAX_TEXT_LENGTH)

        // 3. 创建元数据对象
        val metadata = Metadata()

        // 4. 创建解析上下文
        val context = ParseContext()

        // 5. 显式指定 Parser 到 Context（增强健壮性）
        context.set(Parser::class.java, parser)

        // 6. 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
        context.set(EmbeddedDocumentExtractor::class.java, NoOpEmbeddedDocumentExtractor())

        // 7. PDF 专用配置：关闭图片提取，按位置排序文本
        val pdfConfig = PDFParserConfig()
        pdfConfig.isExtractInlineImages = false
        pdfConfig.isSortByPosition = true // 按 x/y 坐标排序文本，改善多栏布局解析顺序
        // 注意：Tika 2.9.2 中 setExtractAnnotations 方法可能不存在，关闭图片提取已足够
        context.set(PDFParserConfig::class.java, pdfConfig)

        // 8. 执行解析
        parser.parse(inputStream, handler, metadata, context)

        // 9. 返回提取的文本内容
        return handler.toString()
    }

    /**
     * 在有界线程池中解析并对单次解析设置超时
     *
     * @param fileBytes 文件字节数组 // 待解析内容
     * @return 提取的文本内容 // Tika 解析结果
     */
    private fun parseWithTimeout(fileBytes: ByteArray): String {
        if (parseExecutor == null) {
            return parseDirect(fileBytes)
        }
        val future = parseExecutor.submit(Callable {
            ByteArrayInputStream(fileBytes).use { inputStream ->
                parseContent(inputStream)
            }
        })
        try {
            return future.get(properties.timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.error("文件解析超时: timeoutMs={}", properties.timeout.toMillis(),
                ErrorLogSanitizer.forLogging(e))
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析超时，请检查文件内容")
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析被中断")
        } catch (e: ExecutionException) {
            val cause: Throwable = e.cause ?: e
            log.error("文件解析失败: {}", ErrorLogSanitizer.summarize(cause),
                ErrorLogSanitizer.forLogging(cause))
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败")
        }
    }

    /**
     * 无线程池时的同步解析
     *
     * @param fileBytes 文件字节数组 // 待解析内容
     * @return 提取的文本内容 // Tika 解析结果
     */
    private fun parseDirect(fileBytes: ByteArray): String {
        return try {
            ByteArrayInputStream(fileBytes).use { inputStream ->
                parseContent(inputStream)
            }
        } catch (e: IOException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败")
        } catch (e: TikaException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败")
        } catch (e: SAXException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败")
        }
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageService   文件存储服务
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    fun downloadAndParseContent(
        storageService: FileStorageService,
        storageKey: String?,
        originalFilename: String?
    ): String {
        try {
            val fileBytes = storageService.downloadFile(storageKey)
            if (fileBytes.isEmpty()) {
                throw BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败")
            }
            return parseContent(fileBytes, originalFilename)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e))
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败")
        }
    }
}

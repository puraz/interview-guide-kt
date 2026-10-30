package interview.guide.modules.knowledgebase.service

import interview.guide.infrastructure.file.ContentTypeDetectionService
import interview.guide.infrastructure.file.DocumentParseService
import interview.guide.infrastructure.file.FileStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 知识库解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Service
class KnowledgeBaseParseService(
    private val documentParseService: DocumentParseService, // 通用文档解析服务
    private val contentTypeDetectionService: ContentTypeDetectionService, // 文件类型检测服务
    private val storageService: FileStorageService // 文件存储服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseParseService::class.java)

    /**
     * 解析上传的知识库文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    fun parseContent(file: MultipartFile): String {
        log.info("开始解析知识库文件: {}", file.originalFilename)
        return documentParseService.parseContent(file)
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    fun parseContent(fileBytes: ByteArray, fileName: String?): String {
        log.info("开始解析知识库文件（从字节数组）: {}", fileName)
        return documentParseService.parseContent(fileBytes, fileName)
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    fun downloadAndParseContent(storageKey: String?, originalFilename: String?): String {
        log.info("从存储下载并解析知识库文件: {}", originalFilename)
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename)
    }

    /**
     * 检测文件的MIME类型
     */
    fun detectContentType(file: MultipartFile): String? {
        return contentTypeDetectionService.detectContentType(file)
    }
}

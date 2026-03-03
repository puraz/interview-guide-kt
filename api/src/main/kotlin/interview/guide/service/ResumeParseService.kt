package interview.guide.service

import interview.guide.infrastructure.file.ContentTypeDetectionService
import interview.guide.infrastructure.file.DocumentParseService
import interview.guide.infrastructure.file.FileStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 简历解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Service
class ResumeParseService(
    private val documentParseService: DocumentParseService, // 文档解析服务
    private val contentTypeDetectionService: ContentTypeDetectionService, // 类型检测服务
    private val storageService: FileStorageService // 存储服务
) {

    private val log = LoggerFactory.getLogger(ResumeParseService::class.java)

    /**
     * 解析上传的简历文件，提取文本内容
     *
     * @param file 上传的文件 // 简历文件
     * @return 提取的文本内容 // 解析结果
     */
    fun parseResume(file: MultipartFile): String {
        log.info("开始解析简历文件: {}", file.originalFilename)
        return documentParseService.parseContent(file)
    }

    /**
     * 解析字节数组形式的简历文件
     */
    fun parseResume(fileBytes: ByteArray, fileName: String?): String {
        log.info("开始解析简历文件（从字节数组）: {}", fileName)
        return documentParseService.parseContent(fileBytes, fileName)
    }

    /**
     * 从存储下载文件并解析内容
     */
    fun downloadAndParseContent(storageKey: String?, originalFilename: String?): String {
        log.info("从存储下载并解析简历文件: {}", originalFilename)
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename)
    }

    /**
     * 检测文件的MIME类型
     */
    fun detectContentType(file: MultipartFile): String? {
        return contentTypeDetectionService.detectContentType(file)
    }
}

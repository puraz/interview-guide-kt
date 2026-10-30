package interview.guide.infrastructure.file

import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.metadata.Metadata
import org.slf4j.LoggerFactory
import org.xml.sax.ContentHandler
import java.io.InputStream

/**
 * 空操作的嵌入文档提取器
 * 用于禁用 Tika 对嵌入资源（图片、附件等）的解析
 */
class NoOpEmbeddedDocumentExtractor : EmbeddedDocumentExtractor {

    private val log = LoggerFactory.getLogger(NoOpEmbeddedDocumentExtractor::class.java)

    /**
     * 是否应该解析嵌入文档
     *
     * @param metadata 文档元数据
     * @return 始终返回 false，禁用嵌入文档解析
     */
    override fun shouldParseEmbedded(metadata: Metadata): Boolean {
        // 记录跳过的嵌入文档（使用字符串常量，兼容不同 Tika 版本）
        val resourceName = metadata.get("resourceName")
        if (resourceName != null) {
            log.debug("Skip embedded document: {}", resourceName)
        }
        return false
    }

    /**
     * 解析嵌入文档（空实现）
     *
     * 由于 [shouldParseEmbedded] 返回 false，此方法不会被调用
     *
     * @param stream     输入流
     * @param handler    内容处理器
     * @param metadata   元数据
     * @param outputHtml 是否输出 HTML
     */
    override fun parseEmbedded(
        stream: InputStream,
        handler: ContentHandler,
        metadata: Metadata,
        outputHtml: Boolean
    ) {
        // 空实现，不执行任何操作
    }
}

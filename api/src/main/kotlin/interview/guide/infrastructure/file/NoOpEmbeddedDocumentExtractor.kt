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
     */
    override fun shouldParseEmbedded(metadata: Metadata): Boolean {
        val resourceName = metadata.get("resourceName")
        if (resourceName != null) {
            log.debug("Skip embedded document: {}", resourceName)
        }
        return false
    }

    /**
     * 解析嵌入文档（空实现）
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

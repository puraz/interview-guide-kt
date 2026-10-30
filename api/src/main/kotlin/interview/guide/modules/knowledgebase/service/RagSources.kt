package interview.guide.modules.knowledgebase.service

import org.springframework.ai.document.Document

/**
 * 对实际进入 Prompt 的检索片段编号，来源尾注由服务端生成。
 */
internal object RagSources {

    /**
     * 构建带来源标注的上下文
     */
    fun context(documents: List<Document>): String {
        val result = StringBuilder()
        for (i in documents.indices) {
            if (i > 0) {
                result.append("\n\n---\n\n")
            }
            result.append("[S").append(i + 1).append("] 来源：")
                .append(description(documents[i])).append("\n")
                .append(documents[i].text)
        }
        return result.toString()
    }

    /**
     * 构建参考片段尾注
     */
    fun footer(documents: List<Document>): String {
        if (documents.isEmpty()) {
            return ""
        }
        val result = StringBuilder("\n\n---\n\n参考片段（本次检索提供）：\n\n")
        for (i in documents.indices) {
            result.append("- [S").append(i + 1).append("] ")
                .append(description(documents[i])).append("\n")
        }
        return result.toString()
    }

    private fun description(document: Document): String {
        val metadata = document.metadata
        val name = text(metadata["source_name"])
        val kbId = text(metadata["kb_id"])
        val result = StringBuilder(if (name.isBlank()) "知识库片段" else name)
        if (kbId.isNotBlank()) {
            result.append("（知识库 ").append(kbId).append("）")
        }
        val filename = text(metadata["source_filename"])
        if (filename.isNotBlank()) {
            result.append("，原文件：").append(filename)
        }
        val index = metadata["chunk_index"]
        val total = metadata["total_chunks"]
        if (index is Number && total is Number) {
            val indexValue = index.toInt()
            val totalValue = total.toInt()
            if (indexValue >= 0 && indexValue < totalValue) {
                result.append("，第 ").append(indexValue + 1).append("/")
                    .append(totalValue).append(" 个片段")
            }
        }
        return result.toString()
    }

    private fun text(value: Any?): String {
        if (value == null) {
            return ""
        }
        val escaped = StringBuilder()
        val singleLine = value.toString().replace(Regex("[\\p{Cntrl}\\p{Z}\\s]+"), " ").trim()
        for (c in singleLine.toCharArray()) {
            if ("\\`*_{}[]()#+-.!|<>~&".indexOf(c) >= 0) {
                escaped.append('\\')
            }
            escaped.append(c)
        }
        return escaped.toString()
    }
}

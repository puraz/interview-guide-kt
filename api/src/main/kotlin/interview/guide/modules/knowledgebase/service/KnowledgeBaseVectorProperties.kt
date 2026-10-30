package interview.guide.modules.knowledgebase.service

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import org.springframework.ai.transformer.splitter.TextSplitter
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * 知识库向量化 Chunk 策略配置。
 *
 * 只暴露 Spring AI 2.0 TokenTextSplitter 原生支持的参数，不虚构 overlap 能力；
 * 是否开发自定义重叠分块器由边界题测评结果决定（P1-03 记录结论）。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rag.vectorization")
class KnowledgeBaseVectorProperties {

    /**
     * 分块器类型，当前仅支持 token。
     */
    @field:NotBlank(message = "splitter 不能为空")
    @field:Pattern(regexp = "token", message = "splitter 当前仅支持 token")
    var splitter: String = "token"

    /**
     * 每块目标 token 数。
     */
    @field:Min(value = 1, message = "chunkSize 必须大于 0")
    var chunkSize: Int = 800

    /**
     * 单块最小字符数，低于该长度的片段并入相邻块。
     */
    @field:Min(value = 1, message = "minChunkSizeChars 必须大于 0")
    var minChunkSizeChars: Int = 350

    /**
     * 低于该字符长度的块不参与 Embedding。
     */
    @field:Min(value = 0, message = "minChunkLengthToEmbed 不能为负数")
    var minChunkLengthToEmbed: Int = 5

    /**
     * 单文档最大块数，超出截断。
     */
    @field:Min(value = 1, message = "maxNumChunks 必须大于 0")
    var maxNumChunks: Int = 10000

    /**
     * 是否在块边界保留分隔符。
     */
    var keepSeparator: Boolean = true

    /**
     * 句末标点集合，YAML 中用 "\\n" 表示换行符。
     */
    @field:NotEmpty(message = "punctuationMarks 不能为空")
    var punctuationMarks: List<String> = listOf(".", "?", "!", "\\n", "。", "？", "！", "；")

    /**
     * 策略版本标识，写入向量 metadata 与知识库配置快照，排查混用问题。
     */
    @field:NotBlank(message = "strategyVersion 不能为空")
    var strategyVersion: String = "token-v1"

    /**
     * 按当前配置构造生产使用的分块器，测试与运行时必须复用该入口。
     */
    fun createTextSplitter(): TextSplitter {
        return TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(minChunkSizeChars)
            .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
            .withMaxNumChunks(maxNumChunks)
            .withKeepSeparator(keepSeparator)
            .withPunctuationMarks(toPunctuationCharacters())
            .build()
    }

    /**
     * 标点字符串转字符集合；"\\n" 转义为换行符，其余取首字符。
     */
    fun toPunctuationCharacters(): List<Char> {
        val characters = ArrayList<Char>()
        for (mark in punctuationMarks) {
            if (mark.isEmpty()) {
                continue
            }
            if ("\\n" == mark) {
                characters.add('\n')
            } else {
                characters.add(mark[0])
            }
        }
        return characters
    }
}

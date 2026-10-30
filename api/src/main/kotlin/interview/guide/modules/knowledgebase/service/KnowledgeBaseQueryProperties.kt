package interview.guide.modules.knowledgebase.service

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 知识库 RAG 查询配置（app.ai.rag.*）
 */
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
class KnowledgeBaseQueryProperties {

    /**
     * RAG 业务指标开关（app.rag.* Micrometer 指标），默认开启。
     */
    var metricsEnabled: Boolean = true

    var rewrite: Rewrite = Rewrite() // Query 改写配置

    var search: Search = Search() // 检索配置

    var history: History = History() // 多轮上下文配置

    var systemPromptPath: String = "classpath:prompts/knowledgebase-query-system.st" // 系统提示词路径

    var userPromptPath: String = "classpath:prompts/knowledgebase-query-user.st" // 用户提示词路径

    var rewritePromptPath: String = "classpath:prompts/knowledgebase-query-rewrite.st" // 改写提示词路径

    /**
     * Query 改写配置
     */
    class Rewrite {
        var enabled: Boolean = true // 是否开启改写
    }

    /**
     * 检索配置
     */
    class Search {
        /**
         * 双路召回融合：开启后改写 Query 与原始 Query 各自检索一次并按 Document 去重融合。
         * 测评证明整体收益成立前默认关闭。
         */
        var mergeOriginalQuery: Boolean = false

        var shortQueryLength: Int = 4 // 短问题长度阈值

        var topkShort: Int = 20 // 短问题 Top K

        var topkMedium: Int = 12 // 中等长度问题 Top K

        var topkLong: Int = 8 // 长问题 Top K

        var minScoreShort: Double = 0.25 // 短问题相似度阈值

        var minScoreDefault: Double = 0.28 // 默认相似度阈值
    }

    /**
     * 多轮上下文配置
     */
    class History {
        var enabled: Boolean = true // 是否启用历史上下文

        var maxMessages: Int = 10 // 最大历史消息数量
    }
}

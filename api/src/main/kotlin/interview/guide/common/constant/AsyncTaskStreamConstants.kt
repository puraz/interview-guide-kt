package interview.guide.common.constant

/**
 * 异步任务 Redis Stream 通用常量
 * 包含知识库向量化和简历分析两个异步任务的配置
 */
object AsyncTaskStreamConstants {

    /**
     * 重试次数字段
     */
    const val FIELD_RETRY_COUNT: String = "retryCount" // 重试次数

    /**
     * 文档内容字段
     */
    const val FIELD_CONTENT: String = "content" // 文档内容

    /**
     * 最大重试次数
     */
    const val MAX_RETRY_COUNT: Int = 3 // 最大重试次数

    /**
     * 每次拉取的消息批次大小
     */
    const val BATCH_SIZE: Int = 10 // 批量消费数量

    /**
     * 消费者轮询间隔（毫秒）
     */
    const val POLL_INTERVAL_MS: Long = 1000 // 轮询间隔毫秒

    /**
     * Stream 最大长度（自动裁剪旧消息，防止无限增长）
     */
    const val STREAM_MAX_LEN: Int = 1000 // Stream 最大长度

    // ========== 知识库向量化 Stream 配置 ==========

    const val KB_VECTORIZE_STREAM_KEY: String = "knowledgebase:vectorize:stream" // 知识库向量化 Stream Key
    const val KB_VECTORIZE_GROUP_NAME: String = "vectorize-group" // 知识库向量化消费者组
    const val KB_VECTORIZE_CONSUMER_PREFIX: String = "vectorize-consumer-" // 知识库向量化消费者前缀
    const val FIELD_KB_ID: String = "kbId" // 知识库ID字段

    // ========== 简历分析 Stream 配置 ==========

    const val RESUME_ANALYZE_STREAM_KEY: String = "resume:analyze:stream" // 简历分析 Stream Key
    const val RESUME_ANALYZE_GROUP_NAME: String = "analyze-group" // 简历分析消费者组
    const val RESUME_ANALYZE_CONSUMER_PREFIX: String = "analyze-consumer-" // 简历分析消费者前缀
    const val FIELD_RESUME_ID: String = "resumeId" // 简历ID字段

    // ========== 面试评估 Stream 配置 ==========

    const val INTERVIEW_EVALUATE_STREAM_KEY: String = "interview:evaluate:stream" // 面试评估 Stream Key
    const val INTERVIEW_EVALUATE_GROUP_NAME: String = "evaluate-group" // 面试评估消费者组
    const val INTERVIEW_EVALUATE_CONSUMER_PREFIX: String = "evaluate-consumer-" // 面试评估消费者前缀
    const val FIELD_SESSION_ID: String = "sessionId" // 面试会话ID字段
}

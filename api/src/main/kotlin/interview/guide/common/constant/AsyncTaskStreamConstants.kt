package interview.guide.common.constant

/**
 * 异步任务 Redis Stream 通用常量
 * 包含知识库向量化和简历分析两个异步任务的配置
 */
object AsyncTaskStreamConstants {

    // ========== 通用消息字段 ==========

    /**
     * 重试次数字段
     */
    const val FIELD_RETRY_COUNT: String = "retryCount"

    // ========== 通用消费者配置 ==========

    /**
     * 最大重试次数
     */
    const val MAX_RETRY_COUNT: Int = 3

    /**
     * 每次拉取的消息批次大小
     */
    const val BATCH_SIZE: Int = 10

    /**
     * Pending 消息空闲超过该时间后允许被当前消费者认领（毫秒）
     */
    const val PENDING_IDLE_TIMEOUT_MS: Long = 5 * 60 * 1000

    /**
     * 每轮最多回收的 pending 消息数
     */
    const val PENDING_CLAIM_BATCH_SIZE: Int = 10

    /**
     * 消费者轮询间隔（毫秒）
     */
    const val POLL_INTERVAL_MS: Long = 1000

    /**
     * Stream 最大长度（自动裁剪旧消息，防止无限增长）
     */
    const val STREAM_MAX_LEN: Int = 1000

    // ========== 知识库向量化 Stream 配置 ==========

    /**
     * 知识库向量化 Stream Key
     */
    const val KB_VECTORIZE_STREAM_KEY: String = "knowledgebase:vectorize:stream"

    /**
     * 知识库向量化 Consumer Group 名称
     */
    const val KB_VECTORIZE_GROUP_NAME: String = "vectorize-group"

    /**
     * 知识库向量化 Consumer 名称前缀
     */
    const val KB_VECTORIZE_CONSUMER_PREFIX: String = "vectorize-consumer-"

    /**
     * 知识库ID字段
     */
    const val FIELD_KB_ID: String = "kbId"

    // ========== 简历分析 Stream 配置 ==========

    /**
     * 简历分析 Stream Key
     */
    const val RESUME_ANALYZE_STREAM_KEY: String = "resume:analyze:stream"

    /**
     * 简历分析 Consumer Group 名称
     */
    const val RESUME_ANALYZE_GROUP_NAME: String = "analyze-group"

    /**
     * 简历分析 Consumer 名称前缀
     */
    const val RESUME_ANALYZE_CONSUMER_PREFIX: String = "analyze-consumer-"

    /**
     * 简历ID字段
     */
    const val FIELD_RESUME_ID: String = "resumeId"

    // ========== 面试评估 Stream 配置 ==========

    /**
     * 面试评估 Stream Key
     */
    const val INTERVIEW_EVALUATE_STREAM_KEY: String = "interview:evaluate:stream"

    /**
     * 面试评估 Consumer Group 名称
     */
    const val INTERVIEW_EVALUATE_GROUP_NAME: String = "evaluate-group"

    /**
     * 面试评估 Consumer 名称前缀
     */
    const val INTERVIEW_EVALUATE_CONSUMER_PREFIX: String = "evaluate-consumer-"

    /**
     * 面试会话ID字段
     */
    const val FIELD_SESSION_ID: String = "sessionId"

    // ========== 语音面试评估 Stream 配置 ==========

    /**
     * 语音面试评估 Stream Key
     */
    const val VOICE_EVALUATE_STREAM_KEY: String = "voice:evaluate:stream"

    /**
     * 语音面试评估 Consumer Group 名称
     */
    const val VOICE_EVALUATE_GROUP_NAME: String = "voice-evaluate-group"

    /**
     * 语音面试评估 Consumer 名称前缀
     */
    const val VOICE_EVALUATE_CONSUMER_PREFIX: String = "voice-evaluate-consumer-"

    /**
     * 语音面试会话ID字段
     */
    const val FIELD_VOICE_SESSION_ID: String = "voiceSessionId"

    // ========== 知识库问题生成 Stream 配置 ==========

    /**
     * 知识库问题生成 Stream Key
     */
    const val KB_QUESTION_GEN_STREAM_KEY: String = "knowledgebase:question-gen:stream"

    /**
     * 知识库问题生成 Consumer Group 名称
     */
    const val KB_QUESTION_GEN_GROUP_NAME: String = "question-gen-group"

    /**
     * 知识库问题生成 Consumer 名称前缀
     */
    const val KB_QUESTION_GEN_CONSUMER_PREFIX: String = "question-gen-consumer-"

    /**
     * 任务ID字段（用于幂等判断）
     */
    const val FIELD_TASK_ID: String = "taskId"

    /**
     * 难度字段
     */
    const val FIELD_DIFFICULTY: String = "difficulty"

    /**
     * 题目数量字段
     */
    const val FIELD_QUESTION_COUNT: String = "questionCount"

    /**
     * 追问数量字段
     */
    const val FIELD_FOLLOW_UP_COUNT: String = "followUpCount"

    /**
     * 方向数量字段
     */
    const val FIELD_CATEGORY_LIMIT: String = "categoryLimit"

    /**
     * LLM Provider字段
     */
    const val FIELD_LLM_PROVIDER: String = "llmProvider"
}

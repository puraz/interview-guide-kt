package interview.guide.modules.interview.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 */
data class InterviewQuestionVo(
    val questionIndex: Int = 0, // 题目索引
    val question: String = "", // 问题内容
    val type: String? = null, // Skill category key，如 "MYSQL"、"CSS"、"DP"
    val category: String? = null, // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    val topicSummary: String? = null, // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    val userAnswer: String? = null, // 用户回答
    val score: Int? = null, // 单题得分
    val feedback: String? = null, // 单题反馈
    @get:JsonProperty("isFollowUp")
    @param:JsonProperty("isFollowUp")
    val isFollowUp: Boolean = false, // 是否为追问（JSON 字段名固定为 isFollowUp）
    val parentQuestionIndex: Int? = null, // 追问关联的主问题索引
    val referenceAnswer: String? = null, // 参考答案（题库来源）
    val keyPoints: List<String>? = null, // 评分要点
    val scoringRubric: String? = null, // 评分规则
    val sourceContext: String? = null // 题目来源上下文
) {

    /**
     * 添加用户回答
     */
    fun withAnswer(answer: String?): InterviewQuestionVo {
        return copy(userAnswer = answer)
    }

    /**
     * 添加评分和反馈
     */
    fun withEvaluation(score: Int?, feedback: String?): InterviewQuestionVo {
        return copy(score = score, feedback = feedback)
    }

    companion object {

        /**
         * 创建新问题（未回答状态）
         */
        fun create(index: Int, question: String, type: String?, category: String?): InterviewQuestionVo {
            return InterviewQuestionVo(
                questionIndex = index,
                question = question,
                type = type,
                category = category
            )
        }

        /**
         * 创建新问题（支持追问标记）
         */
        fun create(
            index: Int,
            question: String,
            type: String?,
            category: String?,
            topicSummary: String?,
            isFollowUp: Boolean,
            parentQuestionIndex: Int?
        ): InterviewQuestionVo {
            return InterviewQuestionVo(
                questionIndex = index,
                question = question,
                type = type,
                category = category,
                topicSummary = topicSummary,
                isFollowUp = isFollowUp,
                parentQuestionIndex = parentQuestionIndex
            )
        }

        /**
         * 从题库条目构建问题（携带参考答案/评分要点/评分规则/来源上下文）
         */
        fun fromQuestionBank(
            index: Int,
            question: String,
            type: String?,
            category: String?,
            topicSummary: String?,
            referenceAnswer: String?,
            keyPoints: List<String>?,
            scoringRubric: String?,
            sourceContext: String?
        ): InterviewQuestionVo {
            return InterviewQuestionVo(
                questionIndex = index,
                question = question,
                type = type,
                category = category,
                topicSummary = topicSummary,
                referenceAnswer = referenceAnswer,
                keyPoints = keyPoints,
                scoringRubric = scoringRubric,
                sourceContext = sourceContext
            )
        }
    }
}

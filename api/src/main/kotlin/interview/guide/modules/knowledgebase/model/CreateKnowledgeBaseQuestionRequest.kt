package interview.guide.modules.knowledgebase.model

import jakarta.validation.constraints.NotBlank

/**
 * 手动创建知识库题目请求
 */
data class CreateKnowledgeBaseQuestionRequest(
    val difficulty: String?, // 难度
    val type: String?, // 题目类型

    @field:NotBlank(message = "面试方向不能为空")
    val category: String, // 面试方向

    @field:NotBlank(message = "题干不能为空")
    val question: String, // 题干

    val topicSummary: String?, // 知识点摘要
    val referenceAnswer: String?, // 参考答案
    val keyPoints: List<String>?, // 要点列表
    val scoringRubric: String?, // 评分标准
    val followUps: List<KnowledgeBaseQuestionFollowUpDTO>?, // 追问题
    val sourceContext: String?, // 来源上下文
    val status: KnowledgeBaseQuestionStatus? // 题目状态
)

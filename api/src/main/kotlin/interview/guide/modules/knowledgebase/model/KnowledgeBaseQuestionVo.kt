package interview.guide.modules.knowledgebase.model

import java.time.LocalDateTime

/**
 * 知识库题目VO
 */
data class KnowledgeBaseQuestionVo(
    val id: Long?, // 题目ID
    val knowledgeBaseId: Long?, // 知识库ID
    val knowledgeBaseName: String?, // 知识库名称
    val skillId: String?, // 技能标识
    val difficulty: String?, // 难度
    val type: String?, // 题目类型
    val category: String?, // 面试方向
    val question: String?, // 题干
    val topicSummary: String?, // 知识点摘要
    val referenceAnswer: String?, // 参考答案
    val keyPoints: List<String>?, // 要点列表
    val scoringRubric: String?, // 评分标准
    val followUps: List<KnowledgeBaseQuestionFollowUpDTO>?, // 追问列表
    val sourceContext: String?, // 来源上下文
    val status: KnowledgeBaseQuestionStatus?, // 题目状态
    val createdAt: LocalDateTime?, // 创建时间
    val updatedAt: LocalDateTime? // 更新时间
)

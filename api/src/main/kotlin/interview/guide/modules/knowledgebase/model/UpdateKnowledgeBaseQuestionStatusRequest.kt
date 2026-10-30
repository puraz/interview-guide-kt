package interview.guide.modules.knowledgebase.model

import jakarta.validation.constraints.NotNull

/**
 * 更新知识库题目状态请求
 */
data class UpdateKnowledgeBaseQuestionStatusRequest(
    @field:NotNull(message = "题目状态不能为空")
    val status: KnowledgeBaseQuestionStatus? // 目标状态
)

package interview.guide.modules.knowledgebase.model

import java.time.LocalDateTime

/**
 * 问题生成任务状态响应。
 */
data class QuestionGenStatusResponse(
    val knowledgeBaseId: Long?, // 知识库ID
    val questionGenStatus: QuestionGenStatus?, // 生成状态
    val questionGenTaskId: String?, // 任务ID
    val questionGenConfig: QuestionGenerationConfig?, // 生成参数快照
    val savedCount: Int, // 已保存题目数
    val skippedCount: Int, // 已跳过题目数
    val message: String?, // 结果摘要
    val error: String?, // 错误信息
    val updatedAt: LocalDateTime? // 状态更新时间
)

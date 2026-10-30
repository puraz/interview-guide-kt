package interview.guide.modules.voiceinterview.dto

import java.time.LocalDateTime

/**
 * Voice evaluation status response DTO
 * 语音面试评估状态响应
 */
data class VoiceEvaluationStatusVo(
    /**
     * Evaluation task status: PENDING / PROCESSING / COMPLETED / FAILED
     * 评估任务状态
     */
    var evaluateStatus: String? = null,

    /**
     * Error message when status is FAILED
     * 评估失败时的错误信息
     */
    var evaluateError: String? = null,

    /**
     * Time when the current evaluation status was last updated.
     * 评估状态最后更新时间
     */
    var evaluateStatusUpdatedAt: LocalDateTime? = null,

    /**
     * Full evaluation result, only present when status is COMPLETED
     * 完整评估结果，仅当状态为 COMPLETED 时返回
     */
    var evaluation: VoiceEvaluationDetailVo? = null
)

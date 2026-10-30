package interview.guide.modules.voiceinterview.controller

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.common.result.Result
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest
import interview.guide.modules.voiceinterview.dto.SessionMetaVo
import interview.guide.modules.voiceinterview.dto.SessionResponseVo
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationStatusVo
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageVo
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService
import interview.guide.modules.voiceinterview.service.VoiceInterviewService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Voice Interview Controller
 * 语音面试控制器
 *
 * REST API endpoints for voice interview session management:
 * - Session lifecycle (create, retrieve, end)
 * - Message history retrieval
 * - Async evaluation trigger and status polling
 */
@RestController
@RequestMapping("/api/voice-interview")
class VoiceInterviewController(
    private val voiceInterviewService: VoiceInterviewService, // 语音面试服务
    private val evaluationService: VoiceInterviewEvaluationService, // 语音面试评估服务
    private val voiceEvaluateStreamProducer: VoiceEvaluateStreamProducer // 评估任务生产者
) {

    private val log = LoggerFactory.getLogger(VoiceInterviewController::class.java)

    /**
     * Create a new voice interview session
     * 创建新的语音面试会话
     */
    @PostMapping("/sessions")
    fun createSession(@Valid @RequestBody request: CreateSessionRequest): Result<SessionResponseVo> {
        log.info("Creating voice interview session for role: {}", request.roleType)
        val session = voiceInterviewService.createSession(request)
        return Result.success(session)
    }

    /**
     * Get session details by ID
     * 根据ID获取会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    fun getSession(@PathVariable sessionId: Long): Result<SessionResponseVo> {
        log.info("Getting session details for: {}", sessionId)
        val session = voiceInterviewService.getSessionDTO(sessionId)
        if (session == null) {
            return Result.error("Session not found: $sessionId")
        }
        return Result.success(session)
    }

    /**
     * End interview session
     * 结束面试会话
     *
     * This also triggers async evaluation via Redis Stream.
     */
    @PostMapping("/sessions/{sessionId}/end")
    fun endSession(@PathVariable sessionId: Long): Result<Void> {
        log.info("Ending session: {}", sessionId)
        voiceInterviewService.endSession(sessionId.toString())
        return Result.success()
    }

    /**
     * Pause interview session
     * 暂停面试会话
     */
    @PutMapping("/sessions/{sessionId}/pause")
    fun pauseSession(
        @PathVariable sessionId: Long,
        @RequestBody request: Map<String, String>
    ): Result<Void> {
        log.info("Pausing session: {}", sessionId)
        val reason = request["reason"] ?: "user_initiated"
        voiceInterviewService.pauseSession(sessionId.toString(), reason)
        return Result.success()
    }

    /**
     * Resume interview session
     * 恢复面试会话
     */
    @PutMapping("/sessions/{sessionId}/resume")
    fun resumeSession(@PathVariable sessionId: Long): Result<SessionResponseVo> {
        log.info("Resuming session: {}", sessionId)
        val session = voiceInterviewService.resumeSession(sessionId.toString())
        return Result.success(session)
    }

    /**
     * Get all sessions for user
     * 获取用户所有语音面试会话
     */
    @GetMapping("/sessions")
    fun getAllSessions(
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) status: String?
    ): Result<List<SessionMetaVo>> {
        log.info("Getting sessions for user: {}, status: {}", userId, status)
        val sessions = voiceInterviewService.getAllSessions(userId, status)
        return Result.success(sessions)
    }

    /**
     * 删除语音面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(@PathVariable sessionId: Long): Result<Void> {
        log.info("Deleting voice interview session: {}", sessionId)
        voiceInterviewService.deleteSession(sessionId)
        return Result.success()
    }

    /**
     * Get conversation history for a session
     * 获取会话对话历史
     */
    @GetMapping("/sessions/{sessionId}/messages")
    fun getMessages(@PathVariable sessionId: Long): Result<List<VoiceInterviewMessageVo>> {
        log.info("Getting messages for session: {}", sessionId)
        val messages = voiceInterviewService.getConversationHistoryDTO(sessionId.toString())
        return Result.success(messages)
    }

    /**
     * Get evaluation status and result for a session
     * 获取会话评估状态与结果
     *
     * Returns the current evaluation status (PENDING/PROCESSING/COMPLETED/FAILED)
     * along with the evaluation result when COMPLETED.
     * Frontend polls this endpoint until evaluation is complete.
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    fun getEvaluation(@PathVariable sessionId: Long): Result<VoiceEvaluationStatusVo> {
        log.info("Getting evaluation status for session: {}", sessionId)

        val session = voiceInterviewService.getSession(sessionId)
            ?: throw BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: $sessionId")

        val status = session.evaluateStatus
        val evaluation = if (status == AsyncTaskStatus.COMPLETED) {
            evaluationService.getEvaluation(sessionId)
        } else {
            null
        }

        return Result.success(
            VoiceEvaluationStatusVo(
                evaluateStatus = status?.name,
                evaluateError = session.evaluateError,
                evaluateStatusUpdatedAt = session.updatedAt,
                evaluation = evaluation
            )
        )
    }

    /**
     * Trigger async evaluation for a session
     * 触发异步评估
     *
     * Enqueues evaluation task to Redis Stream and returns immediately.
     * Frontend should then poll GET /evaluation to track progress.
     * If evaluation is already in progress or completed, returns current status.
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    fun generateEvaluation(@PathVariable sessionId: Long): Result<VoiceEvaluationStatusVo> {
        log.info("Triggering async evaluation for session: {}", sessionId)

        val session = voiceInterviewService.getSession(sessionId)
            ?: throw BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: $sessionId")

        // If already completed, return cached result
        if (session.evaluateStatus == AsyncTaskStatus.COMPLETED) {
            val evaluation = evaluationService.getEvaluation(sessionId)
            return Result.success(
                VoiceEvaluationStatusVo(
                    evaluateStatus = AsyncTaskStatus.COMPLETED.name,
                    evaluateStatusUpdatedAt = session.updatedAt,
                    evaluation = evaluation
                )
            )
        }

        // A POST is an explicit retry. Requeue PENDING tasks instead of returning
        // the same stuck state; PROCESSING tasks keep their current worker.
        if (session.evaluateStatus == AsyncTaskStatus.PROCESSING) {
            return Result.success(
                VoiceEvaluationStatusVo(
                    evaluateStatus = session.evaluateStatus?.name,
                    evaluateStatusUpdatedAt = session.updatedAt
                )
            )
        }

        // Trigger new async evaluation via service
        voiceInterviewService.triggerEvaluation(sessionId)

        return Result.success(
            VoiceEvaluationStatusVo(
                evaluateStatus = AsyncTaskStatus.PENDING.name,
                evaluateStatusUpdatedAt = LocalDateTime.now()
            )
        )
    }
}

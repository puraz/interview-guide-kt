package interview.guide.listener

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.repository.InterviewSessionRepository
import interview.guide.service.AnswerEvaluationService
import interview.guide.service.InterviewPersistenceService
import interview.guide.service.InterviewQuestionVo
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Component
class EvaluateStreamConsumer(
    redisService: RedisService, // Redis 操作封装
    private val sessionRepository: InterviewSessionRepository, // 会话仓库
    private val evaluationService: AnswerEvaluationService, // 评估服务
    private val persistenceService: InterviewPersistenceService, // 持久化服务
    private val objectMapper: ObjectMapper // JSON 序列化
) : AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload>(redisService) {

    private val log = LoggerFactory.getLogger(EvaluateStreamConsumer::class.java)

    data class EvaluatePayload(
        val sessionId: String // 会话ID
    )

    override fun taskDisplayName(): String = "评估" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME // 消费者组

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "evaluate-consumer" // 线程名称

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): EvaluatePayload? {
        val sessionId = data[AsyncTaskStreamConstants.FIELD_SESSION_ID]
        if (sessionId.isNullOrBlank()) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        return EvaluatePayload(sessionId)
    }

    override fun payloadIdentifier(payload: EvaluatePayload): String {
        return "sessionId=${payload.sessionId}"
    }

    override fun markProcessing(payload: EvaluatePayload) {
        updateEvaluateStatus(payload.sessionId, AsyncTaskStatus.PROCESSING, null)
    }

    override fun processBusiness(payload: EvaluatePayload) {
        val sessionId = payload.sessionId
        val session = sessionRepository.findBySessionIdWithResume(sessionId)
        if (session == null) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId)
            return
        }

        val questions: MutableList<InterviewQuestionVo> = objectMapper.readValue(
            session.questionsJson ?: "[]",
            object : TypeReference<List<InterviewQuestionVo>>() {}
        ).toMutableList()

        val answers = persistenceService.findAnswersBySessionId(sessionId)
        for (answer in answers) {
            val index = answer.questionIndex ?: continue
            if (index >= 0 && index < questions.size) {
                val question = questions[index]
                questions[index] = question.withAnswer(answer.userAnswer)
            }
        }

        val resumeText = session.resume?.resumeText ?: ""
        val report = evaluationService.evaluateInterview(sessionId, resumeText, questions)
        persistenceService.saveReport(sessionId, report)
    }

    override fun markCompleted(payload: EvaluatePayload) {
        updateEvaluateStatus(payload.sessionId, AsyncTaskStatus.COMPLETED, null)
    }

    override fun markFailed(payload: EvaluatePayload, error: String?) {
        updateEvaluateStatus(payload.sessionId, AsyncTaskStatus.FAILED, error)
    }

    override fun retryMessage(payload: EvaluatePayload, retryCount: Int) {
        val sessionId = payload.sessionId
        try {
            val message = mapOf(
                AsyncTaskStreamConstants.FIELD_SESSION_ID to sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT to retryCount.toString()
            )

            redisService().streamAdd(
                AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            )
            log.info("评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount)
        } catch (e: Exception) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.message, e)
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: ${e.message}"))
        }
    }

    /**
     * 更新评估状态
     */
    private fun updateEvaluateStatus(sessionId: String, status: AsyncTaskStatus, error: String?) {
        try {
            sessionRepository.findBySessionId(sessionId)?.let { session ->
                session.evaluateStatus = status
                session.evaluateError = error
                sessionRepository.save(session)
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status)
            }
        } catch (e: Exception) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}", sessionId, status, e.message, e)
        }
    }
}

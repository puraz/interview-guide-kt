package interview.guide.modules.interview.listener

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.async.AbstractStreamConsumer
import interview.guide.common.constant.AsyncTaskStreamConstants
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.infrastructure.redis.RedisService
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.repository.InterviewSessionRepository
import interview.guide.modules.interview.service.AnswerEvaluationService
import interview.guide.modules.interview.service.InterviewPersistenceService
import org.redisson.api.stream.StreamMessageId
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
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
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val llmProviderRegistry: LlmProviderRegistry // LLM Provider 注册表
) : AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload>(redisService) {

    companion object {
        private val log = LoggerFactory.getLogger(EvaluateStreamConsumer::class.java)
    }

    /**
     * 评估任务载荷
     */
    data class EvaluatePayload(
        val sessionId: String // 面试会话ID
    )

    override fun taskDisplayName(): String = "评估" // 任务展示名称

    override fun streamKey(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY // Stream Key

    override fun groupName(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME // 消费者组

    override fun consumerPrefix(): String = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX // 消费者前缀

    override fun threadName(): String = "evaluate-consumer" // 线程名称

    override fun parsePayload(messageId: StreamMessageId, data: Map<String, String>): EvaluatePayload? {
        val sessionId = data[AsyncTaskStreamConstants.FIELD_SESSION_ID]
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId)
            return null
        }
        return EvaluatePayload(sessionId)
    }

    override fun payloadIdentifier(payload: EvaluatePayload): String = "sessionId=${payload.sessionId}" // 载荷标识

    override fun shouldSkip(payload: EvaluatePayload): Boolean {
        val session = sessionRepository.findBySessionId(payload.sessionId)
        return session == null || session.evaluateStatus == AsyncTaskStatus.COMPLETED
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

        val questions = objectMapper.readValue(
            session.questionsJson,
            object : TypeReference<MutableList<InterviewQuestionVo>>() {}
        )

        val answers = persistenceService.findAnswersBySessionId(sessionId)
        for (answer in answers) {
            val index = answer.questionIndex ?: continue
            if (index >= 0 && index < questions.size) {
                val question = questions[index]
                questions[index] = question.withAnswer(answer.userAnswer)
            }
        }

        // 获取 LLM 客户端
        val provider = session.llmProvider
        val chatClient: ChatClient = llmProviderRegistry.getChatClientOrDefault(provider)

        val resumeText = session.resume?.resumeText ?: ""
        val report = evaluationService.evaluateInterview(chatClient, sessionId, resumeText, questions)
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
            updateEvaluateStatus(
                sessionId,
                AsyncTaskStatus.FAILED,
                truncateError("重试入队失败: ${e.message}")
            )
        }
    }

    /**
     * 更新评估状态
     */
    private fun updateEvaluateStatus(sessionId: String, status: AsyncTaskStatus, error: String?) {
        try {
            val session = sessionRepository.findBySessionId(sessionId)
            if (session != null) {
                session.evaluateStatus = status
                session.evaluateError = error
                sessionRepository.save(session)
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status)
            }
        } catch (e: Exception) {
            log.error(
                "更新评估状态失败: sessionId={}, status={}, error={}",
                sessionId, status, e.message, e
            )
        }
    }
}

package interview.guide.modules.knowledgebase.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity
import interview.guide.modules.knowledgebase.model.QuestionGenStatus
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 知识库题目生成状态的事务边界。
 */
@Service
class QuestionGenerationStateService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val questionRepository: KnowledgeBaseQuestionRepository, // 题目仓库
    private val objectMapper: ObjectMapper // JSON 序列化工具
) {

    /**
     * 创建题目生成任务，状态置为 QUEUED。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun createTask(knowledgeBaseId: Long, config: QuestionGenerationConfig): QuestionGenStatusResponse {
        val kb = lockKnowledgeBase(knowledgeBaseId)
        if (kb.vectorStatus != VectorStatus.COMPLETED) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "知识库尚未完成向量化")
        }
        if (isActive(kb.questionGenStatus)) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "知识库问题正在生成中，请勿重复提交")
        }

        val now = LocalDateTime.now()
        kb.questionGenTaskId = UUID.randomUUID().toString()
        kb.questionGenStatus = QuestionGenStatus.QUEUED
        kb.questionGenConfig = writeConfig(config)
        kb.questionGenError = null
        kb.questionGenMessage = null
        kb.questionGenSavedCount = 0
        kb.questionGenSkippedCount = 0
        kb.questionGenUpdatedAt = now
        knowledgeBaseRepository.save(kb)
        return toResponse(kb, config)
    }

    /**
     * 查询题目生成状态。
     */
    @Transactional(readOnly = true)
    fun getStatus(knowledgeBaseId: Long): QuestionGenStatusResponse {
        val kb = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
        return toResponse(kb, readConfigOrNull(kb.questionGenConfig))
    }

    /**
     * 读取任务配置；任务ID不匹配时认为任务已失效。
     */
    @Transactional(readOnly = true)
    fun getConfig(knowledgeBaseId: Long, taskId: String): QuestionGenerationConfig {
        val kb = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
        if (taskId != kb.questionGenTaskId) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "题目生成任务已失效")
        }
        val config = readConfigOrNull(kb.questionGenConfig)
            ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "题目生成配置不存在")
        return config
    }

    /**
     * QUEUED → PROCESSING 原子领取。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun tryMarkProcessing(knowledgeBaseId: Long, taskId: String): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId) ?: return false
        if (!matches(kb, taskId, QuestionGenStatus.QUEUED)) {
            return false
        }
        kb.questionGenStatus = QuestionGenStatus.PROCESSING
        kb.questionGenError = null
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    /**
     * PROCESSING → QUEUED（重试前重置）。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun resetForRetry(knowledgeBaseId: Long, taskId: String): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId) ?: return false
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
            return false
        }
        kb.questionGenStatus = QuestionGenStatus.QUEUED
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    /**
     * 标记任务失败；已完成的任务不会被覆盖。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun markFailed(knowledgeBaseId: Long, taskId: String): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId)
        if (kb == null || taskId != kb.questionGenTaskId) {
            return false
        }
        if (kb.questionGenStatus == QuestionGenStatus.COMPLETED) {
            return false
        }
        kb.questionGenStatus = QuestionGenStatus.FAILED
        kb.questionGenError = SAFE_FAILURE_MESSAGE
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    /**
     * 替换旧题目并写入完成状态（同一事务）。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun replaceQuestionsAndComplete(
        knowledgeBaseId: Long,
        taskId: String,
        questions: List<KnowledgeBaseQuestionEntity>,
        skippedCount: Int
    ): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId) ?: return false
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
            return false
        }

        questions.forEach { question -> question.knowledgeBase = kb }
        questionRepository.deleteByKnowledgeBaseId(knowledgeBaseId)
        questionRepository.saveAll(questions)

        val savedCount = questions.size
        val message = if (skippedCount > 0) {
            String.format("已生成 %d 道题，跳过 %d 道重复题", savedCount, skippedCount)
        } else {
            String.format("已生成 %d 道题", savedCount)
        }
        kb.questionGenStatus = QuestionGenStatus.COMPLETED
        kb.questionGenError = null
        kb.questionGenMessage = message
        kb.questionGenSavedCount = savedCount
        kb.questionGenSkippedCount = skippedCount
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    /**
     * 恢复投递前原子推进等待中任务的更新时间。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun touchQueuedForRecovery(
        knowledgeBaseId: Long,
        taskId: String,
        threshold: LocalDateTime
    ): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId) ?: return false
        if (!matches(kb, taskId, QuestionGenStatus.QUEUED) ||
            !isStale(kb.questionGenUpdatedAt, threshold)
        ) {
            return false
        }
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    /**
     * 卡住的 PROCESSING 重置回 QUEUED。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun resetStaleProcessing(
        knowledgeBaseId: Long,
        taskId: String,
        threshold: LocalDateTime
    ): Boolean {
        val kb = lockKnowledgeBaseOrNull(knowledgeBaseId) ?: return false
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING) ||
            !isStale(kb.questionGenUpdatedAt, threshold)
        ) {
            return false
        }
        kb.questionGenStatus = QuestionGenStatus.QUEUED
        kb.questionGenUpdatedAt = LocalDateTime.now()
        return true
    }

    private fun lockKnowledgeBase(knowledgeBaseId: Long): KnowledgeBaseEntity {
        return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
    }

    private fun lockKnowledgeBaseOrNull(knowledgeBaseId: Long): KnowledgeBaseEntity? {
        return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId).orElse(null)
    }

    private fun matches(
        kb: KnowledgeBaseEntity?,
        taskId: String,
        expectedStatus: QuestionGenStatus
    ): Boolean {
        return kb != null && taskId == kb.questionGenTaskId && kb.questionGenStatus == expectedStatus
    }

    private fun isActive(status: QuestionGenStatus?): Boolean {
        return status == QuestionGenStatus.QUEUED || status == QuestionGenStatus.PROCESSING
    }

    private fun isStale(updatedAt: LocalDateTime?, threshold: LocalDateTime): Boolean {
        return updatedAt == null || updatedAt.isBefore(threshold)
    }

    private fun writeConfig(config: QuestionGenerationConfig): String {
        try {
            return objectMapper.writeValueAsString(config)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目生成配置失败", e)
        }
    }

    private fun readConfigOrNull(value: String?): QuestionGenerationConfig? {
        if (value.isNullOrBlank()) {
            return null
        }
        try {
            return objectMapper.readValue(value, QuestionGenerationConfig::class.java)
        } catch (e: JsonProcessingException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "解析题目生成配置失败", e)
        }
    }

    private fun toResponse(kb: KnowledgeBaseEntity, config: QuestionGenerationConfig?): QuestionGenStatusResponse {
        return QuestionGenStatusResponse(
            kb.id,
            kb.questionGenStatus,
            kb.questionGenTaskId,
            config,
            kb.questionGenSavedCount ?: 0,
            kb.questionGenSkippedCount ?: 0,
            kb.questionGenMessage,
            kb.questionGenError,
            kb.questionGenUpdatedAt
        )
    }

    companion object {
        const val SAFE_FAILURE_MESSAGE: String = "题目生成失败，请稍后重试"
    }
}

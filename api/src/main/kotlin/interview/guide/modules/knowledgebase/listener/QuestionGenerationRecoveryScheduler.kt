package interview.guide.modules.knowledgebase.listener

import interview.guide.modules.knowledgebase.model.QuestionGenStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.service.QuestionGenerationStateService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 恢复未成功投递或执行节点异常退出的题目生成任务。
 */
@Component
class QuestionGenerationRecoveryScheduler(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val stateService: QuestionGenerationStateService, // 生成状态事务服务
    private val producer: QuestionGenStreamProducer // 题目生成任务生产者
) {

    private val log = LoggerFactory.getLogger(QuestionGenerationRecoveryScheduler::class.java)

    /**
     * 定时恢复卡住的题目生成任务。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    fun recoverStaleTasks() {
        val now = LocalDateTime.now()
        recoverQueued(now.minusMinutes(QUEUED_STALE_MINUTES))
        recoverProcessing(now.minusMinutes(PROCESSING_STALE_MINUTES))
    }

    /**
     * 重新投递长时间停留在 QUEUED 的任务
     */
    private fun recoverQueued(threshold: LocalDateTime) {
        val tasks = knowledgeBaseRepository
            .findStaleQuestionGenerationTasks(QuestionGenStatus.QUEUED, threshold)
        for (task in tasks) {
            val taskId = task.questionGenTaskId
            val kbId = task.id
            if (taskId != null && kbId != null &&
                stateService.touchQueuedForRecovery(kbId, taskId, threshold)
            ) {
                producer.sendGenerateTask(kbId, taskId)
                log.info("重新投递等待中的题目生成任务: kbId={}, taskId={}", kbId, taskId)
            }
        }
    }

    /**
     * 重置并重新投递卡在 PROCESSING 的任务
     */
    private fun recoverProcessing(threshold: LocalDateTime) {
        val tasks = knowledgeBaseRepository
            .findStaleQuestionGenerationTasks(QuestionGenStatus.PROCESSING, threshold)
        for (task in tasks) {
            val taskId = task.questionGenTaskId
            val kbId = task.id
            if (taskId != null && kbId != null &&
                stateService.resetStaleProcessing(kbId, taskId, threshold)
            ) {
                producer.sendGenerateTask(kbId, taskId)
                log.warn("恢复卡住的题目生成任务: kbId={}, taskId={}", kbId, taskId)
            }
        }
    }

    companion object {
        private const val QUEUED_STALE_MINUTES: Long = 2
        private const val PROCESSING_STALE_MINUTES: Long = 20
    }
}

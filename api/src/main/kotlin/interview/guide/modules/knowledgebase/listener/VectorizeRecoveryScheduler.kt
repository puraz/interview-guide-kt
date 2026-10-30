package interview.guide.modules.knowledgebase.listener

import interview.guide.common.async.recovery.VectorizeRecoveryProperties
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 知识库向量化卡住任务恢复调度器（P1-07）。
 *
 * PENDING 超阈值（投递丢失）与 PROCESSING 超阈值且无心跳（消费者死亡）两种场景补投。
 * 恢复前通过条件更新原子推进 vector_updated_at，保证一个调度周期内同一实体只补投一次；
 * recovery_count 达到上限转 FAILED。外部投递在事务外完成。
 */
@Component
class VectorizeRecoveryScheduler(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val producer: VectorizeStreamProducer, // 向量化任务生产者
    private val properties: VectorizeRecoveryProperties // 恢复配置
) {

    private val log = LoggerFactory.getLogger(VectorizeRecoveryScheduler::class.java)

    /**
     * 定时扫描并恢复卡住的向量化任务。
     */
    @Scheduled(
        fixedDelayString = "\${app.async.recovery.vectorize.interval-ms:60000}",
        initialDelayString = "\${app.async.recovery.vectorize.interval-ms:60000}"
    )
    fun recoverStuckTasks() {
        if (!properties.enabled) {
            return
        }
        val now = LocalDateTime.now()
        val stalePending = knowledgeBaseRepository.findStaleByVectorStatus(
            VectorStatus.PENDING, now.minus(properties.pendingThreshold),
            PageRequest.of(0, properties.batchSize)
        )
        for (kb in stalePending) {
            val kbId = kb.id ?: continue
            recover(kbId, true)
        }
        val staleProcessing = knowledgeBaseRepository.findStaleByVectorStatus(
            VectorStatus.PROCESSING, now.minus(properties.processingThreshold),
            PageRequest.of(0, properties.batchSize)
        )
        for (kb in staleProcessing) {
            val kbId = kb.id ?: continue
            // PROCESSING 无进展：先条件重置回 PENDING，再走统一的补投路径
            val reset = knowledgeBaseRepository.resetStaleVectorProcessing(
                kbId, LocalDateTime.now().minus(properties.processingThreshold), LocalDateTime.now()
            )
            if (reset == 1) {
                recover(kbId, false)
            }
        }
    }

    /**
     * 补投单个任务
     *
     * @param kbId 知识库ID
     * @param alreadyPending 是否仍处于 PENDING（需要先原子推进时间）
     */
    private fun recover(kbId: Long, alreadyPending: Boolean) {
        val now = LocalDateTime.now()
        if (alreadyPending) {
            val touched = knowledgeBaseRepository.touchQueuedVectorForRecovery(
                kbId, LocalDateTime.now().minus(properties.pendingThreshold), now
            )
            if (touched != 1) {
                return // 已被其他路径推进或恢复
            }
        }
        knowledgeBaseRepository.incrementVectorRecoveryCount(kbId)
        val kb = knowledgeBaseRepository.findById(kbId).orElse(null) ?: return
        if (kb.vectorRecoveryCount >= properties.maxRecoveryCount) {
            knowledgeBaseRepository.failVectorIfPending(
                kbId,
                "自动恢复次数达到上限（" + kb.vectorRecoveryCount + "），请手动重试",
                LocalDateTime.now()
            )
            log.warn(
                "向量化任务自动恢复达到上限转 FAILED: kbId={}, recoveryCount={}",
                kbId, kb.vectorRecoveryCount
            )
            return
        }
        val sent = producer.sendVectorizeTask(kbId)
        log.info(
            "向量化卡住任务已补投: kbId={}, sent={}, recoveryCount={}",
            kbId, sent, kb.vectorRecoveryCount
        )
    }
}

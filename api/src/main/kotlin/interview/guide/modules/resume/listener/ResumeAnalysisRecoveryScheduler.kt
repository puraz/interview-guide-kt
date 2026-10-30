package interview.guide.modules.resume.listener

import interview.guide.common.async.recovery.ResumeAnalysisRecoveryProperties
import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.resume.repository.ResumeRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 简历分析卡住任务恢复调度器（P1-07）。语义与 VectorizeRecoveryScheduler 一致。
 */
@Component
class ResumeAnalysisRecoveryScheduler(
    private val resumeRepository: ResumeRepository, // 简历仓库
    private val producer: AnalyzeStreamProducer, // 分析任务生产者
    private val properties: ResumeAnalysisRecoveryProperties // 恢复调度配置
) {

    companion object {
        private val log = LoggerFactory.getLogger(ResumeAnalysisRecoveryScheduler::class.java)
    }

    /**
     * 扫描并恢复卡住的简历分析任务：
     * PENDING 超阈值直接补投；PROCESSING 无心跳超阈值先条件重置回 PENDING 再补投。
     */
    @Scheduled(
        fixedDelayString = "\${app.async.recovery.resume-analyze.interval-ms:60000}",
        initialDelayString = "\${app.async.recovery.resume-analyze.interval-ms:60000}"
    )
    fun recoverStuckTasks() {
        if (!properties.enabled) {
            return
        }
        val now = LocalDateTime.now()
        val stalePending = resumeRepository.findStaleByAnalyzeStatus(
            AsyncTaskStatus.PENDING, now.minus(properties.pendingThreshold),
            PageRequest.of(0, properties.batchSize)
        )
        for (resume in stalePending) {
            resume.id?.let { recover(it, true) }
        }
        val staleProcessing = resumeRepository.findStaleByAnalyzeStatus(
            AsyncTaskStatus.PROCESSING, now.minus(properties.processingThreshold),
            PageRequest.of(0, properties.batchSize)
        )
        for (resume in staleProcessing) {
            val resumeId = resume.id ?: continue
            val reset = resumeRepository.resetStaleAnalyzeProcessing(
                resumeId, LocalDateTime.now().minus(properties.processingThreshold), LocalDateTime.now()
            )
            if (reset == 1) {
                recover(resumeId, false)
            }
        }
    }

    /**
     * 补投单个卡住任务
     *
     * @param resumeId       简历ID // 任务目标
     * @param alreadyPending 是否已处于 PENDING // true 时先原子推进时间并校验
     */
    private fun recover(resumeId: Long, alreadyPending: Boolean) {
        val now = LocalDateTime.now()
        if (alreadyPending) {
            val touched = resumeRepository.touchQueuedAnalyzeForRecovery(
                resumeId, LocalDateTime.now().minus(properties.pendingThreshold), now
            )
            if (touched != 1) {
                return
            }
        }
        resumeRepository.incrementAnalyzeRecoveryCount(resumeId)
        val resume = resumeRepository.findById(resumeId).orElse(null)
        if (resume == null) {
            return
        }
        if (resume.analyzeRecoveryCount >= properties.maxRecoveryCount) {
            resumeRepository.failAnalyzeIfPending(
                resumeId,
                "自动恢复次数达到上限（" + resume.analyzeRecoveryCount + "），请手动重试",
                LocalDateTime.now()
            )
            log.warn("简历分析任务自动恢复达到上限转 FAILED: resumeId={}, recoveryCount={}",
                resumeId, resume.analyzeRecoveryCount)
            return
        }
        val sent = producer.sendAnalyzeTask(resumeId)
        log.info("简历分析卡住任务已补投: resumeId={}, sent={}, recoveryCount={}",
            resumeId, sent, resume.analyzeRecoveryCount)
    }
}

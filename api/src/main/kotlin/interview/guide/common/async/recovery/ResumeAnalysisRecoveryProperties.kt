package interview.guide.common.async.recovery

import interview.guide.common.async.AsyncTaskRecoveryProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

/**
 * 简历分析卡住任务恢复配置。
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.async.recovery.resume-analyze")
class ResumeAnalysisRecoveryProperties : AsyncTaskRecoveryProperties()

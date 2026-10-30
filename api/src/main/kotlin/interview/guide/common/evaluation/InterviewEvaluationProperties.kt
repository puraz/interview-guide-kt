package interview.guide.common.evaluation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 面试评估配置属性
 */
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
class InterviewEvaluationProperties {

    /** 每批评估题目数量 */
    var batchSize: Int = 8

    /**
     * 批次失败后按问答组二分重试的总开关；关闭即恢复整批降级行为。
     */
    var fallbackSplitEnabled: Boolean = true

    /**
     * 一次评估共享的额外模型调用预算（只计批次重试，不计首轮与二次汇总）。
     */
    var fallbackMaxExtraCalls: Int = 6

    /**
     * 可二分拆分的最小组数；小于该值按不可拆处理（整段再试一次）。
     */
    var fallbackMinGroups: Int = 2

    /** 批次评估系统提示词路径 */
    var systemPromptPath: String = "classpath:prompts/interview-evaluation-system.st"

    /** 批次评估用户提示词路径 */
    var userPromptPath: String = "classpath:prompts/interview-evaluation-user.st"

    /** 二次汇总系统提示词路径 */
    var summarySystemPromptPath: String = "classpath:prompts/interview-evaluation-summary-system.st"

    /** 二次汇总用户提示词路径 */
    var summaryUserPromptPath: String = "classpath:prompts/interview-evaluation-summary-user.st"
}

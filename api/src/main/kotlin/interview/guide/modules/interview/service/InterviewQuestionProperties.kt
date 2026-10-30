package interview.guide.modules.interview.service

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 面试出题配置属性
 */
@Component
@ConfigurationProperties(prefix = "app.interview")
class InterviewQuestionProperties {

    /** 每道主问题追问数量 */
    var followUpCount: Int = 1

    /** 方向题系统提示词路径 */
    var questionSystemPromptPath: String = "classpath:prompts/interview-question-skill-system.st"

    /** 方向题用户提示词路径 */
    var questionUserPromptPath: String = "classpath:prompts/interview-question-skill-user.st"

    /** 简历题系统提示词路径 */
    var resumeQuestionSystemPromptPath: String = "classpath:prompts/interview-question-resume-system.st"

    /** 简历题用户提示词路径 */
    var resumeQuestionUserPromptPath: String = "classpath:prompts/interview-question-resume-user.st"
}

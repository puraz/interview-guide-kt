package interview.guide.modules.resume.service

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 简历分析提示词配置
 */
@Component
@ConfigurationProperties(prefix = "app.resume.analysis")
class ResumeAnalysisProperties {

    /**
     * 简历分析系统提示词路径
     */
    var systemPromptPath: String = "classpath:prompts/resume-analysis-system.st"

    /**
     * 简历分析用户提示词路径
     */
    var userPromptPath: String = "classpath:prompts/resume-analysis-user.st"
}

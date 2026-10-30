package interview.guide.common.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * spring-ai-agent-utils 工具配置属性（对应 app.ai.agent-utils.*）
 */
@Component
@ConfigurationProperties(prefix = "app.ai.agent-utils")
class AgentUtilsProperties {

    var skillsRoot: String = "classpath:skills" // skills 根目录（支持 classpath: 前缀）
}

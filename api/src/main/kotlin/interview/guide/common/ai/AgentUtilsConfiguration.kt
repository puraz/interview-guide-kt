package interview.guide.common.ai

import org.slf4j.LoggerFactory
import org.springaicommunity.agent.tools.SkillsTool
import org.springframework.ai.tool.ToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

/**
 * spring-ai-agent-utils 工具配置。
 * 当前先接入 SkillsTool，复用 resources/skills/{skillId}/SKILL.md。
 */
@Configuration
class AgentUtilsConfiguration(
    private val resourceLoader: ResourceLoader, // 资源加载器
    private val agentUtilsProperties: AgentUtilsProperties // agent-utils 配置属性
) {

    private val log = LoggerFactory.getLogger(AgentUtilsConfiguration::class.java)

    /**
     * 注册面试技能工具回调
     *
     * @return ToolCallback // SkillsTool 实例，供 ChatClient 默认工具使用
     */
    @Bean("interviewSkillsToolCallback")
    fun interviewSkillsToolCallback(): ToolCallback {
        val configuredSkillsRoot = agentUtilsProperties.skillsRoot
        val normalizedSkillsRoot = normalizeSkillsRoot(configuredSkillsRoot)
        val skillsRootResource: Resource = resourceLoader.getResource(normalizedSkillsRoot)

        if (!skillsRootResource.exists()) {
            throw IllegalStateException("未找到 skills 根目录，请检查配置: $normalizedSkillsRoot")
        }

        log.info("AgentUtils SkillsTool 已启用，skillsRoot={}, configured={}", normalizedSkillsRoot, configuredSkillsRoot)

        return SkillsTool.builder()
            .addSkillsResource(skillsRootResource)
            .build()
    }

    /**
     * 归一化 skills 根目录：去空白、统一分隔符、去掉 SKILL.md 后缀与通配符、去掉末尾斜杠
     *
     * @param raw 配置的原始路径 // 可为 null/空白
     * @return 归一化后的资源路径 // 默认 classpath:skills
     */
    private fun normalizeSkillsRoot(raw: String?): String {
        if (raw == null || raw.isBlank()) {
            return "classpath:skills"
        }

        var normalized = raw.trim()
        normalized = normalized.replace('\\', '/')

        if (normalized.endsWith("/SKILL.md")) {
            normalized = normalized.substring(0, normalized.length - "/SKILL.md".length)
        }

        val wildcardIndex = normalized.indexOf('*')
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex)
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }

        return if (normalized.isBlank()) "classpath:skills" else normalized
    }
}

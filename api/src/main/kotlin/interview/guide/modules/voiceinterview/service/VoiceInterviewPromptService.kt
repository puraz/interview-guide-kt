package interview.guide.modules.voiceinterview.service

import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.ai.PromptSecurityConstants
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 语音面试 Prompt 构建服务
 */
@Service
class VoiceInterviewPromptService(
    private val promptSanitizer: PromptSanitizer // Prompt 注入净化工具
) {

    private val log = LoggerFactory.getLogger(VoiceInterviewPromptService::class.java)

    companion object {
        /**
         * 语音面试输出约束（追加到 system prompt）
         */
        private val VOICE_RESPONSE_CONSTRAINTS = """
            【语音面试输出约束】
            1. 每轮只问 1 个主问题，必要时最多补 1 个短追问。
            2. 总长度控制在 2-4 句，避免长段落、列表、Markdown、代码块。
            3. 不要重复开场白，不要复述上一轮已问过的完整问题。
            4. 若候选人回答过短或含糊，直接追问一个具体的技术细节或给出提示引导，不要简单确认后停止。
            5. 当候选人明确要求换题时，立即切换到新的技术方向，不要停留在当前话题。
            6. 语气简洁直接，适配口语对话。
        """.trimIndent() + "\n"

        /**
         * 技能角色加载指令模板（%s 为技能 ID）
         */
        private val SKILL_TOOL_INSTRUCTION = """
            你是一位 %s 方向的面试官。
            如果尚未加载完整的角色设定，请调用 Skill 工具（command: %s）加载该技能的 SKILL.md。
            工具输出包含完整的面试官角色和出题规则，后续对话应基于该角色进行。
        """.trimIndent() + "\n"
    }

    /**
     * 构建带简历上下文的 system prompt
     *
     * @param skillId 技能模板 ID // 用于加载面试官角色
     * @param resumeText 简历解析文本 // 可为 null
     * @return 完整 system prompt // 含角色、语音约束、简历与防注入指令
     */
    fun generateSystemPromptWithContext(skillId: String?, resumeText: String?): String {
        val prompt = StringBuilder()

        if (!skillId.isNullOrBlank()) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId))
        }

        prompt.append("\n\n").append(VOICE_RESPONSE_CONSTRAINTS)

        if (!resumeText.isNullOrEmpty()) {
            val safeResume = promptSanitizer.sanitize(resumeText)
            prompt.append("\n\n【实时语音面试 - 候选人简历内容】\n")
                .append("你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。\n\n")
                .append("【简历解析文本】\n")
                .append(promptSanitizer.wrapWithDelimiters("resume", safeResume))
        }

        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION)
        return prompt.toString()
    }
}

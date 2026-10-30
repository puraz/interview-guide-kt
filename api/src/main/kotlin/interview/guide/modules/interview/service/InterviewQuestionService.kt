package interview.guide.modules.interview.service

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.ai.PromptSecurityConstants
import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.constant.CommonConstants.InterviewDefaults
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.interview.model.HistoricalQuestion
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.skill.InterviewSkillService
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
class InterviewQuestionService(
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用封装
    private val skillService: InterviewSkillService, // 面试方向 Skill 服务
    properties: InterviewQuestionProperties, // 出题配置
    resourceLoader: ResourceLoader, // classpath 资源加载器
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val promptSanitizer: PromptSanitizer // Prompt 注入净化工具
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewQuestionService::class.java)

        private const val DEFAULT_QUESTION_TYPE: String = "GENERAL" // 默认题目类型
        private const val MAX_FOLLOW_UP_COUNT: Int = 2 // 最大追问数
        private const val RESUME_QUESTION_RATIO: Double = 0.6 // 简历题占比

        private val GENERIC_MODE_SYSTEM_APPEND: String = "\n\n" + """
            # 通用面试模式
            本次面试无候选人简历，请出该方向的标准面试题。
            - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
            - 问题表述应与简历无关，直接考察该方向的技术能力
        """.trimIndent()

        private val DIFFICULTY_DESCRIPTIONS: Map<String, String> = mapOf(
            "junior" to "校招/0-1年经验。考察基础概念和简单应用。",
            "mid" to "1-3年经验。考察原理理解和实战经验。",
            "senior" to "3年+经验。考察架构设计和深度调优。"
        )

        private val GENERIC_FALLBACK_QUESTIONS: Array<Array<String>> = arrayOf(
            arrayOf("请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"),
            arrayOf("你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"),
            arrayOf("请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"),
            arrayOf("你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"),
            arrayOf("描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"),
            arrayOf("你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力")
        )
    }

    private val skillSystemPromptTemplate: PromptTemplate =
        loadTemplate(resourceLoader, properties.questionSystemPromptPath) // 方向题系统提示词
    private val skillUserPromptTemplate: PromptTemplate =
        loadTemplate(resourceLoader, properties.questionUserPromptPath) // 方向题用户提示词
    private val resumeSystemPromptTemplate: PromptTemplate =
        loadTemplate(resourceLoader, properties.resumeQuestionSystemPromptPath) // 简历题系统提示词
    private val resumeUserPromptTemplate: PromptTemplate =
        loadTemplate(resourceLoader, properties.resumeQuestionUserPromptPath) // 简历题用户提示词
    private val outputConverter: BeanOutputConverter<QuestionListDTO> =
        BeanOutputConverter(QuestionListDTO::class.java) // 结构化输出转换器

    // Java HEAD 使用虚拟线程（Java 21+）；Java 17 目标下改用固定大小守护线程池，
    // 简历题/方向题并行度固定为 2，语义等价且不阻塞容器关闭。
    private val questionExecutor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        val thread = Thread(r, "interview-question-generator")
        thread.isDaemon = true
        thread
    }

    private val followUpCount: Int = properties.followUpCount.coerceIn(0, MAX_FOLLOW_UP_COUNT) // 追问数量

    /**
     * 结构化输出的问题列表 DTO
     */
    private data class QuestionListDTO(
        val questions: List<QuestionDTO>? = null // 问题列表
    )

    /**
     * 结构化输出的单题 DTO
     */
    private data class QuestionDTO(
        val question: String? = null, // 题面
        val type: String? = null, // 分类 key
        val category: String? = null, // 展示标签
        val topicSummary: String? = null, // 知识点摘要
        val followUps: List<String>? = null // 追问列表
    )

    private fun loadTemplate(resourceLoader: ResourceLoader, location: String): PromptTemplate {
        return PromptTemplate(resourceLoader.getResource(location).getContentAsString(StandardCharsets.UTF_8))
    }

    @PreDestroy
    fun destroy() {
        questionExecutor.shutdownNow()
    }

    /**
     * 基于 Skill 生成面试问题
     */
    fun generateQuestionsBySkill(
        llmProvider: String?,
        skillId: String,
        difficulty: String?,
        resumeText: String?,
        questionCount: Int,
        historicalQuestions: List<HistoricalQuestion>?,
        customCategories: List<CategoryDTO>?,
        jdText: String?
    ): List<InterviewQuestionVo> {

        val skill = resolveSkill(skillId, customCategories, jdText)
        val difficultyDesc = resolveDifficulty(difficulty)
        val questionChatClient = llmProviderRegistry.getPlainChatClient(llmProvider)

        val hasResume = !resumeText.isNullOrBlank()
        val historicalSection = buildHistoricalSection(historicalQuestions)
        if (!hasResume) {
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount, historicalSection)
        }

        val resumeCount = Math.max(1, Math.round(questionCount * RESUME_QUESTION_RATIO).toInt())
        val directionCount = questionCount - resumeCount

        log.info(
            "并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount
        )

        val resumeFuture: CompletableFuture<List<InterviewQuestionVo>> = CompletableFuture.supplyAsync(
            {
                generateResumeQuestions(
                    questionChatClient, resumeText ?: "", resumeCount, skill,
                    difficultyDesc, historicalSection
                )
            },
            questionExecutor
        )

        val directionFuture: CompletableFuture<List<InterviewQuestionVo>> = CompletableFuture.supplyAsync(
            { generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount, historicalSection) },
            questionExecutor
        )

        val resumeQuestions: List<InterviewQuestionVo>
        val directionQuestions: List<InterviewQuestionVo>
        try {
            resumeQuestions = resumeFuture.join()
        } catch (e: CompletionException) {
            log.error("简历题生成失败，降级为全方向题", ErrorLogSanitizer.forLogging(e.cause))
            directionFuture.cancel(true)
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount, historicalSection)
        }

        try {
            directionQuestions = directionFuture.join()
        } catch (e: CompletionException) {
            log.error("方向题生成失败，降级为全简历题", ErrorLogSanitizer.forLogging(e.cause))
            if (resumeQuestions.isEmpty()) {
                return generateFallbackQuestions(skill, questionCount)
            }
            return resumeQuestions
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题均为空，回退到默认问题")
            return generateFallbackQuestions(skill, questionCount)
        }

        val merged = mergeQuestionBatches(resumeQuestions, directionQuestions)
        log.info(
            "并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size, directionQuestions.size, merged.size
        )
        return merged
    }

    /**
     * 生成简历相关题目
     */
    private fun generateResumeQuestions(
        questionClient: ChatClient,
        resumeText: String,
        questionCount: Int,
        skill: SkillDTO,
        difficultyDesc: String,
        historicalSection: String
    ): List<InterviewQuestionVo> {
        try {
            val variables = HashMap<String, Any>()
            variables["questionCount"] = questionCount
            variables["followUpCount"] = followUpCount
            variables["skillName"] = skill.name ?: ""
            variables["skillDescription"] = skill.description ?: ""
            variables["difficultyDescription"] = difficultyDesc
            variables["resumeText"] = resumeText
            variables["historicalSection"] = historicalSection

            val systemPrompt = resumeSystemPromptTemplate.render() +
                buildSkillPersonaSection(skill) +
                "\n\n" + outputConverter.format
            val userPrompt = resumeUserPromptTemplate.render(variables)

            val dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log
            )

            var questions = convertToQuestions(dto)
            questions = capToMainCount(questions, questionCount)
            log.info(
                "简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.count { q -> !q.isFollowUp }
            )
            return questions
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("简历题生成异常: {}", ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e))
            throw e
        }
    }

    /**
     * 仅生成方向题（Skill 驱动）
     */
    private fun generateDirectionOnly(
        questionClient: ChatClient,
        skill: SkillDTO,
        difficultyDesc: String,
        questionCount: Int,
        historicalSection: String
    ): List<InterviewQuestionVo> {
        val allocation = skillService.calculateAllocation(skill.categories, questionCount)
        val allocationTable = skillService.buildAllocationDescription(allocation, skill.categories)

        log.info("方向题生成: skill={}, total={}, allocation={}", skill.id, questionCount, allocation)

        try {
            val variables = HashMap<String, Any>()
            variables["questionCount"] = questionCount
            variables["followUpCount"] = followUpCount
            variables["difficultyDescription"] = difficultyDesc
            variables["skillName"] = skill.name ?: ""
            variables["skillDescription"] = skill.description ?: ""
            variables["allocationTable"] = allocationTable
            variables["historicalSection"] = historicalSection
            variables["referenceSection"] = skillService.buildReferenceSection(skill, allocation)
            variables["jdSection"] = buildJdSection(skill.sourceJd)

            val systemPrompt = skillSystemPromptTemplate.render() +
                buildSkillPersonaSection(skill) +
                GENERIC_MODE_SYSTEM_APPEND +
                outputConverter.format
            val userPrompt = skillUserPromptTemplate.render(variables)

            val dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log
            )

            var questions = convertToQuestions(dto)
            if (questions.count { q -> !q.isFollowUp } == 0) {
                log.warn("方向题返回空题单，回退到默认问题")
                return generateFallbackQuestions(skill, questionCount)
            }
            questions = capToMainCount(questions, questionCount)
            log.info(
                "方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.count { q -> !q.isFollowUp }
            )
            return questions
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "方向题生成失败，回退到默认问题: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e)
            )
            return generateFallbackQuestions(skill, questionCount)
        }
    }

    /**
     * 合并简历题与方向题，并重新编号
     */
    private fun mergeQuestionBatches(
        first: List<InterviewQuestionVo>,
        second: List<InterviewQuestionVo>
    ): List<InterviewQuestionVo> {
        if (second.isEmpty()) {
            return first
        }
        if (first.isEmpty()) {
            return second
        }
        val offset = first.size
        val merged = ArrayList<InterviewQuestionVo>(first)
        for (q in second) {
            val newIndex = q.questionIndex + offset
            val newParent = q.parentQuestionIndex?.let { it + offset }
            merged.add(
                InterviewQuestionVo.create(
                    newIndex, q.question, q.type, q.category,
                    q.topicSummary, q.isFollowUp, newParent
                )
            )
        }
        return merged
    }

    /**
     * 解析目标 Skill（自定义 JD 面试时动态构建）
     */
    private fun resolveSkill(
        skillId: String,
        customCategories: List<CategoryDTO>?,
        jdText: String?
    ): SkillDTO {
        if (InterviewSkillService.CUSTOM_SKILL_ID == skillId && !customCategories.isNullOrEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText ?: "")
        }
        return skillService.getSkill(skillId)
    }

    /**
     * 解析难度描述
     */
    private fun resolveDifficulty(difficulty: String?): String {
        return DIFFICULTY_DESCRIPTIONS[difficulty ?: InterviewDefaults.DIFFICULTY]
            ?: DIFFICULTY_DESCRIPTIONS[InterviewDefaults.DIFFICULTY]
            ?: ""
    }

    /**
     * 结构化结果转问题列表（展开追问并重新编号）
     */
    private fun convertToQuestions(dto: QuestionListDTO?): List<InterviewQuestionVo> {
        val questions = ArrayList<InterviewQuestionVo>()
        var index = 0

        val dtoQuestions = dto?.questions ?: return questions

        for (q in dtoQuestions) {
            if (q.question.isNullOrBlank()) {
                continue
            }
            val type = q.type?.takeIf { it.isNotBlank() }?.uppercase() ?: DEFAULT_QUESTION_TYPE
            val mainQuestionIndex = index
            questions.add(
                InterviewQuestionVo.create(index++, q.question ?: "", type, q.category, q.topicSummary, false, null)
            )

            val followUps = sanitizeFollowUps(q.followUps)
            for (i in followUps.indices) {
                questions.add(
                    InterviewQuestionVo.create(
                        index++, followUps[i], type,
                        buildFollowUpCategory(q.category, i + 1), null, true, mainQuestionIndex
                    )
                )
            }
        }

        return questions
    }

    /**
     * 将问题列表截断到指定的主问题数量（AI 多生时截断，少生时保留原样并记录警告）。
     */
    private fun capToMainCount(
        questions: List<InterviewQuestionVo>,
        maxMainCount: Int
    ): List<InterviewQuestionVo> {
        val currentMainCount = questions.count { q -> !q.isFollowUp }

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount)
            }
            return questions
        }

        val capped = ArrayList<InterviewQuestionVo>()
        var mainSeen = 0
        for (q in questions) {
            if (!q.isFollowUp) {
                mainSeen++
            }
            if (mainSeen > maxMainCount) {
                break
            }
            capped.add(q)
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount)
        return capped
    }

    /**
     * 生成兜底题目（按分类轮转或通用题库）
     */
    private fun generateFallbackQuestions(skill: SkillDTO?, count: Int): List<InterviewQuestionVo> {
        val categories = skill?.categories ?: emptyList()
        val questions = ArrayList<InterviewQuestionVo>()
        var index = 0

        if (categories.isNotEmpty()) {
            var generated = 0
            while (generated < count) {
                val cat = categories[generated % categories.size]
                val question = "请谈谈你在\"" + cat.label + "\"方向的技术理解和实践经验。"
                questions.add(
                    InterviewQuestionVo.create(index++, question, cat.key, cat.label, null, false, null)
                )
                val mainIndex = index - 1
                for (j in 0 until followUpCount) {
                    questions.add(
                        InterviewQuestionVo.create(
                            index++, buildDefaultFollowUp(question, j + 1),
                            cat.key, buildFollowUpCategory(cat.label, j + 1), null, true, mainIndex
                        )
                    )
                }
                generated++
            }
            return questions
        }

        for (i in 0 until Math.min(count, GENERIC_FALLBACK_QUESTIONS.size)) {
            val q = GENERIC_FALLBACK_QUESTIONS[i]
            questions.add(InterviewQuestionVo.create(index++, q[0], q[1], q[2], null, false, null))
            val mainIndex = index - 1
            for (j in 0 until followUpCount) {
                questions.add(
                    InterviewQuestionVo.create(
                        index++, buildDefaultFollowUp(q[0], j + 1),
                        q[1], buildFollowUpCategory(q[2], j + 1), null, true, mainIndex
                    )
                )
            }
        }
        return questions
    }

    /**
     * 构建历史提问区（按分类聚合知识点摘要）
     */
    private fun buildHistoricalSection(historicalQuestions: List<HistoricalQuestion>?): String {
        if (historicalQuestions.isNullOrEmpty()) {
            return "暂无历史提问"
        }

        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (hq in historicalQuestions) {
            val type = hq.type?.takeIf { it.isNotBlank() } ?: DEFAULT_QUESTION_TYPE
            var summary = hq.topicSummary
            if (summary.isNullOrBlank()) {
                val q = hq.question
                summary = if (q.length > 30) q.substring(0, 30) + "…" else q
            }
            grouped.getOrPut(type) { ArrayList() }.add(summary ?: "")
        }

        val sb = StringBuilder("已考过的知识点（避免重复出题）：\n")
        for ((type, summaries) in grouped) {
            sb.append("- ").append(type).append(": ")
            sb.append(summaries.joinToString(", "))
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 构建 JD 区（带注入防护）
     */
    private fun buildJdSection(sourceJd: String?): String {
        if (sourceJd.isNullOrBlank()) {
            return ""
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd))
    }

    /**
     * 构建 Skill Persona 区
     */
    private fun buildSkillPersonaSection(skill: SkillDTO?): String {
        val persona = skill?.persona
        if (persona.isNullOrBlank()) {
            return ""
        }
        return "\n\n# Skill Persona\n" +
            "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n" +
            promptSanitizer.wrapWithDelimiters("skill_persona", persona)
    }

    /**
     * 清洗并限制追问数量
     */
    private fun sanitizeFollowUps(followUps: List<String>?): List<String> {
        if (followUpCount == 0 || followUps.isNullOrEmpty()) {
            return emptyList()
        }
        return followUps
            .filter { !it.isNullOrBlank() }
            .map { it.trim() }
            .take(followUpCount)
    }

    /**
     * 构建追问展示标签
     */
    private fun buildFollowUpCategory(category: String?, order: Int): String {
        val base = if (category.isNullOrBlank()) "追问" else category
        return "$base（追问$order）"
    }

    /**
     * 构建默认追问文案
     */
    private fun buildDefaultFollowUp(mainQuestion: String, order: Int): String {
        if (order == 1) {
            return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。"
        }
        return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？"
    }
}

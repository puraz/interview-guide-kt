package com.example.business.service

import com.example.business.service.InterviewSessionService.InterviewQuestionVo
import com.example.framework.ai.chat.AiChatClient
import com.example.framework.ai.chat.AiChatMessage
import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatRole
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.example.framework.core.utils.stripCodeFences
import com.fasterxml.jackson.core.type.TypeReference
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import kotlin.math.max

/**
 * 面试问题生成服务
 *
 * 负责根据简历内容生成面试问题，并按规则补充追问。
 */
@Service
class InterviewQuestionService(
    private val aiChatClient: AiChatClient,
    @Value("classpath:prompts/interview-question-system.st")
    private val systemPromptResource: Resource,
    @Value("classpath:prompts/interview-question-user.st")
    private val userPromptResource: Resource,
    @Value("\${app.interview.follow-up-count:1}")
    private val followUpCount: Int
) {
    private val logger = LoggerFactory.getLogger(InterviewQuestionService::class.java)
    private val systemPrompt: String = readResource(systemPromptResource)
    private val userPromptTemplate: String = readResource(userPromptResource)
    private val safeFollowUpCount: Int = followUpCount.coerceIn(0, 2)

    /**
     * 生成面试问题
     *
     * @param resumeText 简历文本内容
     * @param questionCount 题目数量
     * @param historicalQuestions 历史问题列表
     * @return 面试问题列表
     */
    fun generateQuestions(
        resumeText: String,
        questionCount: Int,
        historicalQuestions: List<String>?
    ): List<InterviewQuestionVo> {
        if (questionCount < 3 || questionCount > 20) {
            throw BusinessException("题目数量范围为 3-20")
        }

        val distribution = calculateDistribution(questionCount)
        val variables = mapOf(
            "questionCount" to questionCount,
            "projectCount" to distribution.project,
            "mysqlCount" to distribution.mysql,
            "redisCount" to distribution.redis,
            "javaBasicCount" to distribution.javaBasic,
            "javaCollectionCount" to distribution.javaCollection,
            "javaConcurrentCount" to distribution.javaConcurrent,
            "springCount" to distribution.spring,
            "followUpCount" to safeFollowUpCount,
            "resumeText" to resumeText,
            "historicalQuestions" to if (historicalQuestions.isNullOrEmpty()) "暂无历史提问" else historicalQuestions.joinToString("\n")
        )

        val userPrompt = renderTemplate(userPromptTemplate, variables)

        val request = AiChatRequest(
            messages = listOf(
                AiChatMessage(role = AiChatRole.SYSTEM, content = systemPrompt), // 系统提示词，定义角色与输出约束
                AiChatMessage(role = AiChatRole.USER, content = userPrompt) // 用户提示词，传入简历与数量
            )
        )

        val result = aiChatClient.chat(request)
        if (!result.success || result.content.isNullOrBlank()) {
            throw BusinessException("面试问题生成失败：${result.errorMessage ?: "AI返回空内容"}")
        }

        val cleaned = result.content.stripCodeFences() // 去除代码块标记，避免JSON解析失败
        val dto = JSONUtil.parseObject(cleaned, object : TypeReference<QuestionListDto>() {})
            ?: throw BusinessException("面试问题生成失败：结果解析失败")

        val questions = convertToQuestions(dto)
        if (questions.isEmpty()) {
            throw BusinessException("面试问题生成失败：未生成任何问题")
        }

        logger.info("面试问题生成完成，主问题数={}, 总问题数={}", dto.questions?.size ?: 0, questions.size)
        return questions
    }

    private fun convertToQuestions(dto: QuestionListDto): List<InterviewQuestionVo> {
        val questions = mutableListOf<InterviewQuestionVo>()
        var index = 0

        val source = dto.questions ?: emptyList()
        for (item in source) {
            val questionText = item.question?.trim().orEmpty()
            if (questionText.isBlank()) {
                continue
            }
            val type = normalizeQuestionType(item.type)
            val category = item.category?.trim().orEmpty()
            val mainIndex = index
            questions.add(
                InterviewQuestionVo(
                    questionIndex = index++, // 问题索引
                    question = questionText, // 问题内容
                    type = type, // 问题类型
                    category = category, // 问题类别
                    userAnswer = null, // 用户回答
                    score = null, // 单题得分
                    feedback = null, // 单题反馈
                    isFollowUp = false, // 是否追问
                    parentQuestionIndex = null // 追问关联的主问题索引
                )
            )

            val followUps = sanitizeFollowUps(item.followUps)
            for (i in followUps.indices) {
                questions.add(
                    InterviewQuestionVo(
                        questionIndex = index++, // 追问索引
                        question = followUps[i], // 追问内容
                        type = type, // 追问类型与主问题一致
                        category = buildFollowUpCategory(category, i + 1), // 追问类别
                        userAnswer = null, // 用户回答
                        score = null, // 单题得分
                        feedback = null, // 单题反馈
                        isFollowUp = true, // 是否追问
                        parentQuestionIndex = mainIndex // 关联主问题索引
                    )
                )
            }
        }
        return questions
    }

    private fun normalizeQuestionType(rawType: String?): String {
        val type = rawType?.trim()?.uppercase().orEmpty()
        if (type.isBlank()) {
            throw BusinessException("面试问题生成失败：问题类型为空")
        }
        if (!ALLOWED_TYPES.contains(type)) {
            throw BusinessException("面试问题生成失败：无效的问题类型 $type")
        }
        return type
    }

    private fun sanitizeFollowUps(followUps: List<String>?): List<String> {
        if (followUps.isNullOrEmpty() || safeFollowUpCount <= 0) {
            return emptyList()
        }
        return followUps.map { it.trim() }
            .filter { it.isNotBlank() }
            .take(safeFollowUpCount)
    }

    private fun buildFollowUpCategory(category: String, index: Int): String {
        val prefix = if (category.isBlank()) "追问" else category
        return "$prefix - 追问$index"
    }

    private fun calculateDistribution(total: Int): QuestionDistribution {
        val project = max(1, kotlin.math.round(total * 0.20).toInt())
        val mysql = max(1, kotlin.math.round(total * 0.20).toInt())
        val redis = max(1, kotlin.math.round(total * 0.20).toInt())
        val javaBasic = max(1, kotlin.math.round(total * 0.10).toInt())
        val javaCollection = max(0, kotlin.math.round(total * 0.10).toInt())
        val javaConcurrent = max(0, kotlin.math.round(total * 0.10).toInt())
        val spring = max(0, total - project - mysql - redis - javaBasic - javaCollection - javaConcurrent)
        return QuestionDistribution(
            project = project,
            mysql = mysql,
            redis = redis,
            javaBasic = javaBasic,
            javaCollection = javaCollection,
            javaConcurrent = javaConcurrent,
            spring = spring
        )
    }

    private fun renderTemplate(template: String, variables: Map<String, Any?>): String {
        var rendered = template
        for ((key, value) in variables) {
            rendered = rendered.replace("{$key}", value?.toString() ?: "")
        }
        return rendered
    }

    private fun readResource(resource: Resource): String {
        return resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private data class QuestionListDto(
        val questions: List<QuestionDto>? // AI返回的问题列表
    )

    private data class QuestionDto(
        val question: String?, // 问题内容
        val type: String?, // 问题类型
        val category: String?, // 问题类别
        val followUps: List<String>? // 追问列表
    )

    private data class QuestionDistribution(
        val project: Int, // 项目题数量
        val mysql: Int, // MySQL题数量
        val redis: Int, // Redis题数量
        val javaBasic: Int, // Java基础题数量
        val javaCollection: Int, // Java集合题数量
        val javaConcurrent: Int, // Java并发题数量
        val spring: Int // Spring/SpringBoot题数量
    )

    companion object {
        private val ALLOWED_TYPES = setOf(
            "PROJECT",
            "JAVA_BASIC",
            "JAVA_COLLECTION",
            "JAVA_CONCURRENT",
            "MYSQL",
            "REDIS",
            "SPRING",
            "SPRING_BOOT"
        )
    }
}

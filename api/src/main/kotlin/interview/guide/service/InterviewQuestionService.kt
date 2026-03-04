package interview.guide.service

import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * 面试问题生成服务
 * 基于简历内容生成针对性的面试问题
 */
@Service
class InterviewQuestionService(
    chatClientBuilder: ChatClient.Builder, // ChatClient 构建器
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用器
    @Value("classpath:prompts/interview-question-system.st") systemPromptResource: Resource, // 系统提示词
    @Value("classpath:prompts/interview-question-user.st") userPromptResource: Resource, // 用户提示词
    @Value("\${app.interview.follow-up-count:1}") followUpCount: Int // 追问数量
) {

    private val log = LoggerFactory.getLogger(InterviewQuestionService::class.java)

    private val chatClient: ChatClient = chatClientBuilder.build() // AI 客户端
    private val systemPromptTemplate = PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 系统模板
    private val userPromptTemplate = PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 用户模板
    private val outputConverter = BeanOutputConverter(QuestionListDto::class.java) // 输出转换器
    private val followUpCount = followUpCount.coerceIn(0, MAX_FOLLOW_UP_COUNT) // 追问数量限制

    companion object {
        private const val PROJECT_RATIO = 0.20 // 项目经历比例
        private const val MYSQL_RATIO = 0.20 // MySQL 比例
        private const val REDIS_RATIO = 0.20 // Redis 比例
        private const val JAVA_BASIC_RATIO = 0.10 // Java基础比例
        private const val JAVA_COLLECTION_RATIO = 0.10 // Java集合比例
        private const val JAVA_CONCURRENT_RATIO = 0.10 // Java并发比例
        private const val MAX_FOLLOW_UP_COUNT = 2 // 最大追问数
    }

    private data class QuestionListDto(
        val questions: List<QuestionDto>? // 问题列表
    )

    private data class QuestionDto(
        val question: String?, // 问题内容
        val type: String?, // 问题类型
        val category: String?, // 问题类别
        val followUps: List<String>? // 追问列表
    )

    private data class QuestionDistribution(
        val project: Int, // 项目题数量
        val mysql: Int, // MySQL 题数量
        val redis: Int, // Redis 题数量
        val javaBasic: Int, // Java基础题数量
        val javaCollection: Int, // Java集合题数量
        val javaConcurrent: Int, // Java并发题数量
        val spring: Int // Spring 题数量
    )

    /**
     * 生成面试问题
     *
     * @param resumeText 简历文本 // 简历内容
     * @param questionCount 问题数量 // 总题数
     * @param historicalQuestions 历史问题列表 // 用于去重
     * @return 面试问题列表 // 生成的问题
     */
    fun generateQuestions(
        resumeText: String,
        questionCount: Int,
        historicalQuestions: List<String>?
    ): List<InterviewQuestionVo> {
        log.info("开始生成面试问题，简历长度: {}, 问题数量: {}, 历史问题数: {}",
            resumeText.length, questionCount, historicalQuestions?.size ?: 0)

        val distribution = calculateDistribution(questionCount)

        try {
            val systemPrompt = systemPromptTemplate.render()
            val variables = mutableMapOf<String, Any>(
                "questionCount" to questionCount,
                "projectCount" to distribution.project,
                "mysqlCount" to distribution.mysql,
                "redisCount" to distribution.redis,
                "javaBasicCount" to distribution.javaBasic,
                "javaCollectionCount" to distribution.javaCollection,
                "javaConcurrentCount" to distribution.javaConcurrent,
                "springCount" to distribution.spring,
                "followUpCount" to followUpCount,
                "resumeText" to resumeText
            )

            if (!historicalQuestions.isNullOrEmpty()) {
                variables["historicalQuestions"] = historicalQuestions.joinToString("\n")
            } else {
                variables["historicalQuestions"] = "暂无历史提问"
            }

            val userPrompt = userPromptTemplate.render(variables)
            val systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.format

            val dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "面试问题生成失败：",
                "结构化问题生成",
                log
            )

            log.debug("AI响应解析成功: questions count={}", dto.questions?.size ?: 0)
            val questions = convertToQuestions(dto)
            log.info("成功生成 {} 个面试问题", questions.size)
            return questions
        } catch (e: Exception) {
            log.error("生成面试问题失败: {}", e.message, e)
            return generateDefaultQuestions(questionCount)
        }
    }

    /**
     * 生成面试问题（不带历史问题）
     */
    fun generateQuestions(resumeText: String, questionCount: Int): List<InterviewQuestionVo> {
        return generateQuestions(resumeText, questionCount, null)
    }

    /**
     * 计算各类型问题分布
     */
    private fun calculateDistribution(total: Int): QuestionDistribution {
        val project = maxOf(1, kotlin.math.round(total * PROJECT_RATIO).toInt())
        val mysql = maxOf(1, kotlin.math.round(total * MYSQL_RATIO).toInt())
        val redis = maxOf(1, kotlin.math.round(total * REDIS_RATIO).toInt())
        val javaBasic = maxOf(1, kotlin.math.round(total * JAVA_BASIC_RATIO).toInt())
        val javaCollection = kotlin.math.round(total * JAVA_COLLECTION_RATIO).toInt()
        val javaConcurrent = kotlin.math.round(total * JAVA_CONCURRENT_RATIO).toInt()
        val spring = maxOf(0, total - project - mysql - redis - javaBasic - javaCollection - javaConcurrent)
        return QuestionDistribution(project, mysql, redis, javaBasic, javaCollection, javaConcurrent, spring)
    }

    /**
     * 转换 DTO 为业务对象
     */
    private fun convertToQuestions(dto: QuestionListDto): List<InterviewQuestionVo> {
        val questions = mutableListOf<InterviewQuestionVo>()
        var index = 0

        val list = dto.questions ?: emptyList()
        for (q in list) {
            val questionText = q.question?.trim()
            if (questionText.isNullOrBlank()) {
                continue
            }
            val type = parseQuestionType(q.type)
            val mainQuestionIndex = index
            questions.add(InterviewQuestionVo.create(index++, questionText, type, q.category))

            val followUps = sanitizeFollowUps(q.followUps)
            for ((i, followUp) in followUps.withIndex()) {
                questions.add(
                    InterviewQuestionVo.create(
                        index++,
                        followUp,
                        type,
                        buildFollowUpCategory(q.category, i + 1),
                        true,
                        mainQuestionIndex
                    )
                )
            }
        }

        return questions
    }

    private fun parseQuestionType(typeStr: String?): InterviewQuestionType {
        return try {
            InterviewQuestionType.valueOf(typeStr?.uppercase() ?: "JAVA_BASIC")
        } catch (_: Exception) {
            InterviewQuestionType.JAVA_BASIC
        }
    }

    private fun sanitizeFollowUps(followUps: List<String>?): List<String> {
        if (followUpCount == 0 || followUps.isNullOrEmpty()) {
            return emptyList()
        }
        return followUps
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .take(followUpCount)
    }

    private fun buildFollowUpCategory(category: String?, order: Int): String {
        val base = if (category.isNullOrBlank()) "追问" else category
        return "$base（追问$order）"
    }

    /**
     * 生成默认问题（备用）
     *
     * @param count 题目数量 // 期望题数
     * @return 默认问题列表 // 兜底问题
     */
    private fun generateDefaultQuestions(count: Int): List<InterviewQuestionVo> {
        val questions = mutableListOf<InterviewQuestionVo>()

        val defaultQuestions = arrayOf(
            arrayOf("请介绍一下你在简历中提到的最重要的项目，你在其中承担了什么角色？", "PROJECT", "项目经历"),
            arrayOf("MySQL的索引有哪些类型？B+树索引的原理是什么？", "MYSQL", "MySQL"),
            arrayOf("Redis支持哪些数据结构？各自的使用场景是什么？", "REDIS", "Redis"),
            arrayOf("Java中HashMap的底层实现原理是什么？JDK8做了哪些优化？", "JAVA_COLLECTION", "Java集合"),
            arrayOf("synchronized和ReentrantLock有什么区别？", "JAVA_CONCURRENT", "Java并发"),
            arrayOf("Spring的IoC和AOP原理是什么？", "SPRING", "Spring"),
            arrayOf("MySQL事务的ACID特性是什么？隔离级别有哪些？", "MYSQL", "MySQL"),
            arrayOf("Redis的持久化机制有哪些？RDB和AOF的区别？", "REDIS", "Redis"),
            arrayOf("Java的垃圾回收机制是怎样的？常见的GC算法有哪些？", "JAVA_BASIC", "Java基础"),
            arrayOf("线程池的核心参数有哪些？如何合理配置？", "JAVA_CONCURRENT", "Java并发")
        )

        var index = 0
        val limit = minOf(count, defaultQuestions.size)
        for (i in 0 until limit) {
            val mainQuestion = defaultQuestions[i][0]
            val type = InterviewQuestionType.valueOf(defaultQuestions[i][1])
            val category = defaultQuestions[i][2]
            questions.add(InterviewQuestionVo.create(index++, mainQuestion, type, category))

            val mainQuestionIndex = index - 1
            for (j in 0 until followUpCount) {
                questions.add(
                    InterviewQuestionVo.create(
                        index++,
                        buildDefaultFollowUp(mainQuestion, j + 1),
                        type,
                        buildFollowUpCategory(category, j + 1),
                        true,
                        mainQuestionIndex
                    )
                )
            }
        }

        return questions
    }

    /**
     * 默认追问构造
     */
    private fun buildDefaultFollowUp(mainQuestion: String, order: Int): String {
        return if (order == 1) {
            "基于“$mainQuestion”，请结合你亲自做过的一个真实场景展开说明。"
        } else {
            "基于“$mainQuestion”，如果线上出现异常，你会如何定位并给出修复方案？"
        }
    }
}

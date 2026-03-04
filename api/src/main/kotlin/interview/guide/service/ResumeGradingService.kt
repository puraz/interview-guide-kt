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
 * 简历评分服务
 * 使用 Spring AI 调用 LLM 对简历进行评分和建议
 */
@Service
class ResumeGradingService(
    chatClientBuilder: ChatClient.Builder, // ChatClient 构建器
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用器
    @Value("classpath:prompts/resume-analysis-system.st") systemPromptResource: Resource, // 系统提示词
    @Value("classpath:prompts/resume-analysis-user.st") userPromptResource: Resource // 用户提示词
) {

    private val log = LoggerFactory.getLogger(ResumeGradingService::class.java)

    private val chatClient: ChatClient = chatClientBuilder.build() // AI 客户端
    private val systemPromptTemplate = PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 系统模板
    private val userPromptTemplate = PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8)) // 用户模板
    private val outputConverter = BeanOutputConverter(ResumeAnalysisResponseDto::class.java) // 输出转换器

    // 中间 DTO 用于接收 AI 响应
    private data class ResumeAnalysisResponseDto(
        val overallScore: Int, // 总分
        val scoreDetail: ScoreDetailDto, // 各维度评分
        val summary: String?, // 简历摘要
        val strengths: List<String>?, // 优点列表
        val suggestions: List<SuggestionDto>? // 改进建议
    )

    private data class ScoreDetailDto(
        val contentScore: Int, // 内容完整性
        val structureScore: Int, // 结构清晰度
        val skillMatchScore: Int, // 技能匹配度
        val expressionScore: Int, // 表达专业性
        val projectScore: Int // 项目经验
    )

    private data class SuggestionDto(
        val category: String?, // 建议类别
        val priority: String?, // 优先级
        val issue: String?, // 问题描述
        val recommendation: String? // 具体建议
    )

    /**
     * 分析简历并返回评分和建议
     *
     * @param resumeText 简历文本内容 // 简历原文
     * @return 分析结果 // AI 分析结果
     */
    fun analyzeResume(resumeText: String): ResumeAnalysisVo {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length)

        try {
            val systemPrompt = systemPromptTemplate.render()
            val userPrompt = userPromptTemplate.render(mapOf("resumeText" to resumeText))
            val systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.format

            val dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "简历分析失败：",
                "简历分析",
                log
            )

            log.debug("AI响应解析成功: overallScore={}", dto.overallScore)
            val result = convertToVo(dto, resumeText)
            log.info("简历分析完成，总分: {}", result.overallScore)
            return result
        } catch (e: Exception) {
            log.error("简历分析失败: {}", e.message, e)
            return createErrorResponse(resumeText, e.message ?: "未知错误")
        }
    }

    /**
     * 转换 DTO 为业务对象
     */
    private fun convertToVo(dto: ResumeAnalysisResponseDto, originalText: String): ResumeAnalysisVo {
        val scoreDetail = ResumeAnalysisVo.ScoreDetailVo(
            dto.scoreDetail.contentScore,
            dto.scoreDetail.structureScore,
            dto.scoreDetail.skillMatchScore,
            dto.scoreDetail.expressionScore,
            dto.scoreDetail.projectScore
        )
        val suggestions = dto.suggestions?.map { s ->
            ResumeAnalysisVo.SuggestionVo(s.category, s.priority, s.issue, s.recommendation)
        }

        return ResumeAnalysisVo(
            dto.overallScore,
            scoreDetail,
            dto.summary,
            dto.strengths,
            suggestions,
            originalText
        )
    }

    /**
     * 创建错误响应
     *
     * @param originalText 原始简历文本 // 回填原始内容
     * @param errorMessage 错误信息 // 失败原因说明
     * @return 错误结果 // 固定结构的失败响应
     */
    private fun createErrorResponse(originalText: String, errorMessage: String): ResumeAnalysisVo {
        return ResumeAnalysisVo(
            overallScore = 0,
            scoreDetail = ResumeAnalysisVo.ScoreDetailVo(0, 0, 0, 0, 0),
            summary = "分析过程中出现错误: $errorMessage",
            strengths = emptyList(),
            suggestions = listOf(
                ResumeAnalysisVo.SuggestionVo(
                    category = "系统",
                    priority = "高",
                    issue = "AI分析服务暂时不可用",
                    recommendation = "请稍后重试，或检查AI服务是否正常运行"
                )
            ),
            originalText = originalText
        )
    }
}

package interview.guide.modules.resume.service

import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.interview.model.ResumeAnalysisVo
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * 简历评分服务
 * 使用Spring AI调用LLM对简历进行评分和建议
 */
@Service
class ResumeGradingService(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用器
    properties: ResumeAnalysisProperties, // 简历分析提示词配置
    resourceLoader: ResourceLoader // 提示词资源加载器
) {

    companion object {
        private val log = LoggerFactory.getLogger(ResumeGradingService::class.java)
    }

    private val systemPromptTemplate: PromptTemplate = PromptTemplate(
        resourceLoader.getResource(properties.systemPromptPath)
            .getContentAsString(StandardCharsets.UTF_8)
    ) // 系统提示词模板

    private val userPromptTemplate: PromptTemplate = PromptTemplate(
        resourceLoader.getResource(properties.userPromptPath)
            .getContentAsString(StandardCharsets.UTF_8)
    ) // 用户提示词模板

    private val outputConverter = BeanOutputConverter<ResumeAnalysisResponseDto>(
        ResumeAnalysisResponseDto::class.java
    ) // AI 输出转换器

    // 中间DTO用于接收AI响应
    private data class ResumeAnalysisResponseDto(
        var overallScore: Int = 0, // 总分
        var scoreDetail: ScoreDetailDto? = null, // 各维度评分
        var summary: String? = null, // 简历摘要
        var strengths: List<String>? = null, // 优点列表
        var suggestions: List<SuggestionDto>? = null // 改进建议列表
    )

    private data class ScoreDetailDto(
        var contentScore: Int = 0, // 内容完整性
        var structureScore: Int = 0, // 结构清晰度
        var skillMatchScore: Int = 0, // 技能匹配度
        var expressionScore: Int = 0, // 表达专业性
        var projectScore: Int = 0 // 项目经验
    )

    private data class SuggestionDto(
        var category: String? = null, // 建议类别
        var priority: String? = null, // 优先级
        var issue: String? = null, // 问题描述
        var recommendation: String? = null // 具体建议
    )

    /**
     * 分析简历并返回评分和建议
     *
     * @param resumeText 简历文本内容
     * @return 分析结果
     */
    fun analyzeResume(resumeText: String): ResumeAnalysisVo {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length)

        try {
            // 加载系统提示词
            val systemPrompt = systemPromptTemplate.render()

            // 加载用户提示词并填充变量
            val variables = HashMap<String, Any>()
            variables["resumeText"] = resumeText
            val userPrompt = userPromptTemplate.render(variables)

            // 添加格式指令到系统提示词
            val systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.format

            // 调用AI
            val dto: ResumeAnalysisResponseDto
            try {
                val chatClient = llmProviderRegistry.getDefaultChatClient()
                dto = structuredOutputInvoker.invoke(
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
            } catch (e: Exception) {
                log.error("简历分析AI调用失败: {}", ErrorLogSanitizer.summarize(e),
                    ErrorLogSanitizer.forLogging(e))
                throw BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败")
            }

            // 转换为业务对象
            val result = convertToResponse(dto, resumeText)
            log.info("简历分析完成，总分: {}", result.overallScore)

            return result
        } catch (e: Exception) {
            log.error("简历分析失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e))
            return createErrorResponse(resumeText, e.javaClass.simpleName)
        }
    }

    /**
     * 转换DTO为业务对象
     *
     * @param dto          AI 返回的中间对象 // 结构化输出
     * @param originalText 原始简历文本 // 回填原文
     * @return 分析结果 // 业务 VO
     */
    private fun convertToResponse(dto: ResumeAnalysisResponseDto, originalText: String): ResumeAnalysisVo {
        val scoreDetailDto = dto.scoreDetail
            ?: throw IllegalStateException("AI 响应缺少各维度评分")
        val strengths = dto.strengths
            ?: throw IllegalStateException("AI 响应缺少优点列表")
        val suggestionDtos = dto.suggestions
            ?: throw IllegalStateException("AI 响应缺少改进建议")

        val scoreDetail = ResumeAnalysisVo.ScoreDetailVo(
            scoreDetailDto.contentScore,
            scoreDetailDto.structureScore,
            scoreDetailDto.skillMatchScore,
            scoreDetailDto.expressionScore,
            scoreDetailDto.projectScore
        )

        val suggestions = suggestionDtos.map { s ->
            ResumeAnalysisVo.SuggestionVo(s.category, s.priority, s.issue, s.recommendation)
        }

        return ResumeAnalysisVo(
            dto.overallScore,
            scoreDetail,
            dto.summary,
            strengths,
            suggestions,
            originalText
        )
    }

    /**
     * 创建错误响应
     *
     * @param originalText 原始简历文本 // 回填原文
     * @param errorMessage 错误信息 // 失败原因
     * @return 错误结果 // 固定结构的失败响应
     */
    private fun createErrorResponse(originalText: String, errorMessage: String): ResumeAnalysisVo {
        return ResumeAnalysisVo(
            0,
            ResumeAnalysisVo.ScoreDetailVo(0, 0, 0, 0, 0),
            "分析过程中出现错误: $errorMessage",
            emptyList(),
            listOf(
                ResumeAnalysisVo.SuggestionVo(
                    "系统",
                    "高",
                    "AI分析服务暂时不可用",
                    "请稍后重试，或检查AI服务是否正常运行"
                )
            ),
            originalText
        )
    }
}

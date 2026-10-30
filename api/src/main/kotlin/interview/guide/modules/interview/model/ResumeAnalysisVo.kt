package interview.guide.modules.interview.model

/**
 * 简历分析响应DTO
 */
data class ResumeAnalysisVo(
    // 总分 (0-100)
    val overallScore: Int? = null,

    // 各维度评分
    val scoreDetail: ScoreDetailVo? = null,

    // 简历摘要
    val summary: String? = null,

    // 优点列表
    val strengths: List<String>? = null,

    // 改进建议列表
    val suggestions: List<SuggestionVo>? = null,

    // 原始简历文本
    val originalText: String? = null
) {

    /**
     * 各维度评分详情
     */
    data class ScoreDetailVo(
        val contentScore: Int = 0, // 内容完整性 (0-25)
        val structureScore: Int = 0, // 结构清晰度 (0-20)
        val skillMatchScore: Int = 0, // 技能匹配度 (0-25)
        val expressionScore: Int = 0, // 表达专业性 (0-15)
        val projectScore: Int = 0 // 项目经验 (0-15)
    )

    /**
     * 改进建议
     */
    data class SuggestionVo(
        val category: String? = null, // 建议类别：内容、格式、技能、项目等
        val priority: String? = null, // 优先级：高、中、低
        val issue: String? = null, // 问题描述
        val recommendation: String? = null // 具体建议
    )
}

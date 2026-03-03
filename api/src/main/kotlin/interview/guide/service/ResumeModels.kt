package interview.guide.service

import interview.guide.common.model.AsyncTaskStatus
import java.time.LocalDateTime

/**
 * 简历列表项返回对象
 */
data class ResumeListItemVo(
    val id: Long, // 简历ID
    val filename: String?, // 原始文件名
    val fileSize: Long?, // 文件大小（字节）
    val uploadedAt: LocalDateTime?, // 上传时间
    val accessCount: Int?, // 访问次数
    val latestScore: Int?, // 最新评分
    val lastAnalyzedAt: LocalDateTime?, // 最近评测时间
    val interviewCount: Int // 面试次数
)

/**
 * 简历详情返回对象
 */
data class ResumeDetailVo(
    val id: Long, // 简历ID
    val filename: String?, // 原始文件名
    val fileSize: Long?, // 文件大小（字节）
    val contentType: String?, // 文件类型
    val storageUrl: String?, // 存储URL
    val uploadedAt: LocalDateTime?, // 上传时间
    val accessCount: Int?, // 访问次数
    val resumeText: String?, // 简历文本
    val analyzeStatus: AsyncTaskStatus?, // 分析状态
    val analyzeError: String?, // 分析错误信息
    val analyses: List<AnalysisHistoryVo>, // 分析历史列表
    val interviews: List<Any> // 面试历史列表
) {
    /**
     * 分析历史返回对象
     */
    data class AnalysisHistoryVo(
        val id: Long?, // 评测记录ID
        val overallScore: Int?, // 总分
        val contentScore: Int?, // 内容评分
        val structureScore: Int?, // 结构评分
        val skillMatchScore: Int?, // 技能匹配评分
        val expressionScore: Int?, // 表达评分
        val projectScore: Int?, // 项目评分
        val summary: String?, // 简历摘要
        val analyzedAt: LocalDateTime?, // 评测时间
        val strengths: List<String>, // 优点列表
        val suggestions: List<Any> // 建议列表
    )
}

/**
 * 简历分析结果返回对象
 */
data class ResumeAnalysisVo(
    val overallScore: Int, // 总分
    val scoreDetail: ScoreDetailVo?, // 各维度评分
    val summary: String?, // 简历摘要
    val strengths: List<String>?, // 优点列表
    val suggestions: List<SuggestionVo>?, // 改进建议列表
    val originalText: String? // 原始简历文本
) {
    /**
     * 各维度评分详情
     */
    data class ScoreDetailVo(
        val contentScore: Int, // 内容完整性
        val structureScore: Int, // 结构清晰度
        val skillMatchScore: Int, // 技能匹配度
        val expressionScore: Int, // 表达专业性
        val projectScore: Int // 项目经验
    )

    /**
     * 改进建议
     */
    data class SuggestionVo(
        val category: String?, // 建议类别
        val priority: String?, // 优先级
        val issue: String?, // 问题描述
        val recommendation: String? // 具体建议
    )
}

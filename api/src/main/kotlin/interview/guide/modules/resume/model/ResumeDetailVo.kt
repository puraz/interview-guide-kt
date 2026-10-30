package interview.guide.modules.resume.model

import interview.guide.common.model.AsyncTaskStatus
import interview.guide.modules.interview.model.InterviewHistoryItemVo
import java.time.LocalDateTime

/**
 * 简历详情返回对象（原 ResumeDetailDTO）
 */
data class ResumeDetailVo(
    val id: Long?, // 简历ID
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
    val interviews: List<InterviewHistoryItemVo> // 关联面试历史列表
) {
    /**
     * 分析历史返回对象（原 AnalysisHistoryDTO）
     */
    data class AnalysisHistoryVo(
        val id: Long?, // 分析记录ID
        val overallScore: Int?, // 总分 (0-100)
        val contentScore: Int?, // 内容完整性 (0-25)
        val structureScore: Int?, // 结构清晰度 (0-20)
        val skillMatchScore: Int?, // 技能匹配度 (0-25)
        val expressionScore: Int?, // 表达专业性 (0-15)
        val projectScore: Int?, // 项目经验 (0-15)
        val summary: String?, // 简历摘要
        val analyzedAt: LocalDateTime?, // 评测时间
        val strengths: List<String>, // 优点列表
        val suggestions: List<Any> // 改进建议列表
    )
}

package interview.guide.modules.resume.model

import interview.guide.common.model.AsyncTaskStatus
import java.time.LocalDateTime

/**
 * 简历列表项返回对象（原 ResumeListItemDTO）
 */
data class ResumeListItemVo(
    val id: Long?, // 简历ID
    val filename: String?, // 原始文件名
    val fileSize: Long?, // 文件大小（字节）
    val uploadedAt: LocalDateTime?, // 上传时间
    val accessCount: Int?, // 访问次数
    val latestScore: Int?, // 最新分析总分
    val lastAnalyzedAt: LocalDateTime?, // 最近分析时间
    val interviewCount: Int, // 关联面试次数
    val analyzeStatus: AsyncTaskStatus?, // 分析状态
    val analyzeError: String? // 分析错误信息
)

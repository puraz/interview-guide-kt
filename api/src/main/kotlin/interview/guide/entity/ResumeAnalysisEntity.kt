package interview.guide.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 简历评测结果实体
 */
@Entity
@Table(name = "resume_analyses")
open class ResumeAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    var resume: ResumeEntity? = null // 关联简历

    var overallScore: Int? = null // 总分

    var contentScore: Int? = null // 内容完整性评分
    var structureScore: Int? = null // 结构清晰度评分
    var skillMatchScore: Int? = null // 技能匹配度评分
    var expressionScore: Int? = null // 表达专业性评分
    var projectScore: Int? = null // 项目经验评分

    @Column(columnDefinition = "TEXT")
    var summary: String? = null // 简历摘要

    @Column(columnDefinition = "TEXT")
    var strengthsJson: String? = null // 优点列表 JSON

    @Column(columnDefinition = "TEXT")
    var suggestionsJson: String? = null // 改进建议 JSON

    @Column(nullable = false)
    var analyzedAt: LocalDateTime? = null // 评测时间

    @PrePersist
    fun onCreate() {
        analyzedAt = LocalDateTime.now()
    }
}

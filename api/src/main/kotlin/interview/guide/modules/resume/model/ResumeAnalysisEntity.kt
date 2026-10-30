package interview.guide.modules.resume.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 简历评测结果实体
 * Resume Analysis Entity
 */
@Entity
@Table(name = "resume_analyses")
open class ResumeAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    // 关联的简历
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    var resume: ResumeEntity? = null

    // 总分 (0-100)
    var overallScore: Int? = null

    // 各维度评分
    var contentScore: Int? = null // 内容完整性 (0-25)
    var structureScore: Int? = null // 结构清晰度 (0-20)
    var skillMatchScore: Int? = null // 技能匹配度 (0-25)
    var expressionScore: Int? = null // 表达专业性 (0-15)
    var projectScore: Int? = null // 项目经验 (0-15)

    // 简历摘要
    @Column(columnDefinition = "TEXT")
    var summary: String? = null

    // 优点列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    var strengthsJson: String? = null

    // 改进建议列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    var suggestionsJson: String? = null

    // 评测时间
    @Column(nullable = false)
    var analyzedAt: LocalDateTime? = null

    @PrePersist
    protected fun onCreate() {
        analyzedAt = LocalDateTime.now()
    }
}

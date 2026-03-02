package com.example.business.entity

import org.babyfish.jimmer.sql.Column
import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.JoinColumn
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.Table
import java.time.LocalDateTime

/**
 * 简历评测结果实体
 */
@Entity
@Table(name = "resume_analyses")
interface ResumeAnalysisEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 关联的简历
     */
    @ManyToOne
    @JoinColumn(name = "resume_id")
    val resume: ResumeEntity

    /**
     * 总分(0-100)
     */
    @Column(name = "overall_score")
    val overallScore: Int?

    /**
     * 内容完整性评分(0-25)
     */
    @Column(name = "content_score")
    val contentScore: Int?

    /**
     * 结构清晰度评分(0-20)
     */
    @Column(name = "structure_score")
    val structureScore: Int?

    /**
     * 技能匹配度评分(0-25)
     */
    @Column(name = "skill_match_score")
    val skillMatchScore: Int?

    /**
     * 表达专业性评分(0-15)
     */
    @Column(name = "expression_score")
    val expressionScore: Int?

    /**
     * 项目经验评分(0-15)
     */
    @Column(name = "project_score")
    val projectScore: Int?

    /**
     * 简历摘要
     */
    @Column(name = "summary", sqlType = "TEXT")
    val summary: String?

    /**
     * 优点列表(JSON)
     */
    @Column(name = "strengths_json", sqlType = "TEXT")
    val strengthsJson: String?

    /**
     * 改进建议列表(JSON)
     */
    @Column(name = "suggestions_json", sqlType = "TEXT")
    val suggestionsJson: String?

    /**
     * 评测时间
     */
    @Column(name = "analyzed_at")
    val analyzedAt: LocalDateTime
}

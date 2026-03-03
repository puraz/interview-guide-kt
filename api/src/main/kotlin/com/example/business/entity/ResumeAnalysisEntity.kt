package com.example.business.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 简历评测结果实体
 */
@Entity
@Table(name = "resume_analyses")
open class ResumeAnalysisEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 关联的简历
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    open lateinit var resume: ResumeEntity

    /**
     * 总分(0-100)
     */
    @Column(name = "overall_score")
    open var overallScore: Int? = null

    /**
     * 内容完整性评分(0-25)
     */
    @Column(name = "content_score")
    open var contentScore: Int? = null

    /**
     * 结构清晰度评分(0-20)
     */
    @Column(name = "structure_score")
    open var structureScore: Int? = null

    /**
     * 技能匹配度评分(0-25)
     */
    @Column(name = "skill_match_score")
    open var skillMatchScore: Int? = null

    /**
     * 表达专业性评分(0-15)
     */
    @Column(name = "expression_score")
    open var expressionScore: Int? = null

    /**
     * 项目经验评分(0-15)
     */
    @Column(name = "project_score")
    open var projectScore: Int? = null

    /**
     * 简历摘要
     */
    @Column(name = "summary", columnDefinition = "TEXT")
    open var summary: String? = null

    /**
     * 优点列表(JSON)
     */
    @Column(name = "strengths_json", columnDefinition = "TEXT")
    open var strengthsJson: String? = null

    /**
     * 改进建议列表(JSON)
     */
    @Column(name = "suggestions_json", columnDefinition = "TEXT")
    open var suggestionsJson: String? = null

    /**
     * 评测时间
     */
    @Column(name = "analyzed_at", nullable = false)
    open lateinit var analyzedAt: LocalDateTime
}

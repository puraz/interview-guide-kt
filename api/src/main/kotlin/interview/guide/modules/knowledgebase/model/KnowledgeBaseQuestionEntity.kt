package interview.guide.modules.knowledgebase.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 知识库题目实体
 */
@Entity
@Table(
    name = "knowledge_base_questions",
    indexes = [
        Index(name = "idx_kb_question_kb_status", columnList = "knowledge_base_id,status"),
        Index(name = "idx_kb_question_skill_difficulty", columnList = "skill_id,difficulty")
    ]
)
open class KnowledgeBaseQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null // 主键ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    var knowledgeBase: KnowledgeBaseEntity? = null // 所属知识库

    @Column(name = "knowledge_base_id", insertable = false, updatable = false)
    var knowledgeBaseId: Long? = null // 只读外键列（兜底读取）

    @Column(name = "skill_id", nullable = false, length = 64)
    var skillId: String = DEFAULT_SKILL_ID // 技能标识（兼容旧表非空约束）

    @Column(length = 16)
    var difficulty: String? = null // 难度

    @Column(length = 64)
    var type: String? = null // 题目类型

    // 面试方向，由模型生成（例如"整洁架构"/"Redis"/"测试策略"），用于筛选和开始面试
    @Column(length = 64)
    var category: String? = null

    @Column(nullable = false, columnDefinition = "TEXT")
    var question: String? = null // 题干

    @Column(length = 300)
    var topicSummary: String? = null // 知识点摘要

    @Column(columnDefinition = "TEXT")
    var referenceAnswer: String? = null // 参考答案

    @Column(columnDefinition = "TEXT")
    var keyPointsJson: String? = null // 要点 JSON

    @Column(columnDefinition = "TEXT")
    var scoringRubric: String? = null // 评分标准

    @Column(columnDefinition = "TEXT")
    var followUpsJson: String? = null // 追问题 JSON

    @Column(columnDefinition = "TEXT")
    var sourceContext: String? = null // 生成来源上下文

    @Column(length = 64)
    var kbContentHash: String? = null // 知识库内容哈希

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: KnowledgeBaseQuestionStatus = KnowledgeBaseQuestionStatus.DRAFT // 题目状态

    @Column(nullable = false)
    var createdAt: LocalDateTime? = null // 创建时间

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null // 更新时间

    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
        updatedAt = createdAt
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    companion object {
        // 兼容旧面试会话表的 skill_id 非空约束，知识库题目固定写这个默认值
        const val DEFAULT_SKILL_ID: String = "knowledge-base"
    }
}

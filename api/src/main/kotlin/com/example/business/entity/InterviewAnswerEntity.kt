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
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers")
open class InterviewAnswerEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long = 0L

    /**
     * 关联的会话
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    open lateinit var session: InterviewSessionEntity

    /**
     * 问题索引
     */
    @Column(name = "question_index")
    open var questionIndex: Int? = null

    /**
     * 问题内容
     */
    @Column(name = "question", columnDefinition = "TEXT")
    open var question: String? = null

    /**
     * 问题类别
     */
    @Column(name = "category")
    open var category: String? = null

    /**
     * 用户答案
     */
    @Column(name = "user_answer", columnDefinition = "TEXT")
    open var userAnswer: String? = null

    /**
     * 得分(0-100)
     */
    @Column(name = "score")
    open var score: Int? = null

    /**
     * 反馈
     */
    @Column(name = "feedback", columnDefinition = "TEXT")
    open var feedback: String? = null

    /**
     * 参考答案
     */
    @Column(name = "reference_answer", columnDefinition = "TEXT")
    open var referenceAnswer: String? = null

    /**
     * 关键点(JSON)
     */
    @Column(name = "key_points_json", columnDefinition = "TEXT")
    open var keyPointsJson: String? = null

    /**
     * 回答时间
     */
    @Column(name = "answered_at", nullable = false)
    open lateinit var answeredAt: LocalDateTime
}

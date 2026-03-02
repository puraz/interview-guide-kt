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
 * 面试答案实体
 */
@Entity
@Table(name = "interview_answers")
interface InterviewAnswerEntity {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    /**
     * 关联的会话
     */
    @ManyToOne
    @JoinColumn(name = "session_id")
    val session: InterviewSessionEntity

    /**
     * 问题索引
     */
    @Column(name = "question_index")
    val questionIndex: Int?

    /**
     * 问题内容
     */
    @Column(name = "question", sqlType = "TEXT")
    val question: String?

    /**
     * 问题类别
     */
    @Column(name = "category")
    val category: String?

    /**
     * 用户答案
     */
    @Column(name = "user_answer", sqlType = "TEXT")
    val userAnswer: String?

    /**
     * 得分(0-100)
     */
    @Column(name = "score")
    val score: Int?

    /**
     * 反馈
     */
    @Column(name = "feedback", sqlType = "TEXT")
    val feedback: String?

    /**
     * 参考答案
     */
    @Column(name = "reference_answer", sqlType = "TEXT")
    val referenceAnswer: String?

    /**
     * 关键点(JSON)
     */
    @Column(name = "key_points_json", sqlType = "TEXT")
    val keyPointsJson: String?

    /**
     * 回答时间
     */
    @Column(name = "answered_at")
    val answeredAt: LocalDateTime
}

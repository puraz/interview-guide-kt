package interview.guide.service

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * 创建面试会话请求
 */
data class CreateInterviewRequest(
    @field:NotBlank(message = "简历文本不能为空")
    val resumeText: String, // 简历文本内容
    @field:Min(value = 3, message = "题目数量最少3题")
    @field:Max(value = 20, message = "题目数量最多20题")
    val questionCount: Int, // 面试题目数量
    @field:NotNull(message = "简历ID不能为空")
    val resumeId: Long?, // 简历ID
    val forceCreate: Boolean? // 是否强制创建新会话
)

/**
 * 提交答案请求
 */
data class SubmitAnswerRequest(
    @field:NotBlank(message = "会话ID不能为空")
    val sessionId: String, // 会话ID
    @field:NotNull(message = "问题索引不能为空")
    @field:Min(value = 0, message = "问题索引无效")
    val questionIndex: Int?, // 问题索引
    @field:NotBlank(message = "答案不能为空")
    val answer: String // 用户答案
)

/**
 * 提交答案响应
 */
data class SubmitAnswerResponseVo(
    val hasNextQuestion: Boolean, // 是否还有下一题
    val nextQuestion: InterviewQuestionVo?, // 下一题内容
    val currentIndex: Int, // 当前问题索引
    val totalQuestions: Int // 总题数
)

/**
 * 面试会话状态
 */
enum class InterviewSessionStatus {
    CREATED, // 会话已创建
    IN_PROGRESS, // 面试进行中
    COMPLETED, // 面试已完成
    EVALUATED // 已生成评估报告
}

/**
 * 面试问题类型
 */
enum class InterviewQuestionType {
    PROJECT, // 项目经历
    JAVA_BASIC, // Java基础
    JAVA_COLLECTION, // Java集合
    JAVA_CONCURRENT, // Java并发
    MYSQL, // MySQL
    REDIS, // Redis
    SPRING, // Spring
    SPRING_BOOT // Spring Boot
}

/**
 * 面试问题返回对象
 */
data class InterviewQuestionVo(
    val questionIndex: Int, // 题目索引
    val question: String, // 问题内容
    val type: InterviewQuestionType, // 问题类型
    val category: String?, // 问题类别
    val userAnswer: String?, // 用户回答
    val score: Int?, // 单题得分
    val feedback: String?, // 单题反馈
    val isFollowUp: Boolean, // 是否为追问
    val parentQuestionIndex: Int? // 追问关联的主问题索引
) {
    companion object {
        /**
         * 创建新问题（未回答状态）
         */
        fun create(index: Int, question: String, type: InterviewQuestionType, category: String): InterviewQuestionVo {
            return InterviewQuestionVo(index, question, type, category, null, null, null, false, null)
        }

        /**
         * 创建新问题（支持追问标记）
         */
        fun create(
            index: Int,
            question: String,
            type: InterviewQuestionType,
            category: String,
            isFollowUp: Boolean,
            parentQuestionIndex: Int?
        ): InterviewQuestionVo {
            return InterviewQuestionVo(index, question, type, category, null, null, null, isFollowUp, parentQuestionIndex)
        }
    }

    /**
     * 添加用户回答
     */
    fun withAnswer(answer: String?): InterviewQuestionVo {
        return copy(userAnswer = answer)
    }

    /**
     * 添加评分和反馈
     */
    fun withEvaluation(score: Int?, feedback: String?): InterviewQuestionVo {
        return copy(score = score, feedback = feedback)
    }
}

/**
 * 面试会话返回对象
 */
data class InterviewSessionVo(
    val sessionId: String, // 会话ID
    val resumeText: String, // 简历文本
    val totalQuestions: Int, // 总题数
    val currentQuestionIndex: Int, // 当前题索引
    val questions: List<InterviewQuestionVo>, // 问题列表
    val status: InterviewSessionStatus // 会话状态
)

/**
 * 面试评估报告返回对象
 */
data class InterviewReportVo(
    val sessionId: String, // 会话ID
    val totalQuestions: Int, // 总题数
    val overallScore: Int, // 总分
    val categoryScores: List<CategoryScoreVo>, // 类别得分
    val questionDetails: List<QuestionEvaluationVo>, // 题目详情
    val overallFeedback: String, // 总体评价
    val strengths: List<String>, // 优势
    val improvements: List<String>, // 改进建议
    val referenceAnswers: List<ReferenceAnswerVo> // 参考答案
) {
    /**
     * 类别得分
     */
    data class CategoryScoreVo(
        val category: String, // 类别
        val score: Int, // 平均分
        val questionCount: Int // 题数
    )

    /**
     * 问题评估详情
     */
    data class QuestionEvaluationVo(
        val questionIndex: Int, // 题目索引
        val question: String, // 问题内容
        val category: String?, // 问题类别
        val userAnswer: String?, // 用户回答
        val score: Int, // 得分
        val feedback: String? // 反馈
    )

    /**
     * 参考答案
     */
    data class ReferenceAnswerVo(
        val questionIndex: Int, // 题目索引
        val question: String, // 问题内容
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String> // 要点列表
    )
}

/**
 * 面试详情返回对象
 */
data class InterviewDetailVo(
    val id: Long?, // 记录ID
    val sessionId: String?, // 会话ID
    val totalQuestions: Int?, // 总题数
    val status: String?, // 会话状态
    val evaluateStatus: String?, // 评估状态
    val evaluateError: String?, // 评估错误
    val overallScore: Int?, // 总分
    val overallFeedback: String?, // 总体评价
    val createdAt: LocalDateTime?, // 创建时间
    val completedAt: LocalDateTime?, // 完成时间
    val questions: List<Any>, // 题目列表
    val strengths: List<String>, // 优势
    val improvements: List<String>, // 改进建议
    val referenceAnswers: List<Any>, // 参考答案
    val answers: List<AnswerDetailVo> // 答案详情
) {
    /**
     * 答案详情
     */
    data class AnswerDetailVo(
        val questionIndex: Int?, // 题目索引
        val question: String?, // 问题内容
        val category: String?, // 问题类别
        val userAnswer: String?, // 用户回答
        val score: Int?, // 得分
        val feedback: String?, // 反馈
        val referenceAnswer: String?, // 参考答案
        val keyPoints: List<String>, // 要点列表
        val answeredAt: LocalDateTime? // 回答时间
    )
}

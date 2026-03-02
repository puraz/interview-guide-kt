package com.example.business.service

import com.example.business.entity.InterviewSessionEntity
import com.example.business.enums.AsyncTaskStatus
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.fasterxml.jackson.core.type.TypeReference
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 面试会话服务
 *
 * 管理面试会话的创建、答题、报告生成等流程。
 */
@Service
class InterviewSessionService(
    private val interviewQuestionService: InterviewQuestionService,
    private val answerEvaluationService: AnswerEvaluationService,
    private val interviewPersistenceService: InterviewPersistenceService
) {
    private val logger = LoggerFactory.getLogger(InterviewSessionService::class.java)

    /**
     * 创建面试会话
     *
     * @param param 创建参数
     * @return 面试会话信息
     */
    fun createSession(param: CreateInterviewParam): InterviewSessionVo {
        if (param.resumeId != null && param.forceCreate != true) {
            val unfinished = interviewPersistenceService.findUnfinishedSession(param.resumeId)
            if (unfinished != null) {
                logger.info("检测到未完成面试会话: resumeId={}, sessionId={}", param.resumeId, unfinished.sessionId)
                return buildSessionVo(unfinished, param.resumeText)
            }
        }

        val sessionId = generateSessionId()
        val historicalQuestions = param.resumeId?.let {
            interviewPersistenceService.getHistoricalQuestionsByResumeId(it)
        }

        val questions = interviewQuestionService.generateQuestions(
            resumeText = param.resumeText,
            questionCount = param.questionCount,
            historicalQuestions = historicalQuestions
        )

        interviewPersistenceService.saveSession(
            sessionId = sessionId,
            resumeId = param.resumeId ?: throw BusinessException("简历ID不能为空"),
            resumeText = param.resumeText,
            totalQuestions = questions.size,
            questions = questions
        )

        return InterviewSessionVo(
            sessionId = sessionId, // 会话ID
            resumeText = param.resumeText, // 简历文本
            totalQuestions = questions.size, // 题目总数
            currentQuestionIndex = 0, // 当前题索引
            questions = questions, // 问题列表
            status = InterviewSessionEntity.SessionStatus.CREATED.name // 会话状态
        )
    }

    /**
     * 获取会话信息
     *
     * @param sessionId 会话ID
     * @return 面试会话信息
     */
    fun getSession(sessionId: String): InterviewSessionVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")

        val resumeText = interviewPersistenceService.getResumeTextBySessionId(sessionId).orEmpty()
        return buildSessionVo(session, resumeText)
    }

    /**
     * 查找未完成会话
     *
     * @param resumeId 简历ID
     * @return 面试会话信息
     */
    fun findUnfinishedSessionOrThrow(resumeId: Long): InterviewSessionVo {
        val session = interviewPersistenceService.findUnfinishedSession(resumeId)
            ?: throw BusinessException("未找到未完成的面试会话")
        val resumeText = interviewPersistenceService.getResumeTextBySessionId(session.sessionId).orEmpty()
        return buildSessionVo(session, resumeText)
    }

    /**
     * 获取当前问题响应
     *
     * @param sessionId 会话ID
     * @return 当前问题信息
     */
    fun getCurrentQuestionResponse(sessionId: String): CurrentQuestionVo {
        val question = getCurrentQuestion(sessionId)
        return if (question == null) {
            CurrentQuestionVo(
                completed = true, // 是否完成
                question = null, // 当前问题
                message = "所有问题已回答完毕" // 完成提示
            )
        } else {
            CurrentQuestionVo(
                completed = false, // 是否完成
                question = question, // 当前问题
                message = null // 完成提示
            )
        }
    }

    /**
     * 获取当前问题
     *
     * @param sessionId 会话ID
     * @return 当前问题
     */
    fun getCurrentQuestion(sessionId: String): InterviewQuestionVo? {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        val questions = parseQuestions(session.questionsJson)
        val currentIndex = session.currentQuestionIndex ?: 0
        if (currentIndex >= questions.size) {
            return null
        }

        if (session.status == InterviewSessionEntity.SessionStatus.CREATED) {
            interviewPersistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.SessionStatus.IN_PROGRESS)
        }

        return questions[currentIndex]
    }

    /**
     * 提交答案（进入下一题）
     *
     * @param sessionId 会话ID
     * @param param 答案参数
     * @return 提交结果
     */
    fun submitAnswer(sessionId: String, param: SubmitAnswerParam): SubmitAnswerResponseVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        val questions = mergeAnswersIntoQuestions(
            parseQuestions(session.questionsJson),
            interviewPersistenceService.findAnswersBySessionId(sessionId)
        )

        if (param.questionIndex < 0 || param.questionIndex >= questions.size) {
            throw BusinessException("无效的问题索引: ${param.questionIndex}")
        }

        val currentQuestion = questions[param.questionIndex]
        interviewPersistenceService.saveAnswer(
            sessionId = sessionId,
            questionIndex = param.questionIndex,
            question = currentQuestion.question,
            category = currentQuestion.category,
            userAnswer = param.answer,
            score = 0,
            feedback = null
        )

        val newIndex = param.questionIndex + 1
        val hasNextQuestion = newIndex < questions.size
        val nextQuestion = if (hasNextQuestion) questions[newIndex] else null
        val newStatus = if (hasNextQuestion) {
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        } else {
            InterviewSessionEntity.SessionStatus.COMPLETED
        }

        interviewPersistenceService.updateCurrentQuestionIndex(sessionId, newIndex)
        interviewPersistenceService.updateSessionStatus(sessionId, newStatus)

        if (!hasNextQuestion) {
            interviewPersistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
        }

        return SubmitAnswerResponseVo(
            hasNextQuestion = hasNextQuestion, // 是否有下一题
            nextQuestion = nextQuestion, // 下一题
            currentIndex = newIndex, // 当前索引
            totalQuestions = questions.size // 题目总数
        )
    }

    /**
     * 暂存答案（不进入下一题）
     *
     * @param sessionId 会话ID
     * @param param 答案参数
     */
    fun saveAnswer(sessionId: String, param: SubmitAnswerParam) {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        val questions = parseQuestions(session.questionsJson)
        if (param.questionIndex < 0 || param.questionIndex >= questions.size) {
            throw BusinessException("无效的问题索引: ${param.questionIndex}")
        }
        val currentQuestion = questions[param.questionIndex]
        interviewPersistenceService.saveAnswer(
            sessionId = sessionId,
            questionIndex = param.questionIndex,
            question = currentQuestion.question,
            category = currentQuestion.category,
            userAnswer = param.answer,
            score = 0,
            feedback = null
        )

        if (session.status == InterviewSessionEntity.SessionStatus.CREATED) {
            interviewPersistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.SessionStatus.IN_PROGRESS)
        }
    }

    /**
     * 提前交卷
     *
     * @param sessionId 会话ID
     */
    fun completeInterview(sessionId: String) {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        if (session.status == InterviewSessionEntity.SessionStatus.COMPLETED ||
            session.status == InterviewSessionEntity.SessionStatus.EVALUATED
        ) {
            throw BusinessException("面试已完成，无法重复提交")
        }
        interviewPersistenceService.updateSessionStatus(sessionId, InterviewSessionEntity.SessionStatus.COMPLETED)
        interviewPersistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null)
    }

    /**
     * 生成评估报告
     *
     * @param sessionId 会话ID
     * @return 面试评估报告
     */
    fun generateReport(sessionId: String): AnswerEvaluationService.InterviewReportVo {
        val session = interviewPersistenceService.findBySessionId(sessionId)
            ?: throw BusinessException("未找到面试会话")
        if (session.status != InterviewSessionEntity.SessionStatus.COMPLETED &&
            session.status != InterviewSessionEntity.SessionStatus.EVALUATED
        ) {
            throw BusinessException("面试未完成，无法生成报告")
        }

        interviewPersistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PROCESSING, null)

        return try {
            val resumeText = interviewPersistenceService.getResumeTextBySessionId(sessionId).orEmpty()
            val questions = mergeAnswersIntoQuestions(
                parseQuestions(session.questionsJson),
                interviewPersistenceService.findAnswersBySessionId(sessionId)
            )
            val report = answerEvaluationService.evaluateInterview(sessionId, resumeText, questions)
            interviewPersistenceService.saveReport(sessionId, report)
            interviewPersistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.COMPLETED, null)
            report
        } catch (ex: Exception) {
            interviewPersistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, ex.message)
            throw ex
        }
    }

    private fun buildSessionVo(session: InterviewSessionEntity, resumeText: String): InterviewSessionVo {
        val questions = mergeAnswersIntoQuestions(
            parseQuestions(session.questionsJson),
            interviewPersistenceService.findAnswersBySessionId(session.sessionId)
        )
        return InterviewSessionVo(
            sessionId = session.sessionId, // 会话ID
            resumeText = resumeText, // 简历文本
            totalQuestions = session.totalQuestions ?: questions.size, // 题目总数
            currentQuestionIndex = session.currentQuestionIndex ?: 0, // 当前题索引
            questions = questions, // 问题列表
            status = session.status?.name ?: InterviewSessionEntity.SessionStatus.CREATED.name // 会话状态
        )
    }

    private fun parseQuestions(json: String?): List<InterviewQuestionVo> {
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        return JSONUtil.parseObject(json, object : TypeReference<List<InterviewQuestionVo>>() {})
            ?: emptyList()
    }

    private fun mergeAnswersIntoQuestions(
        questions: List<InterviewQuestionVo>,
        answers: List<com.example.business.entity.InterviewAnswerEntity>
    ): List<InterviewQuestionVo> {
        if (questions.isEmpty() || answers.isEmpty()) {
            return questions
        }
        val answerMap = answers.associateBy { it.questionIndex }
        return questions.map { question ->
            val answer = answerMap[question.questionIndex] ?: return@map question
            question.copy(
                userAnswer = answer.userAnswer,
                score = answer.score,
                feedback = answer.feedback
            )
        }
    }

    private fun generateSessionId(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    /**
     * 创建面试会话参数
     */
    data class CreateInterviewParam(
        @field:NotBlank(message = "简历文本不能为空")
        val resumeText: String, // 简历文本
        @field:Min(value = 3, message = "题目数量最少3题")
        @field:Max(value = 20, message = "题目数量最多20题")
        val questionCount: Int, // 题目数量
        @field:NotNull(message = "简历ID不能为空")
        val resumeId: Long?, // 简历ID
        val forceCreate: Boolean? // 是否强制创建新会话
    )

    /**
     * 提交答案参数
     */
    data class SubmitAnswerParam(
        @field:NotNull(message = "问题索引不能为空")
        @field:Min(value = 0, message = "问题索引无效")
        val questionIndex: Int, // 问题索引
        @field:NotBlank(message = "答案不能为空")
        val answer: String // 用户答案
    )

    /**
     * 面试会话返回对象
     */
    data class InterviewSessionVo(
        val sessionId: String, // 会话ID
        val resumeText: String, // 简历文本
        val totalQuestions: Int, // 题目总数
        val currentQuestionIndex: Int, // 当前题索引
        val questions: List<InterviewQuestionVo>, // 问题列表
        val status: String // 会话状态
    )

    /**
     * 面试问题返回对象
     */
    data class InterviewQuestionVo(
        val questionIndex: Int, // 问题索引
        val question: String, // 问题内容
        val type: String, // 问题类型
        val category: String, // 问题类别
        val userAnswer: String?, // 用户回答
        val score: Int?, // 单题得分
        val feedback: String?, // 单题反馈
        val isFollowUp: Boolean, // 是否追问
        val parentQuestionIndex: Int? // 追问关联的主问题索引
    )

    /**
     * 当前问题返回对象
     */
    data class CurrentQuestionVo(
        val completed: Boolean, // 是否已完成
        val question: InterviewQuestionVo?, // 当前问题
        val message: String? // 完成提示
    )

    /**
     * 提交答案返回对象
     */
    data class SubmitAnswerResponseVo(
        val hasNextQuestion: Boolean, // 是否有下一题
        val nextQuestion: InterviewQuestionVo?, // 下一题
        val currentIndex: Int, // 当前索引
        val totalQuestions: Int // 题目总数
    )
}

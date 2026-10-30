package interview.guide.modules.interview.service

import interview.guide.common.evaluation.EvaluationReport
import interview.guide.common.evaluation.QaRecord
import interview.guide.common.evaluation.UnifiedEvaluationService
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.log.ErrorLogSanitizer
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewReportVo
import interview.guide.modules.interview.skill.InterviewSkillService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
class AnswerEvaluationService(
    private val unifiedEvaluationService: UnifiedEvaluationService, // 统一评估服务
    private val persistenceService: InterviewPersistenceService, // 面试持久化服务
    private val skillService: InterviewSkillService // 面试方向 Skill 服务
) {

    companion object {
        private val log = LoggerFactory.getLogger(AnswerEvaluationService::class.java)
    }

    /**
     * 评估完整面试并生成报告
     */
    fun evaluateInterview(
        chatClient: ChatClient,
        sessionId: String,
        resumeText: String?,
        questions: List<InterviewQuestionVo>
    ): InterviewReportVo {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size)

        try {
            // 转为通用问答记录
            val qaRecords = questions.map {
                QaRecord(
                    it.questionIndex, it.question, it.category, it.userAnswer,
                    it.isFollowUp, it.parentQuestionIndex
                )
            }

            var referenceContext = buildQuestionReferenceContext(questions)
            if (referenceContext.isBlank()) {
                referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                    persistenceService.findBySessionId(sessionId)?.skillId
                )
            }

            // 调用通用评估服务
            val report = unifiedEvaluationService.evaluate(
                chatClient, sessionId, qaRecords, resumeText, referenceContext
            )

            // 转为文字面试专用 DTO
            return withQuestionReferences(toInterviewReportVo(report), questions)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "面试评估失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e)
            )
            throw BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败")
        }
    }

    /**
     * 通用评估报告转文字面试报告
     */
    private fun toInterviewReportVo(report: EvaluationReport): InterviewReportVo {
        return InterviewReportVo(
            sessionId = report.sessionId,
            totalQuestions = report.totalQuestions,
            overallScore = report.overallScore,
            categoryScores = report.categoryScores.map { cs ->
                InterviewReportVo.CategoryScoreVo(cs.category, cs.score, cs.questionCount)
            },
            questionDetails = report.questionDetails.map { qe ->
                InterviewReportVo.QuestionEvaluationVo(
                    qe.questionIndex, qe.question, qe.category,
                    qe.userAnswer, qe.score, qe.feedback
                )
            },
            overallFeedback = report.overallFeedback,
            strengths = report.strengths,
            improvements = report.improvements,
            referenceAnswers = report.referenceAnswers.map { ra ->
                InterviewReportVo.ReferenceAnswerVo(
                    ra.questionIndex, ra.question,
                    ra.referenceAnswer, ra.keyPoints
                )
            }
        )
    }

    /**
     * 用题库自带参考答案覆盖模型生成结果
     */
    private fun withQuestionReferences(
        report: InterviewReportVo,
        questions: List<InterviewQuestionVo>
    ): InterviewReportVo {
        val references = report.referenceAnswers.map { reference ->
            val question = questions.firstOrNull { it.questionIndex == reference.questionIndex }
            if (question?.referenceAnswer.isNullOrBlank()) {
                reference
            } else {
                InterviewReportVo.ReferenceAnswerVo(
                    reference.questionIndex,
                    reference.question,
                    question?.referenceAnswer,
                    question?.keyPoints ?: emptyList()
                )
            }
        }
        return report.copy(referenceAnswers = references)
    }

    /**
     * 构建题目参考基线（参考答案/评分要点/评分规则）
     */
    private fun buildQuestionReferenceContext(questions: List<InterviewQuestionVo>): String {
        val sb = StringBuilder()
        for (question in questions) {
            if (!hasQuestionReference(question)) {
                continue
            }
            sb.append("问题").append(question.questionIndex + 1).append(": ")
                .append(question.question).append('\n')
            appendIfPresent(sb, "参考答案", question.referenceAnswer)
            if (!question.keyPoints.isNullOrEmpty()) {
                sb.append("评分要点: ").append(question.keyPoints?.joinToString("；") ?: "").append('\n')
            }
            appendIfPresent(sb, "评分规则", question.scoringRubric)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 判断题目是否携带参考信息
     */
    private fun hasQuestionReference(question: InterviewQuestionVo): Boolean {
        return hasText(question.referenceAnswer) ||
            hasText(question.scoringRubric) ||
            !question.keyPoints.isNullOrEmpty()
    }

    /**
     * 追加非空标签内容
     */
    private fun appendIfPresent(sb: StringBuilder, label: String, value: String?) {
        if (hasText(value)) {
            sb.append(label).append(": ").append(value?.trim() ?: "").append('\n')
        }
    }

    /**
     * 判断文本非空
     */
    private fun hasText(value: String?): Boolean {
        return !value.isNullOrBlank()
    }
}

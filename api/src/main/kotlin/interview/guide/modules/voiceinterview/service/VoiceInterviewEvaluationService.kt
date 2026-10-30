package interview.guide.modules.voiceinterview.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.evaluation.EvaluationReport
import interview.guide.common.evaluation.QaRecord
import interview.guide.common.evaluation.UnifiedEvaluationService
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.interview.skill.InterviewSkillService
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailVo
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底
 */
@Service
class VoiceInterviewEvaluationService(
    private val unifiedEvaluationService: UnifiedEvaluationService, // 统一评估服务
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val evaluationRepository: VoiceInterviewEvaluationRepository, // 评估仓库
    private val messageRepository: VoiceInterviewMessageRepository, // 消息仓库
    private val sessionRepository: VoiceInterviewSessionRepository, // 会话仓库
    private val objectMapper: ObjectMapper, // JSON 序列化
    private val skillService: InterviewSkillService // 面试技能服务
) {

    private val log = LoggerFactory.getLogger(VoiceInterviewEvaluationService::class.java)

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内
     *
     * @param sessionId 会话ID // 待评估会话
     */
    fun generateEvaluation(sessionId: Long) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId)

            val session = getSession(sessionId)
            val messages = messageRepository
                .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
                    sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY
                )

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId)
                saveEmptyEvaluationTransactional(sessionId, session)
                return
            }

            val qaRecords = buildQaRecords(messages)

            val provider = session.llmProvider
            val chatClient: ChatClient = llmProviderRegistry.getChatClientOrDefault(provider)

            val sessionIdStr = sessionId.toString()
            val referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.skillId)
            val report: EvaluationReport = unifiedEvaluationService.evaluate(
                chatClient, sessionIdStr, qaRecords, null, referenceContext
            )

            saveEvaluationTransactional(sessionId, session, report)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e)
            throw BusinessException(
                ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: ${e.message}"
            )
        }
    }

    /**
     * 读取评估详情（不存在则抛业务异常）
     *
     * @param sessionId 会话ID // 待查询会话
     * @return 评估详情 // 供前端复用文字面试评估面板
     */
    fun getEvaluation(sessionId: Long): VoiceEvaluationDetailVo {
        val evaluation = evaluationRepository.findBySessionId(sessionId)
            ?: throw BusinessException(
                ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: $sessionId"
            )

        return buildDetailDTO(evaluation)
    }

    /**
     * 将消息记录转换为通用问答记录（同一套规则与文字面试评估对齐）
     */
    private fun buildQaRecords(messages: List<VoiceInterviewMessageEntity>): List<QaRecord> {
        val records = mutableListOf<QaRecord>()
        var index = 0
        var pendingQuestion: PendingQuestion? = null

        for (msg in messages) {
            val aiText = VoiceInterviewMessageEntity.trimToNull(msg.aiGeneratedText)
            val userText = VoiceInterviewMessageEntity.trimToNull(msg.userRecognizedText)

            if (pendingQuestion != null && userText != null) {
                records.add(
                    QaRecord(
                        questionIndex = index,
                        question = pendingQuestion.question,
                        category = pendingQuestion.category,
                        userAnswer = userText,
                        followUp = false,
                        parentQuestionIndex = null
                    )
                )
                index++
                pendingQuestion = null
                if (aiText != null) {
                    pendingQuestion = PendingQuestion(aiText, inferCategory(aiText))
                }
                continue
            }

            if (pendingQuestion != null) {
                records.add(
                    QaRecord(
                        questionIndex = index,
                        question = pendingQuestion.question,
                        category = pendingQuestion.category,
                        userAnswer = null,
                        followUp = false,
                        parentQuestionIndex = null
                    )
                )
                index++
                pendingQuestion = null
            }

            if (aiText != null && userText != null) {
                records.add(
                    QaRecord(
                        questionIndex = index,
                        question = aiText,
                        category = inferCategory(aiText),
                        userAnswer = userText,
                        followUp = false,
                        parentQuestionIndex = null
                    )
                )
                index++
            } else if (aiText != null) {
                pendingQuestion = PendingQuestion(aiText, inferCategory(aiText))
            } else if (userText != null) {
                records.add(
                    QaRecord(
                        questionIndex = index,
                        question = "",
                        category = "综合",
                        userAnswer = userText,
                        followUp = false,
                        parentQuestionIndex = null
                    )
                )
                index++
            }
        }

        val pending = pendingQuestion
        if (pending != null) {
            records.add(
                QaRecord(
                    questionIndex = index,
                    question = pending.question,
                    category = pending.category,
                    userAnswer = null,
                    followUp = false,
                    parentQuestionIndex = null
                )
            )
        }

        return records
    }

    /**
     * 待配对的问题（AI 文本 + 推断分类）
     */
    private data class PendingQuestion(
        val question: String, // 问题文本
        val category: String // 问题分类
    )

    /**
     * 根据 AI 文本推断问题分类
     */
    private fun inferCategory(aiText: String?): String {
        if (aiText == null) return "综合"
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖"
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍"
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题"
        return "技术问题"
    }

    /**
     * 保存评估结果（事务内写入）
     *
     * @param sessionId 会话ID
     * @param session 会话实体 // 读取角色与开始时间
     * @param report 评估报告 // 统一评估结果
     */
    @Transactional
    fun saveEvaluationTransactional(
        sessionId: Long,
        session: VoiceInterviewSessionEntity,
        report: EvaluationReport
    ) {
        try {
            val questionItems = report.questionDetails
            val refAnswerItems = report.referenceAnswers

            val entity = VoiceInterviewEvaluationEntity().apply {
                this.sessionId = sessionId
                overallScore = report.overallScore
                overallFeedback = report.overallFeedback
                questionEvaluationsJson = objectMapper.writeValueAsString(questionItems)
                strengthsJson = objectMapper.writeValueAsString(report.strengths)
                improvementsJson = objectMapper.writeValueAsString(report.improvements)
                referenceAnswersJson = objectMapper.writeValueAsString(refAnswerItems)
                interviewerRole = session.roleType
                interviewDate = session.startTime
            }

            evaluationRepository.save(entity)
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.overallScore)
        } catch (e: Exception) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e)
            throw BusinessException(
                ErrorCode.VOICE_EVALUATION_FAILED,
                "保存评估失败: ${e.message}"
            )
        }
    }

    /**
     * 保存空评估结果（会话无有效对话时兜底，不伪造题目数据）
     *
     * @param sessionId 会话ID
     * @param session 会话实体
     */
    @Transactional
    fun saveEmptyEvaluationTransactional(sessionId: Long, session: VoiceInterviewSessionEntity) {
        try {
            val entity = evaluationRepository.findBySessionId(sessionId)
                ?: VoiceInterviewEvaluationEntity().apply { this.sessionId = sessionId }

            entity.overallScore = null
            entity.overallFeedback = "本次语音面试未形成有效对话记录，暂无可评估内容。"
            entity.questionEvaluationsJson = "[]"
            entity.strengthsJson = "[]"
            entity.improvementsJson = "[\"请先完成至少一轮有效问答后再生成评估。\"]"
            entity.referenceAnswersJson = "[]"
            entity.interviewerRole = session.roleType
            entity.interviewDate = session.startTime

            evaluationRepository.save(entity)
            log.info("空评估结果已保存: sessionId={}", sessionId)
        } catch (e: Exception) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e)
            throw BusinessException(
                ErrorCode.VOICE_EVALUATION_FAILED,
                "保存空评估失败: ${e.message}"
            )
        }
    }

    /**
     * 将持久化评估实体组装为详情 VO
     */
    private fun buildDetailDTO(entity: VoiceInterviewEvaluationEntity): VoiceEvaluationDetailVo {
        try {
            val questionItems: List<EvaluationReport.QuestionEvaluation> = objectMapper.readValue(
                entity.questionEvaluationsJson ?: "",
                object : TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
            )

            val strengths: List<String> = objectMapper.readValue(
                entity.strengthsJson ?: "",
                object : TypeReference<List<String>>() {}
            )

            val improvements: List<String> = objectMapper.readValue(
                entity.improvementsJson ?: "",
                object : TypeReference<List<String>>() {}
            )

            val refAnswers: List<EvaluationReport.ReferenceAnswer> = objectMapper.readValue(
                entity.referenceAnswersJson ?: "",
                object : TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
            )

            // 与 Java 版一致：同一题号出现多次时保留第一条参考答案
            val refMap = mutableMapOf<Int, EvaluationReport.ReferenceAnswer>()
            for (ref in refAnswers) {
                if (!refMap.containsKey(ref.questionIndex)) {
                    refMap[ref.questionIndex] = ref
                }
            }

            val answers = mutableListOf<VoiceEvaluationDetailVo.AnswerDetail>()
            for (q in questionItems) {
                val ref = refMap[q.questionIndex]
                answers.add(
                    VoiceEvaluationDetailVo.AnswerDetail(
                        questionIndex = q.questionIndex,
                        question = q.question,
                        category = q.category,
                        userAnswer = q.userAnswer,
                        score = q.score,
                        feedback = q.feedback,
                        referenceAnswer = ref?.referenceAnswer,
                        keyPoints = ref?.keyPoints
                    )
                )
            }

            return VoiceEvaluationDetailVo(
                sessionId = entity.sessionId,
                totalQuestions = answers.size,
                overallScore = entity.overallScore,
                overallFeedback = entity.overallFeedback,
                strengths = strengths,
                improvements = improvements,
                answers = answers
            )
        } catch (e: Exception) {
            log.error("构建评估详情失败: sessionId={}", entity.sessionId, e)
            throw BusinessException(
                ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: ${e.message}"
            )
        }
    }

    /**
     * 读取会话实体（不存在抛业务异常）
     */
    private fun getSession(sessionId: Long): VoiceInterviewSessionEntity {
        return sessionRepository.findById(sessionId).orElse(null)
            ?: throw BusinessException(
                ErrorCode.VOICE_SESSION_NOT_FOUND,
                "语音面试会话不存在: $sessionId"
            )
    }
}

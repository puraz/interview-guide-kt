package interview.guide.controller

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import interview.guide.service.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@RestController
class InterviewController(
    private val sessionService: InterviewSessionService, // 面试会话服务
    private val historyService: InterviewHistoryService, // 面试历史服务
    private val persistenceService: InterviewPersistenceService // 面试持久化服务
) {

    private val log = LoggerFactory.getLogger(InterviewController::class.java)

    /**
     * 创建面试会话
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimensions = [RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP], count = 5.0)
    fun createSession(@RequestBody request: CreateInterviewRequest): Result<InterviewSessionVo> {
        log.info("创建面试会话，题目数量: {}", request.questionCount)
        val session = sessionService.createSession(request)
        return Result.success(session)
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    fun getSession(@PathVariable sessionId: String): Result<InterviewSessionVo> {
        val session = sessionService.getSession(sessionId)
        return Result.success(session)
    }

    /**
     * 获取当前问题
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    fun getCurrentQuestion(@PathVariable sessionId: String): Result<Map<String, Any>> {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId))
    }

    /**
     * 提交答案
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimensions = [RateLimit.Dimension.GLOBAL], count = 10.0)
    fun submitAnswer(@PathVariable sessionId: String, @RequestBody body: Map<String, Any>): Result<SubmitAnswerResponseVo> {
        val questionIndex = (body["questionIndex"] as? Number)?.toInt()
        val answer = body["answer"] as? String
        log.info("提交答案: 会话{}, 问题{}", sessionId, questionIndex)
        val request = SubmitAnswerRequest(sessionId, questionIndex, answer ?: "")
        val response = sessionService.submitAnswer(request)
        return Result.success(response)
    }

    /**
     * 生成面试报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    fun getReport(@PathVariable sessionId: String): Result<InterviewReportVo> {
        log.info("生成面试报告: {}", sessionId)
        val report = sessionService.generateReport(sessionId)
        return Result.success(report)
    }

    /**
     * 查找未完成的面试会话
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    fun findUnfinishedSession(@PathVariable resumeId: Long): Result<InterviewSessionVo> {
        return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId))
    }

    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    fun saveAnswer(@PathVariable sessionId: String, @RequestBody body: Map<String, Any>): Result<Void> {
        val questionIndex = (body["questionIndex"] as? Number)?.toInt()
        val answer = body["answer"] as? String
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex)
        val request = SubmitAnswerRequest(sessionId, questionIndex, answer ?: "")
        sessionService.saveAnswer(request)
        return Result.success(null)
    }

    /**
     * 提前交卷
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    fun completeInterview(@PathVariable sessionId: String): Result<Void> {
        log.info("提前交卷: {}", sessionId)
        sessionService.completeInterview(sessionId)
        return Result.success(null)
    }

    /**
     * 获取面试会话详情
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    fun getInterviewDetail(@PathVariable sessionId: String): Result<InterviewDetailVo> {
        val detail = historyService.getInterviewDetail(sessionId)
        return Result.success(detail)
    }

    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    fun exportInterviewPdf(@PathVariable sessionId: String): ResponseEntity<ByteArray> {
        return try {
            val pdfBytes = historyService.exportInterviewPdf(sessionId)
            val filename = URLEncoder.encode("模拟面试报告_${sessionId}.pdf", StandardCharsets.UTF_8)
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''$filename")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes)
        } catch (e: Exception) {
            log.error("导出PDF失败", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * 删除面试会话
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    fun deleteInterview(@PathVariable sessionId: String): Result<Void> {
        log.info("删除面试会话: {}", sessionId)
        persistenceService.deleteSessionBySessionId(sessionId)
        return Result.success(null)
    }
}

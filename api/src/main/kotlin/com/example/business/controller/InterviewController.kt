package com.example.business.controller

import com.example.business.service.InterviewHistoryService
import com.example.business.service.InterviewPersistenceService
import com.example.business.service.InterviewSessionService
import com.example.framework.base.controller.SuperBaseController
import com.example.framework.base.result.ApiResult
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 面试控制器
 *
 * 提供模拟面试相关API接口。
 */
@Validated
@RestController
class InterviewController(
    private val interviewSessionService: InterviewSessionService,
    private val interviewHistoryService: InterviewHistoryService,
    private val interviewPersistenceService: InterviewPersistenceService
) : SuperBaseController {

    /**
     * 创建面试会话
     *
     * @param param 创建参数
     * @return 面试会话信息
     */
    @PostMapping("/api/interview/sessions")
    fun createSession(
        @Valid @RequestBody param: InterviewSessionService.CreateInterviewParam
    ): ApiResult<InterviewSessionService.InterviewSessionVo> {
        return success(interviewSessionService.createSession(param))
    }

    /**
     * 获取会话信息
     *
     * @param sessionId 会话ID
     * @return 面试会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    fun getSession(
        @PathVariable sessionId: String
    ): ApiResult<InterviewSessionService.InterviewSessionVo> {
        return success(interviewSessionService.getSession(sessionId))
    }

    /**
     * 获取当前问题
     *
     * @param sessionId 会话ID
     * @return 当前问题信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    fun getCurrentQuestion(
        @PathVariable sessionId: String
    ): ApiResult<InterviewSessionService.CurrentQuestionVo> {
        return success(interviewSessionService.getCurrentQuestionResponse(sessionId))
    }

    /**
     * 提交答案
     *
     * @param sessionId 会话ID
     * @param param 答案参数
     * @return 提交结果
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    fun submitAnswer(
        @PathVariable sessionId: String,
        @Valid @RequestBody param: InterviewSessionService.SubmitAnswerParam
    ): ApiResult<InterviewSessionService.SubmitAnswerResponseVo> {
        return success(interviewSessionService.submitAnswer(sessionId, param))
    }

    /**
     * 暂存答案（不进入下一题）
     *
     * @param sessionId 会话ID
     * @param param 答案参数
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    fun saveAnswer(
        @PathVariable sessionId: String,
        @Valid @RequestBody param: InterviewSessionService.SubmitAnswerParam
    ): ApiResult<Void> {
        interviewSessionService.saveAnswer(sessionId, param)
        return success()
    }

    /**
     * 提前交卷
     *
     * @param sessionId 会话ID
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    fun completeInterview(
        @PathVariable sessionId: String
    ): ApiResult<Void> {
        interviewSessionService.completeInterview(sessionId)
        return success()
    }

    /**
     * 生成面试报告
     *
     * @param sessionId 会话ID
     * @return 面试评估报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    fun getReport(
        @PathVariable sessionId: String
    ): ApiResult<com.example.business.service.AnswerEvaluationService.InterviewReportVo> {
        return success(interviewSessionService.generateReport(sessionId))
    }

    /**
     * 查找未完成会话
     *
     * @param resumeId 简历ID
     * @return 未完成会话
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    fun findUnfinishedSession(
        @PathVariable resumeId: Long
    ): ApiResult<InterviewSessionService.InterviewSessionVo> {
        return success(interviewSessionService.findUnfinishedSessionOrThrow(resumeId))
    }

    /**
     * 获取面试会话详情
     *
     * @param sessionId 会话ID
     * @return 面试详情
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    fun getInterviewDetail(
        @PathVariable sessionId: String
    ): ApiResult<InterviewHistoryService.InterviewDetailVo> {
        return success(interviewHistoryService.getInterviewDetail(sessionId))
    }

    /**
     * 导出面试报告为PDF
     *
     * @param sessionId 会话ID
     * @return PDF响应
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    fun exportInterviewPdf(
        @PathVariable sessionId: String
    ): ResponseEntity<ByteArray> {
        val pdfBytes = interviewHistoryService.exportInterviewPdf(sessionId)
        val filename = URLEncoder.encode("模拟面试报告_$sessionId.pdf", StandardCharsets.UTF_8)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''$filename")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes)
    }

    /**
     * 删除面试会话
     *
     * @param sessionId 会话ID
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    fun deleteInterview(
        @PathVariable sessionId: String
    ): ApiResult<Void> {
        interviewPersistenceService.deleteSessionBySessionId(sessionId)
        return success()
    }
}

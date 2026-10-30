package interview.guide.modules.knowledgebase

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import interview.guide.modules.interview.model.InterviewSessionVo
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityVo
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionVo
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionStatusRequest
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount
import interview.guide.modules.knowledgebase.service.KnowledgeBaseInterviewService
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 知识库面试与题目管理控制器
 */
@RestController
@Validated
class KnowledgeBaseInterviewController(
    private val questionService: KnowledgeBaseQuestionService, // 题目服务
    private val interviewService: KnowledgeBaseInterviewService // 知识库面试服务
) {

    /**
     * 查询知识库题目列表
     */
    @GetMapping("/api/knowledgebase/{id}/questions")
    fun listQuestions(
        @PathVariable id: Long,
        @RequestParam(value = "status", required = false) status: KnowledgeBaseQuestionStatus?,
        @RequestParam(value = "category", required = false) category: String?,
        @RequestParam(value = "difficulty", required = false) difficulty: String?,
        @RequestParam(value = "keyword", required = false) keyword: String?
    ): Result<List<KnowledgeBaseQuestionVo>> {
        return Result.success(questionService.listQuestions(id, status, category, difficulty, keyword))
    }

    /**
     * 查询知识库题目方向列表（含计数）
     */
    @GetMapping("/api/knowledgebase/{id}/questions/categories")
    fun listCategories(@PathVariable id: Long): Result<List<CategoryCount>> {
        return Result.success(questionService.listCategories(id))
    }

    /**
     * 提交异步题目生成任务
     */
    @PostMapping("/api/knowledgebase/{id}/questions/generate")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2.0)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2.0)
    fun generateQuestions(
        @PathVariable id: Long,
        @Valid @RequestBody request: GenerateKnowledgeBaseQuestionsRequest
    ): Result<QuestionGenStatusResponse> {
        return Result.success(questionService.submitGenerationTask(id, request))
    }

    /**
     * 查询题目生成任务状态
     */
    @GetMapping("/api/knowledgebase/{id}/questions/generation-status")
    fun getQuestionGenerationStatus(@PathVariable id: Long): Result<QuestionGenStatusResponse> {
        return Result.success(questionService.getGenerationStatus(id))
    }

    /**
     * 手动创建题目
     */
    @PostMapping("/api/knowledgebase/{id}/questions")
    fun createQuestion(
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateKnowledgeBaseQuestionRequest
    ): Result<KnowledgeBaseQuestionVo> {
        return Result.success(questionService.createQuestion(id, request))
    }

    /**
     * 更新题目
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}")
    fun updateQuestion(
        @PathVariable questionId: Long,
        @RequestBody request: UpdateKnowledgeBaseQuestionRequest
    ): Result<KnowledgeBaseQuestionVo> {
        return Result.success(questionService.updateQuestion(questionId, request))
    }

    /**
     * 更新题目状态
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}/status")
    fun updateQuestionStatus(
        @PathVariable questionId: Long,
        @Valid @RequestBody request: UpdateKnowledgeBaseQuestionStatusRequest
    ): Result<KnowledgeBaseQuestionVo> {
        val status = request.status ?: return Result.error("题目状态不能为空")
        return Result.success(questionService.updateStatus(questionId, status))
    }

    /**
     * 删除题目
     */
    @DeleteMapping("/api/knowledgebase/questions/{questionId}")
    fun deleteQuestion(@PathVariable questionId: Long): Result<Void> {
        questionService.deleteQuestion(questionId)
        return Result.success(null)
    }

    /**
     * 创建知识库面试会话
     */
    @PostMapping("/api/knowledgebase-interviews/sessions")
    fun createInterviewSession(
        @Valid @RequestBody request: CreateKnowledgeBaseInterviewRequest
    ): Result<InterviewSessionVo> {
        return Result.success(interviewService.createSession(request))
    }

    /**
     * 查询知识库面试容量
     */
    @GetMapping("/api/knowledgebase/{id}/interview-capacity")
    fun getInterviewCapacity(
        @PathVariable id: Long,
        @RequestParam(value = "category", required = false) category: String?,
        @RequestParam(value = "difficulty", defaultValue = "mid") difficulty: String,
        @RequestParam(value = "mainQuestionCount", defaultValue = "5")
        @Min(value = 1, message = "主问题数量最少1题")
        @Max(value = 20, message = "主问题数量最多20题")
        mainQuestionCount: Int
    ): Result<KnowledgeBaseInterviewCapacityVo> {
        return Result.success(
            interviewService.getCapacity(id, category, difficulty, mainQuestionCount)
        )
    }
}

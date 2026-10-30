package interview.guide.modules.interviewschedule

import interview.guide.common.result.Result
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest
import interview.guide.modules.interviewschedule.model.InterviewScheduleVo
import interview.guide.modules.interviewschedule.model.InterviewStatus
import interview.guide.modules.interviewschedule.model.ParseRequest
import interview.guide.modules.interviewschedule.model.ParseResponseVo
import interview.guide.modules.interviewschedule.service.InterviewParseService
import interview.guide.modules.interviewschedule.service.InterviewScheduleService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * 面试日程管理控制器
 * Interview Schedule Management Controller
 */
@RestController
@RequestMapping("/api/interview-schedule")
class InterviewScheduleController(
    private val scheduleService: InterviewScheduleService, // 面试日程服务
    private val parseService: InterviewParseService // 面试邀约解析服务
) {

    private val log = LoggerFactory.getLogger(InterviewScheduleController::class.java)

    /**
     * 解析面试邀约文本
     *
     * @param request 解析请求
     * @return 解析结果
     */
    @PostMapping("/parse")
    fun parse(@Valid @RequestBody request: ParseRequest): Result<ParseResponseVo> {
        log.info("接收到解析请求，来源: {}", request.source)
        val response = parseService.parse(request.rawText, request.source)
        return Result.success(response)
    }

    /**
     * 创建面试记录
     *
     * @param request 创建请求
     * @return 创建的面试记录
     */
    @PostMapping
    fun create(@Valid @RequestBody request: CreateInterviewRequest): Result<InterviewScheduleVo> {
        log.info("创建面试记录: {} - {}", request.companyName, request.position)
        val dto = scheduleService.create(request)
        return Result.success(dto)
    }

    /**
     * 根据ID获取面试记录
     *
     * @param id 面试记录ID
     * @return 面试记录详情
     */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): Result<InterviewScheduleVo> {
        val dto = scheduleService.getById(id)
        return Result.success(dto)
    }

    /**
     * 获取面试记录列表
     *
     * @param status 状态过滤（可选）
     * @param start 开始时间（可选）
     * @param end 结束时间（可选）
     * @return 面试记录列表
     */
    @GetMapping
    fun getAll(
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "start", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime?,
        @RequestParam(name = "end", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime?
    ): Result<List<InterviewScheduleVo>> {
        val list = scheduleService.getAll(status, start, end)
        return Result.success(list)
    }

    /**
     * 更新面试记录
     *
     * @param id 面试记录ID
     * @param request 更新请求
     * @return 更新后的面试记录
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateInterviewRequest
    ): Result<InterviewScheduleVo> {
        log.info("更新面试记录: ID={}", id)
        val dto = scheduleService.update(id, request)
        return Result.success(dto)
    }

    /**
     * 删除面试记录
     *
     * @param id 面试记录ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): Result<Void> {
        log.info("删除面试记录: ID={}", id)
        scheduleService.delete(id)
        return Result.success<Void>(null)
    }

    /**
     * 更新面试状态
     *
     * @param id 面试记录ID
     * @param status 新状态
     * @return 更新后的面试记录
     */
    @RequestMapping(path = ["/{id}/status"], method = [RequestMethod.PATCH, RequestMethod.PUT])
    fun updateStatus(
        @PathVariable id: Long,
        @RequestParam(name = "status") status: InterviewStatus
    ): Result<InterviewScheduleVo> {
        log.info("更新面试状态: ID={}, status={}", id, status)
        val dto = scheduleService.updateStatus(id, status)
        return Result.success(dto)
    }
}

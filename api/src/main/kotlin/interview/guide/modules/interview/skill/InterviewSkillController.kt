package interview.guide.modules.interview.skill

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 面试方向 Skill 控制器
 * 提供 Skill 列表、详情与 JD 解析接口
 */
@RestController
@RequestMapping("/api/interview/skills")
class InterviewSkillController(
    private val skillService: InterviewSkillService // 面试方向 Skill 服务
) {

    /**
     * 获取全部预设 Skill
     */
    @GetMapping
    fun listSkills(): Result<List<InterviewSkillService.SkillDTO>> {
        return Result.success(skillService.getAllSkills())
    }

    /**
     * 根据 ID 获取 Skill
     */
    @GetMapping("/{id}")
    fun getSkill(@PathVariable id: String): Result<InterviewSkillService.SkillDTO> {
        return Result.success(skillService.getSkill(id))
    }

    /**
     * 解析 JD，提取面试方向分类
     */
    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5.0)
    fun parseJd(@Valid @RequestBody request: ParseJdRequest): Result<List<InterviewSkillService.CategoryDTO>> {
        return Result.success(skillService.parseJd(request.jdText))
    }

    /**
     * JD 解析请求
     */
    data class ParseJdRequest(
        @field:NotBlank(message = "JD 内容不能为空")
        val jdText: String = "" // JD 原文
    )
}

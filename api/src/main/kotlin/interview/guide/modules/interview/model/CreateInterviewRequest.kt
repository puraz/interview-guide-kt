package interview.guide.modules.interview.model

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 创建面试会话请求
 */
data class CreateInterviewRequest(
    val resumeText: String? = null, // 简历文本内容（可选，无简历时为通用面试）

    @field:Min(value = 3, message = "题目数量最少3题")
    @field:Max(value = 20, message = "题目数量最多20题")
    val questionCount: Int = 3, // 面试题目数量 (3-20)

    val resumeId: Long? = null, // 简历ID（可选，无简历时不传）

    val forceCreate: Boolean? = null, // 是否强制创建新会话（忽略未完成的会话），默认为 false

    val llmProvider: String? = null, // LLM提供商

    @field:NotBlank(message = "面试主题不能为空")
    val skillId: String = "", // 面试主题 ID（如 java-backend, frontend, custom 等）

    val difficulty: String? = null, // 难度级别: junior / mid / senior

    val customCategories: List<CategoryDTO>? = null, // 自定义面试的分类（JD 解析结果）

    val jdText: String? = null, // JD 原文（自定义面试时作为出题依据）

    val requestId: String? = null // 创建请求幂等键，刷新/重试时复用同一会话
)

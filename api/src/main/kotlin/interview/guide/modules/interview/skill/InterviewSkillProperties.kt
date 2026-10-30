package interview.guide.modules.interview.skill

/**
 * 面试 Skill 配置结构定义（对应 SKILL.md front matter 与 skill.meta.yml）
 */
class InterviewSkillProperties private constructor() {

    /**
     * SKILL.md front matter（标准字段）。
     */
    class SkillFrontMatterDefinition {
        var name: String? = null // Skill 名称
        var description: String? = null // Skill 描述
    }

    /**
     * skill.meta.yml（项目自定义字段）。
     */
    class SkillMetaDefinition {
        var displayName: String? = null // 展示名称
        var display: DisplayDef? = null // 展示样式（图标/渐变等）
        var categories: List<CategoryDef>? = emptyList() // 分类配置
    }

    /**
     * 运行时聚合结构：标准字段 + 项目自定义字段。
     */
    class SkillDefinition {
        var name: String? = null // Skill 名称
        var description: String? = null // Skill 描述
        var persona: String? = null // SKILL.md 正文（面试官角色与风格）
        var displayName: String? = null // 展示名称
        var display: DisplayDef? = null // 展示样式
        var categories: List<CategoryDef>? = emptyList() // 分类配置
    }

    /**
     * 展示样式定义
     */
    class DisplayDef {
        var icon: String? = null // 图标
        var gradient: String? = null // 渐变色
        var iconBg: String? = null // 图标背景色
        var iconColor: String? = null // 图标颜色
    }

    /**
     * 分类定义
     */
    class CategoryDef {
        var key: String? = null // 分类 key（如 MYSQL）
        var label: String? = null // 展示标签（如 MySQL）
        var priority: String? = null // 优先级：ALWAYS_ONE / CORE / NORMAL
        var ref: String? = null // 参考文件名
        var shared: Boolean? = null // 是否为共享参考文件
    }
}

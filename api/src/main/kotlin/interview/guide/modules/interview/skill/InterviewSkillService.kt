package interview.guide.modules.interview.skill

import com.fasterxml.jackson.annotation.JsonProperty
import interview.guide.common.ai.LlmProviderRegistry
import interview.guide.common.ai.PromptSanitizer
import interview.guide.common.ai.PromptSecurityConstants
import interview.guide.common.ai.StructuredOutputInvoker
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 面试方向 Skill 管理：分类分配、References 注入、自定义 Skill 构建。
 *
 * 与 SkillsTool 互补：SkillsTool 负责 LLM 按需加载 persona（SKILL.md body），
 * 本类负责后端解析分类配置（skill.meta.yml）并批量注入 references 到 Prompt。
 */
@Service
class InterviewSkillService(
    private val llmProviderRegistry: LlmProviderRegistry, // LLM Provider 注册表
    private val structuredOutputInvoker: StructuredOutputInvoker, // 结构化输出调用封装
    private val resourceLoader: ResourceLoader, // classpath 资源加载器
    private val promptSanitizer: PromptSanitizer // Prompt 注入净化工具
) {

    companion object {
        private val log = LoggerFactory.getLogger(InterviewSkillService::class.java)

        const val CUSTOM_SKILL_ID: String = "custom" // 自定义面试 Skill ID

        private const val MIN_JD_LENGTH: Int = 50 // JD 最小长度
        private const val MAX_CATEGORY_LABEL_LENGTH: Int = 50 // 分类标签最大长度
        private const val MAX_CATEGORY_KEY_LENGTH: Int = 50 // 分类 key 最大长度

        private val FRONT_MATTER_PATTERN: Pattern =
            Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$") // SKILL.md front matter
        private val SKILL_ID_PATTERN: Pattern = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$") // 从路径提取 skillId
        private const val SKILL_META_FILE: String = "skill.meta.yml" // Skill 元数据文件名
        private const val JD_PARSE_SYSTEM_PROMPT_PATH: String = "classpath:prompts/jd-parse-system.st" // JD 解析系统提示词

        private const val MAX_REFERENCE_SECTION_CHARS: Int = 12000 // 出题参考区最大长度
        private const val MAX_EVALUATION_REFERENCE_SECTION_CHARS: Int = 6000 // 评估参考区最大长度
        private const val MAX_SINGLE_REFERENCE_CHARS: Int = 3000 // 单文件参考最大长度
    }

    private val jdOutputConverter: BeanOutputConverter<CategoryListDTO> =
        BeanOutputConverter(CategoryListDTO::class.java) // JD 解析输出转换器

    private val jdSystemPromptTemplate: PromptTemplate =
        PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH)) // JD 解析系统提示词模板

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private val presetRegistry: TreeMap<String, InterviewSkillProperties.SkillDefinition> = TreeMap()

    /** 参考内容缓存（classpath 资源不可变，加载一次后复用） */
    private val referenceCache: MutableMap<String, String> = ConcurrentHashMap()

    /** 全局 category key → (ref文件名, 是否shared) 映射，启动时构建，之后只读 */
    private val categoryRefIndex: MutableMap<String, RefMapping> = HashMap()

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private var cachedReferenceFileList: String? = null

    /**
     * category key 对应的参考文件映射
     */
    private data class RefMapping(
        val ref: String?, // 参考文件名
        val shared: Boolean, // 是否共享
        val sourceSkillId: String // 来源 Skill ID
    )

    /**
     * 加载预设 Skill：扫描 classpath 下 skills 目录的 SKILL.md 并解析
     */
    @PostConstruct
    fun loadPresetSkills() {
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath:skills/*/SKILL.md")
        val yaml = Yaml()

        for (resource in resources) {
            val skillId = extractSkillId(resource) ?: continue
            if ("_shared" == skillId) {
                continue
            }

            val def = parseSkillDefinition(skillId, resource, yaml)
            if (def.name.isNullOrBlank()) {
                log.warn("跳过无效 Skill（缺少 name）: {}", skillId)
                continue
            }

            presetRegistry[skillId] = def
            log.info("加载预设 Skill: {} ({})", skillId, def.name)
        }

        log.info("共加载 {} 个预设 Skill", presetRegistry.size)

        buildCategoryRefIndex()
        cachedReferenceFileList = buildReferenceFileList()
    }

    /**
     * 构建 category → reference 映射
     */
    private fun buildCategoryRefIndex() {
        categoryRefIndex.clear()
        for (entry in presetRegistry.entries) {
            val def = entry.value
            val categories = def.categories ?: continue
            for (cat in categories) {
                if (!cat.ref.isNullOrBlank() && cat.key != null) {
                    val key = cat.key ?: continue
                    categoryRefIndex.putIfAbsent(
                        key,
                        RefMapping(cat.ref, cat.shared == true, entry.key)
                    )
                }
            }
        }
        log.info("构建 category→reference 映射: {} 个条目", categoryRefIndex.size)
    }

    /**
     * 获取全部预设 Skill
     */
    fun getAllSkills(): List<SkillDTO> {
        return presetRegistry.entries.map { (key, value) -> toSkillDTO(key, value) }
    }

    /**
     * 根据 skillId 获取 Skill
     */
    fun getSkill(skillId: String): SkillDTO {
        val preset = presetRegistry[skillId]
        if (preset != null) {
            return toSkillDTO(skillId, preset)
        }
        throw BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: $skillId")
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     */
    fun buildCustomSkill(customCategories: List<CategoryDTO>, jdText: String): SkillDTO {
        val categories = customCategories
            .filter { cat -> cat.key != null && cat.label != null }
            .map { cat ->
                val safeKey = sanitizeCategoryKey(cat.key)
                val safeLabel = sanitizeCategoryLabel(cat.label)
                val refMapping = categoryRefIndex[safeKey]
                if (refMapping != null) {
                    if (refMapping.ref != cat.ref || refMapping.shared != (cat.shared == true)) {
                        log.info(
                            "JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                            safeKey, cat.ref, cat.shared, refMapping.ref, refMapping.shared
                        )
                    }
                    SkillCategoryDTO(safeKey, safeLabel, cat.priority, refMapping.ref, refMapping.shared)
                } else {
                    SkillCategoryDTO(safeKey, safeLabel, cat.priority, cat.ref, cat.shared == true)
                }
            }

        val matchedCount = categories.count { c -> !c.ref.isNullOrBlank() }
        log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size, matchedCount)

        return SkillDTO(
            id = CUSTOM_SKILL_ID,
            name = "自定义面试（JD 解析）",
            description = "基于职位描述提取的面试方向",
            categories = categories,
            isPreset = false,
            sourceJd = jdText,
            persona = null,
            display = null
        )
    }

    /**
     * 解析 JD，返回分类列表
     */
    fun parseJd(jdText: String?): List<CategoryDTO> {
        if (jdText == null || jdText.length < MIN_JD_LENGTH) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 $MIN_JD_LENGTH 字），请补充后重试")
        }

        log.info("开始解析 JD，长度: {}", jdText.length)

        val chatClient: ChatClient = llmProviderRegistry.getDefaultChatClient()
        val systemPrompt = jdSystemPromptTemplate.render(
            mapOf<String, Any>("referenceFileList" to (cachedReferenceFileList ?: ""))
        ) + "\n\n" + jdOutputConverter.format
        val userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "职位描述：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(jdText))

        try {
            val result = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, jdOutputConverter,
                ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log
            )

            val categories = result.categories
            if (categories.isNullOrEmpty()) {
                throw BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试")
            }

            val refMatched = categories.count { c -> !c.ref.isNullOrBlank() }
            log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", categories.size, refMatched)
            return categories
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("JD 解析失败: {}", e.message, e)
            throw BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题")
        }
    }

    /**
     * 构建 JD 解析用的参考文件清单（Markdown 表格）
     */
    private fun buildReferenceFileList(): String {
        // 收集所有去重的参考文件（文件名 → Markdown 表格行）
        val refDescriptions = LinkedHashMap<String, String>()
        for (entry in presetRegistry.entries) {
            val skillName = entry.value.displayName ?: entry.value.name
            val categories = entry.value.categories ?: continue
            for (cat in categories) {
                val refFile = cat.ref
                if (!refFile.isNullOrBlank()) {
                    refDescriptions.putIfAbsent(
                        refFile,
                        "| " + refFile +
                            " | " + (if (cat.shared == true) "shared" else "skill-local") +
                            " | " + skillName +
                            " | " + cat.label + " |\n"
                    )
                }
            }
        }

        if (refDescriptions.isEmpty()) {
            return "（无可用参考文件）"
        }

        val sb = StringBuilder("| 文件名 | 范围 | 来源 Skill | 覆盖内容 |\n")
        sb.append("|--------|------|-------------|----------|\n")
        for (row in refDescriptions.values) {
            sb.append(row)
        }
        return sb.toString()
    }

    /**
     * 按 skillId 计算题目分配
     */
    fun calculateAllocation(skillId: String, totalQuestions: Int): Map<String, Int> {
        return calculateAllocation(getSkill(skillId).categories, totalQuestions)
    }

    /**
     * 计算每个分类的题目数量：ALWAYS_ONE 保底 → 全类目覆盖 → CORE 优先轮转
     */
    fun calculateAllocation(categories: List<SkillCategoryDTO>, totalQuestions: Int): Map<String, Int> {
        val alwaysOneCats = ArrayList<SkillCategoryDTO>()
        val coreCats = ArrayList<SkillCategoryDTO>()
        val normalCats = ArrayList<SkillCategoryDTO>()

        for (cat in categories) {
            when (cat.priority) {
                "ALWAYS_ONE" -> alwaysOneCats.add(cat)
                "CORE" -> coreCats.add(cat)
                else -> normalCats.add(cat)
            }
        }

        val allocation = LinkedHashMap<String, Int>()
        var remaining = totalQuestions

        // Phase 1: ALWAYS_ONE 保底各 1 题
        for (cat in alwaysOneCats) {
            val key = cat.key ?: continue
            if (remaining > 0) {
                allocation[key] = 1
                remaining--
            }
        }

        // Phase 2: 先给所有类目各 1 题（CORE 优先），保证覆盖率
        for (cat in coreCats) {
            val key = cat.key ?: continue
            if (remaining > 0) {
                allocation[key] = 1
                remaining--
            }
        }
        for (cat in normalCats) {
            val key = cat.key ?: continue
            if (remaining > 0) {
                allocation[key] = 1
                remaining--
            }
        }

        // Phase 3: 剩余名额按 CORE 优先轮转分配
        while (remaining > 0) {
            for (cat in coreCats) {
                val key = cat.key ?: continue
                if (remaining <= 0) break
                allocation[key] = (allocation[key] ?: 0) + 1
                remaining--
            }
            for (cat in normalCats) {
                val key = cat.key ?: continue
                if (remaining <= 0) break
                allocation[key] = (allocation[key] ?: 0) + 1
                remaining--
            }
            if (coreCats.isEmpty() && normalCats.isEmpty()) break
        }

        // 确保所有类目都出现在 allocation 中
        for (cat in coreCats) {
            cat.key?.let { allocation.putIfAbsent(it, 0) }
        }
        for (cat in normalCats) {
            cat.key?.let { allocation.putIfAbsent(it, 0) }
        }

        log.debug("题目分配: total={}, allocation={}", totalQuestions, allocation)
        return allocation
    }

    /**
     * 构建题目分配说明（Markdown 表格行）
     */
    fun buildAllocationDescription(allocation: Map<String, Int>, categories: List<SkillCategoryDTO>): String {
        val sb = StringBuilder()
        for (cat in categories) {
            val count = cat.key?.let { allocation[it] } ?: 0
            if (count > 0) {
                sb.append("| ").append(cat.label).append(" | ").append(count).append(" 题 | ")
                    .append(cat.priority).append(" |\n")
            }
        }
        return sb.toString()
    }

    /**
     * 出题阶段参考基线：只覆盖分配题目数量大于 0 的分类
     */
    fun buildReferenceSection(skill: SkillDTO, allocation: Map<String, Int>): String {
        return buildReferenceSectionInternal(
            skill,
            { category -> (category.key?.let { allocation[it] } ?: 0) > 0 },
            MAX_REFERENCE_SECTION_CHARS
        )
    }

    /**
     * 评估阶段参考基线：不限制题量分配，覆盖该 skill 下所有配置了 reference 的分类。
     */
    fun buildEvaluationReferenceSection(skillId: String): String {
        val skill = getSkill(skillId)
        return buildReferenceSectionInternal(
            skill,
            { true },
            MAX_EVALUATION_REFERENCE_SECTION_CHARS
        )
    }

    /**
     * 安全版本的评估参考基线：skillId 为空或加载失败时返回空字符串，不抛异常。
     */
    fun buildEvaluationReferenceSectionSafe(skillId: String?): String {
        if (skillId.isNullOrBlank()) {
            return ""
        }
        try {
            return buildEvaluationReferenceSection(skillId)
        } catch (e: Exception) {
            log.warn("加载评估参考基线失败，降级为无参考: skillId={}, error={}", skillId, e.message)
            return ""
        }
    }

    /**
     * 按过滤条件拼装 references 文本，超长截断
     */
    private fun buildReferenceSectionInternal(
        skill: SkillDTO,
        categoryFilter: (SkillCategoryDTO) -> Boolean,
        maxChars: Int
    ): String {
        val sb = StringBuilder()

        for (category in skill.categories) {
            if (!categoryFilter(category)) {
                continue
            }
            val referenceFile = category.ref
            if (referenceFile.isNullOrBlank()) {
                continue
            }

            // custom 模式下非 shared 的 ref 需要查原始 skillId 拼路径
            var effectiveSkillId = skill.id
            if (CUSTOM_SKILL_ID == skill.id && !category.shared) {
                val mapping = category.key?.let { categoryRefIndex[it] }
                if (mapping != null) {
                    effectiveSkillId = mapping.sourceSkillId
                }
            }
            val referenceContent = loadReferenceContent(effectiveSkillId, referenceFile, category.shared)
            if (referenceContent.isBlank()) {
                continue
            }

            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append("### ").append(category.label).append(" (").append(category.key).append(")\n")
            sb.append(referenceContent)

            if (sb.length >= maxChars) {
                sb.setLength(maxChars)
                sb.append("\n...（references 已截断）")
                break
            }
        }

        return if (sb.isEmpty()) "未配置 references。" else sb.toString()
    }

    /**
     * 读取 classpath 提示词
     */
    private fun loadClasspathPrompt(path: String): String {
        val resource: Resource = resourceLoader.getResource(path)
        return resource.getContentAsString(StandardCharsets.UTF_8)
    }

    /**
     * 解析 SKILL.md（front matter + 正文）与 skill.meta.yml
     */
    private fun parseSkillDefinition(
        skillId: String,
        resource: Resource,
        yaml: Yaml
    ): InterviewSkillProperties.SkillDefinition {
        try {
            val markdown = resource.getContentAsString(StandardCharsets.UTF_8)
            val matcher = FRONT_MATTER_PATTERN.matcher(markdown)
            if (!matcher.matches()) {
                throw BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Skill 文件格式错误（缺少 front matter）: " + resource.description
                )
            }

            val frontMatter = matcher.group(1)
            val body = matcher.group(2)?.trim() ?: ""

            val frontMatterDef = yaml.loadAs(frontMatter, InterviewSkillProperties.SkillFrontMatterDefinition::class.java)

            val definition = InterviewSkillProperties.SkillDefinition()
            if (frontMatterDef != null) {
                definition.name = frontMatterDef.name
                definition.description = frontMatterDef.description
            }
            if (body.isNotBlank()) {
                definition.persona = body
            }

            val metaDef = loadSkillMetaDefinition(skillId, yaml)
            if (metaDef != null) {
                definition.displayName = metaDef.displayName
                definition.display = metaDef.display
                definition.categories = metaDef.categories
            }

            if (definition.categories == null) {
                definition.categories = emptyList()
            }
            return definition
        } catch (e: IOException) {
            throw BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "读取 Skill 文件失败: " + resource.description
            )
        }
    }

    /**
     * 加载 skill.meta.yml
     */
    private fun loadSkillMetaDefinition(
        skillId: String,
        yaml: Yaml
    ): InterviewSkillProperties.SkillMetaDefinition? {
        val location = "classpath:skills/$skillId/$SKILL_META_FILE"
        val resource = resourceLoader.getResource(location)
        if (!resource.exists()) {
            log.warn("skill meta 文件不存在，使用默认配置: skillId={}, location={}", skillId, location)
            return null
        }

        try {
            val content = resource.getContentAsString(StandardCharsets.UTF_8)
            val meta = yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition::class.java)
            return meta ?: InterviewSkillProperties.SkillMetaDefinition()
        } catch (e: IOException) {
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: $location")
        }
    }

    /**
     * 从资源路径提取 skillId
     */
    private fun extractSkillId(resource: Resource): String? {
        return try {
            val normalized = resource.url.toString().replace('\\', '/')
            val matcher = SKILL_ID_PATTERN.matcher(normalized)
            if (matcher.matches()) matcher.group(1) else null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 读取参考文件内容（带缓存）
     */
    private fun loadReferenceContent(skillId: String, referenceFile: String, shared: Boolean): String {
        if (!isSafeReferencePath(referenceFile)) {
            log.warn("忽略不安全的 reference 路径: skillId={}, ref={}", skillId, referenceFile)
            return ""
        }

        val candidateLocations = resolveReferenceLocations(skillId, referenceFile, shared)
        for (location in candidateLocations) {
            val content = referenceCache.getOrPut(location) { readReferenceContent(location) }
            if (content.isNotBlank()) {
                return content
            }
        }

        log.warn(
            "未找到 reference: skillId={}, ref={}, shared={}, locations={}",
            skillId, referenceFile, shared, candidateLocations
        )
        return ""
    }

    /**
     * 解析参考文件的候选路径（shared / skill-local / 全量兜底）
     */
    private fun resolveReferenceLocations(
        skillId: String,
        referenceFile: String,
        shared: Boolean
    ): List<String> {
        val locations = LinkedHashSet<String>()
        if (shared) {
            locations.add(buildSharedReferenceLocation(referenceFile))
        }

        addSkillReferenceLocations(locations, skillId, referenceFile)

        if (!shared) {
            locations.add(buildSharedReferenceLocation(referenceFile))
        }

        if (CUSTOM_SKILL_ID == skillId || shared) {
            for (presetSkillId in presetRegistry.keys) {
                addSkillReferenceLocations(locations, presetSkillId, referenceFile)
            }
        }

        return locations.toList()
    }

    /**
     * 追加单个 Skill 的候选路径
     */
    private fun addSkillReferenceLocations(
        locations: MutableSet<String>,
        skillId: String?,
        referenceFile: String
    ) {
        if (skillId.isNullOrBlank() || CUSTOM_SKILL_ID == skillId) {
            return
        }
        locations.add("classpath:skills/$skillId/references/$referenceFile")
        locations.add("classpath:skills/$skillId/$referenceFile")
    }

    /**
     * 共享参考文件路径
     */
    private fun buildSharedReferenceLocation(referenceFile: String): String {
        return "classpath:skills/_shared/references/$referenceFile"
    }

    /**
     * 读取参考文件并截断超长内容
     */
    private fun readReferenceContent(location: String): String {
        val resource = resourceLoader.getResource(location)
        if (!resource.exists()) {
            return ""
        }

        return try {
            val content = resource.getContentAsString(StandardCharsets.UTF_8).trim()
            if (content.length > MAX_SINGLE_REFERENCE_CHARS) {
                content.substring(0, MAX_SINGLE_REFERENCE_CHARS) + "\n...（单文件内容已截断）"
            } else {
                content
            }
        } catch (e: IOException) {
            log.warn("读取 reference 失败: location={}", location, e)
            ""
        }
    }

    /**
     * 校验 reference 路径安全（禁止目录穿越与绝对路径）
     */
    private fun isSafeReferencePath(referenceFile: String): Boolean {
        return !referenceFile.contains("..") &&
            !referenceFile.startsWith("/") &&
            !referenceFile.startsWith("\\") &&
            referenceFile.matches(Regex("[a-zA-Z0-9._/-]+"))
    }

    /**
     * 清洗 category key：截断长度，非法字符替换为下划线，转大写。
     * 首字符必须为字母，否则添加 "CAT_" 前缀。
     */
    private fun sanitizeCategoryKey(key: String?): String {
        if (key.isNullOrBlank()) {
            return "UNKNOWN"
        }
        var trimmed = key.trim()
        if (trimmed.length > MAX_CATEGORY_KEY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_KEY_LENGTH)
        }
        var upper = trimmed.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
        if (upper.isEmpty()) {
            return "UNKNOWN"
        }
        // 确保首字符为字母（匹配 category key 命名规范）
        if (!upper[0].isLetter()) {
            upper = "CAT_$upper"
        }
        return upper
    }

    /**
     * 清洗 category label：截断长度，移除换行。
     */
    private fun sanitizeCategoryLabel(label: String?): String {
        if (label.isNullOrBlank()) {
            return "未命名"
        }
        var trimmed = label.trim().replace(Regex("[\\r\\n]+"), " ")
        if (trimmed.length > MAX_CATEGORY_LABEL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_LABEL_LENGTH)
        }
        return trimmed
    }

    /**
     * 内部定义转 SkillDTO
     */
    private fun toSkillDTO(id: String, def: InterviewSkillProperties.SkillDefinition): SkillDTO {
        val skillDisplayName = if (!def.displayName.isNullOrBlank()) def.displayName else def.name

        val disp = def.display
        val displayDTO = disp?.let { DisplayDTO(it.icon, it.gradient, it.iconBg, it.iconColor) }

        val categories = def.categories?.map { c ->
            SkillCategoryDTO(
                c.key,
                c.label,
                c.priority,
                c.ref,
                c.shared == true
            )
        } ?: emptyList()

        return SkillDTO(
            id = id,
            name = skillDisplayName,
            description = def.description,
            categories = categories,
            isPreset = true,
            sourceJd = null,
            persona = def.persona,
            display = displayDTO
        )
    }

    /**
     * 面试 Skill 返回对象
     */
    data class SkillDTO(
        val id: String = "", // Skill ID
        val name: String? = null, // 展示名称
        val description: String? = null, // 描述
        val categories: List<SkillCategoryDTO> = emptyList(), // 分类列表
        @get:JsonProperty("isPreset")
        @param:JsonProperty("isPreset")
        val isPreset: Boolean = false, // 是否预设 Skill
        val sourceJd: String? = null, // 来源 JD（自定义 Skill）
        val persona: String? = null, // SKILL.md 正文
        val display: DisplayDTO? = null // 展示样式
    )

    /**
     * 展示样式返回对象
     */
    data class DisplayDTO(
        val icon: String? = null, // 图标
        val gradient: String? = null, // 渐变色
        val iconBg: String? = null, // 图标背景色
        val iconColor: String? = null // 图标颜色
    )

    /**
     * 预设 Skill 分类（可携带 references 绑定信息）
     */
    data class SkillCategoryDTO(
        val key: String? = null, // 分类 key
        val label: String? = null, // 展示标签
        val priority: String? = null, // 优先级
        val ref: String? = null, // 参考文件名
        val shared: Boolean = false // 是否共享
    )

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    data class CategoryDTO(
        val key: String? = null, // 分类 key
        val label: String? = null, // 展示标签
        val priority: String? = null, // 优先级
        val ref: String? = null, // 参考文件名
        val shared: Boolean? = null // 是否共享
    )

    /**
     * JD 解析结构化输出 DTO
     */
    private data class CategoryListDTO(
        val categories: List<CategoryDTO>? = null // 分类列表
    )
}

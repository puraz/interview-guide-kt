package interview.guide.modules.llmprovider.repository

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * LLM 全局设置仓库
 */
interface LlmGlobalSettingRepository : JpaRepository<LlmGlobalSettingEntity, Long>

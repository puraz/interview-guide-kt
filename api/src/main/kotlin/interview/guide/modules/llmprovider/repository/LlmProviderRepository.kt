package interview.guide.modules.llmprovider.repository

import interview.guide.modules.llmprovider.model.LlmProviderEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * LLM Provider 配置仓库
 */
interface LlmProviderRepository : JpaRepository<LlmProviderEntity, String> {

    /**
     * 查询所有启用中的 Provider，按 ID 升序
     *
     * @return 启用中的 Provider 列表
     */
    fun findByEnabledTrueOrderByIdAsc(): List<LlmProviderEntity>
}

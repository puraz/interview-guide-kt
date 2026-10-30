package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemVo
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsVo
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Service
class KnowledgeBaseListService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val ragChatMessageRepository: RagChatMessageRepository, // RAG消息仓库（统计提问次数）
    private val knowledgeBaseMapper: KnowledgeBaseMapper, // 知识库映射器
    private val fileStorageService: FileStorageService // 文件存储服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseListService::class.java)

    /**
     * 获取知识库列表（支持状态过滤和排序）
     *
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    fun listKnowledgeBases(vectorStatus: VectorStatus?, sortBy: String?): List<KnowledgeBaseListItemVo> {
        var entities: List<KnowledgeBaseEntity>

        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus)
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()
        }

        // 如果指定了排序字段，在内存中排序
        if (!sortBy.isNullOrBlank() && !sortBy.equals("time", ignoreCase = true)) {
            entities = sortEntities(entities, sortBy)
        }

        return knowledgeBaseMapper.toListItemDTOList(entities)
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    fun listKnowledgeBases(): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(null, null)
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    fun listKnowledgeBasesByStatus(vectorStatus: VectorStatus?): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(vectorStatus, null)
    }

    /**
     * 根据ID获取知识库详情
     */
    fun getKnowledgeBase(id: Long): Optional<KnowledgeBaseListItemVo> {
        return knowledgeBaseRepository.findById(id)
            .map { knowledgeBaseMapper.toListItemDTO(it) }
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    fun getKnowledgeBaseEntity(id: Long): Optional<KnowledgeBaseEntity> {
        return knowledgeBaseRepository.findById(id)
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    fun getKnowledgeBaseNames(ids: List<Long>): List<String> {
        return ids.map { id ->
            knowledgeBaseRepository.findById(id)
                .map { it.name ?: "未知知识库" }
                .orElse("未知知识库")
        }
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<String> {
        return knowledgeBaseRepository.findAllCategories()
    }

    /**
     * 根据分类获取知识库列表
     */
    fun listByCategory(category: String?): List<KnowledgeBaseListItemVo> {
        val entities: List<KnowledgeBaseEntity>
        if (category.isNullOrBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc()
        } else {
            entities = knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category)
        }
        return knowledgeBaseMapper.toListItemDTOList(entities)
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    fun updateCategory(id: Long, category: String?) {
        val entity = knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在")
        entity.category = if (!category.isNullOrBlank()) category else null
        knowledgeBaseRepository.save(entity)
        log.info("更新知识库分类: id={}, category={}", id, category)
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    fun search(keyword: String?): List<KnowledgeBaseListItemVo> {
        if (keyword.isNullOrBlank()) {
            return listKnowledgeBases()
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeyword(keyword.trim())
        )
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    fun listSorted(sortBy: String?): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(null, sortBy)
    }

    /**
     * 在内存中对实体列表排序
     */
    private fun sortEntities(entities: List<KnowledgeBaseEntity>, sortBy: String): List<KnowledgeBaseEntity> {
        return when (sortBy.lowercase()) {
            "size" -> entities.sortedWith(Comparator { a, b -> (b.fileSize ?: 0L).compareTo(a.fileSize ?: 0L) })
            "access" -> entities.sortedWith(
                Comparator { a, b -> (b.accessCount ?: 0).compareTo(a.accessCount ?: 0) }
            )
            "question" -> entities.sortedWith(
                Comparator { a, b -> (b.questionCount ?: 0).compareTo(a.questionCount ?: 0) }
            )
            else -> entities // time 已经在数据库层面排序了
        }
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    fun getStatistics(): KnowledgeBaseStatsVo {
        return KnowledgeBaseStatsVo(
            knowledgeBaseRepository.count(),
            ragChatMessageRepository.countByType(MessageType.USER), // 真正的提问次数
            knowledgeBaseRepository.sumAccessCount(),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        )
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    fun downloadFile(id: Long): ByteArray {
        val entity = knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在")

        val storageKey = entity.storageKey
        if (storageKey.isNullOrBlank()) {
            throw BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在")
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.originalFilename)
        return fileStorageService.downloadFile(storageKey)
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    fun getEntityForDownload(id: Long): KnowledgeBaseEntity {
        return knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在")
    }
}

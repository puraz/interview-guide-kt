package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.KnowledgeBaseEntity
import interview.guide.entity.RagChatMessageEntity
import interview.guide.entity.VectorStatus
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.repository.KnowledgeBaseRepository
import interview.guide.repository.RagChatMessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Service
class KnowledgeBaseListService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val ragChatMessageRepository: RagChatMessageRepository, // 消息仓库
    private val fileStorageService: FileStorageService // 文件存储服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseListService::class.java)

    /**
     * 获取知识库列表（支持状态过滤和排序）
     */
    fun listKnowledgeBases(vectorStatus: VectorStatus?, sortBy: String?): List<KnowledgeBaseListItemVo> {
        var entities = if (vectorStatus != null) {
            knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus)
        } else {
            knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()
        }

        if (!sortBy.isNullOrBlank() && !sortBy.equals("time", ignoreCase = true)) {
            entities = sortEntities(entities, sortBy)
        }

        return entities.map { toListItemVo(it) }
    }

    /**
     * 获取所有知识库列表
     */
    fun listKnowledgeBases(): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(null, null)
    }

    /**
     * 按向量化状态获取知识库列表
     */
    fun listKnowledgeBasesByStatus(vectorStatus: VectorStatus): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(vectorStatus, null)
    }

    /**
     * 根据ID获取知识库详情
     */
    fun getKnowledgeBase(id: Long): KnowledgeBaseListItemVo? {
        return knowledgeBaseRepository.findById(id).map { toListItemVo(it) }.orElse(null)
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    fun getKnowledgeBaseEntity(id: Long): KnowledgeBaseEntity? {
        return knowledgeBaseRepository.findById(id).orElse(null)
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    fun getKnowledgeBaseNames(ids: List<Long>): List<String> {
        return ids.map { id ->
            knowledgeBaseRepository.findById(id)
                .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: $id") }
                .name ?: ""
        }
    }

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
        val entities = if (category.isNullOrBlank()) {
            knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc()
        } else {
            knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category)
        }
        return entities.map { toListItemVo(it) }
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    fun updateCategory(id: Long, category: String?) {
        val entity = knowledgeBaseRepository.findById(id)
            .orElseThrow { RuntimeException("知识库不存在") }
        entity.category = if (!category.isNullOrBlank()) category else null
        knowledgeBaseRepository.save(entity)
        log.info("更新知识库分类: id={}, category={}", id, category)
    }

    /**
     * 按关键词搜索知识库
     */
    fun search(keyword: String?): List<KnowledgeBaseListItemVo> {
        if (keyword.isNullOrBlank()) {
            return listKnowledgeBases()
        }
        return knowledgeBaseRepository.searchByKeyword(keyword.trim()).map { toListItemVo(it) }
    }

    /**
     * 按指定字段排序获取知识库列表
     */
    fun listSorted(sortBy: String?): List<KnowledgeBaseListItemVo> {
        return listKnowledgeBases(null, sortBy)
    }

    private fun sortEntities(entities: List<KnowledgeBaseEntity>, sortBy: String): List<KnowledgeBaseEntity> {
        return when (sortBy.lowercase()) {
            "size" -> entities.sortedByDescending { it.fileSize ?: 0 }
            "access" -> entities.sortedByDescending { it.accessCount ?: 0 }
            "question" -> entities.sortedByDescending { it.questionCount ?: 0 }
            else -> entities
        }
    }

    /**
     * 获取知识库统计信息
     */
    fun getStatistics(): KnowledgeBaseStatsVo {
        return KnowledgeBaseStatsVo(
            totalCount = knowledgeBaseRepository.count(),
            totalQuestionCount = ragChatMessageRepository.countByType(RagChatMessageEntity.MessageType.USER),
            totalAccessCount = knowledgeBaseRepository.sumAccessCount(),
            completedCount = knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
            processingCount = knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        )
    }

    /**
     * 下载知识库文件
     */
    fun downloadFile(id: Long): ByteArray {
        val entity = knowledgeBaseRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在") }

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
        return knowledgeBaseRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在") }
    }

    private fun toListItemVo(entity: KnowledgeBaseEntity): KnowledgeBaseListItemVo {
        return KnowledgeBaseListItemVo(
            id = entity.id,
            name = entity.name,
            category = entity.category,
            originalFilename = entity.originalFilename,
            fileSize = entity.fileSize,
            contentType = entity.contentType,
            uploadedAt = entity.uploadedAt,
            lastAccessedAt = entity.lastAccessedAt,
            accessCount = entity.accessCount,
            questionCount = entity.questionCount,
            vectorStatus = entity.vectorStatus,
            vectorError = entity.vectorError,
            chunkCount = entity.chunkCount
        )
    }
}

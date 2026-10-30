package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.repository.VectorRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Service
class KnowledgeBasePersistenceService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val vectorRepository: VectorRepository // 向量存储仓库
) {

    private val log = LoggerFactory.getLogger(KnowledgeBasePersistenceService::class.java)

    /**
     * 处理重复知识库（更新访问计数）
     *
     * @param kb 已存在的知识库实体 // 重复命中的记录
     * @param fileHash 文件哈希 // 上传文件哈希
     * @return 上传响应 // 与新上传保持一致的响应契约
     */
    @Transactional(rollbackFor = [Exception::class])
    fun handleDuplicateKnowledgeBase(kb: KnowledgeBaseEntity, fileHash: String): Map<String, Any?> {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.id)

        // 更新访问计数（在事务中）
        kb.incrementAccessCount()
        knowledgeBaseRepository.save(kb)

        // 重复知识库的向量数据应该已经存在，不需要重新向量化；响应契约与新上传保持一致
        return mapOf(
            "knowledgeBase" to mapOf(
                "id" to kb.id,
                "name" to kb.name,
                "category" to (kb.category ?: ""),
                "fileSize" to kb.fileSize,
                "vectorStatus" to (kb.vectorStatus.name)
            ),
            "storage" to mapOf(
                "fileKey" to (kb.storageKey ?: ""),
                "fileUrl" to (kb.storageUrl ?: "")
            ),
            "enqueueAccepted" to true,
            "message" to "检测到重复文件，已返回现有知识库",
            "duplicate" to true
        )
    }

    /**
     * 向量化成功后的快照更新（独立短事务调用方保证）：Chunk 数、策略 JSON、完成时间。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateVectorizationSnapshot(knowledgeBaseId: Long, chunkCount: Int, vectorConfig: String) {
        knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null)?.let { kb ->
            kb.chunkCount = chunkCount
            kb.vectorConfig = vectorConfig
            kb.vectorizedAt = LocalDateTime.now()
            kb.vectorUpdatedAt = LocalDateTime.now()
            knowledgeBaseRepository.save(kb)
        }
    }

    /**
     * 在锁定知识库行后校验执行代次，并原子提升临时向量与写入配置快照。
     * Embedding 已在调用前完成，不会进入本数据库事务。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun activateVectorJobAndUpdateSnapshot(
        knowledgeBaseId: Long,
        attemptId: String,
        jobId: String,
        chunkCount: Int,
        vectorConfig: String
    ) {
        val kb = knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId).orElse(null)
            ?: throw BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND)
        if (kb.vectorStatus != VectorStatus.PROCESSING || kb.vectorAttemptId != attemptId) {
            throw BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化任务执行权已失效"
            )
        }
        vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)
        vectorRepository.promoteVectorJob(knowledgeBaseId, jobId)
        kb.chunkCount = chunkCount
        kb.vectorConfig = vectorConfig
        kb.vectorizedAt = LocalDateTime.now()
        kb.vectorUpdatedAt = LocalDateTime.now()
        knowledgeBaseRepository.save(kb)
    }

    /**
     * 保存新知识库元数据到数据库
     *
     * @param file 上传文件 // 原始文件
     * @param name 知识库名称 // 可为空，空时从文件名提取
     * @param category 分类 // 可为空
     * @param storageKey 存储Key // RustFS 对象键
     * @param storageUrl 存储URL // RustFS 访问地址
     * @param fileHash 文件哈希 // 去重标识
     * @return 已保存的知识库实体 // 持久化结果
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveKnowledgeBase(
        file: MultipartFile,
        name: String?,
        category: String?,
        storageKey: String,
        storageUrl: String,
        fileHash: String
    ): KnowledgeBaseEntity {
        try {
            val kb = KnowledgeBaseEntity()
            kb.fileHash = fileHash
            kb.name = if (!name.isNullOrBlank()) name else extractNameFromFilename(file.originalFilename)
            kb.category = if (!category.isNullOrBlank()) category.trim() else null
            kb.originalFilename = file.originalFilename
            kb.fileSize = file.size
            kb.contentType = file.contentType
            kb.storageKey = storageKey
            kb.storageUrl = storageUrl
            // 进展时间初始化：NULL 会被 stale 扫描的阈值比较排除，导致 PENDING 永远不被恢复
            kb.vectorUpdatedAt = LocalDateTime.now()

            val saved = knowledgeBaseRepository.save(kb)
            log.info(
                "知识库已保存: id={}, name={}, category={}, hash={}",
                saved.id, saved.name, saved.category, fileHash
            )
            return saved
        } catch (e: Exception) {
            log.error("保存知识库失败: {}", e.message, e)
            throw BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败")
        }
    }

    /**
     * 更新知识库向量化状态为 PENDING
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateVectorStatusToPending(kbId: Long) {
        val kb = knowledgeBaseRepository.findById(kbId).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "知识库不存在")

        kb.vectorStatus = VectorStatus.PENDING
        kb.vectorError = null
        kb.vectorAttemptId = null
        kb.vectorUpdatedAt = LocalDateTime.now()
        knowledgeBaseRepository.save(kb)

        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId)
    }

    /**
     * 从文件名提取知识库名称（去除扩展名）
     */
    private fun extractNameFromFilename(filename: String?): String {
        if (filename.isNullOrEmpty()) {
            return "未命名知识库"
        }
        val lastDot = filename.lastIndexOf('.')
        if (lastDot > 0) {
            return filename.substring(0, lastDot)
        }
        return filename
    }
}

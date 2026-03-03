package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.KnowledgeBaseEntity
import interview.guide.entity.VectorStatus
import interview.guide.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Service
class KnowledgeBasePersistenceService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository // 知识库仓库
) {

    private val log = LoggerFactory.getLogger(KnowledgeBasePersistenceService::class.java)

    /**
     * 处理重复知识库（更新访问计数）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun handleDuplicateKnowledgeBase(kb: KnowledgeBaseEntity, fileHash: String): Map<String, Any> {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.id)
        kb.incrementAccessCount()
        knowledgeBaseRepository.save(kb)

        return mapOf(
            "knowledgeBase" to mapOf(
                "id" to kb.id,
                "name" to kb.name,
                "fileSize" to kb.fileSize,
                "contentLength" to 0
            ),
            "storage" to mapOf(
                "fileKey" to (kb.storageKey ?: ""),
                "fileUrl" to (kb.storageUrl ?: "")
            ),
            "duplicate" to true
        )
    }

    /**
     * 保存新知识库元数据到数据库
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
            val kb = KnowledgeBaseEntity().apply {
                this.fileHash = fileHash
                this.name = if (!name.isNullOrBlank()) name.trim() else extractNameFromFilename(file.originalFilename)
                this.category = if (!category.isNullOrBlank()) category.trim() else null
                this.originalFilename = file.originalFilename
                this.fileSize = file.size
                this.contentType = file.contentType
                this.storageKey = storageKey
                this.storageUrl = storageUrl
            }
            val saved = knowledgeBaseRepository.save(kb)
            log.info("知识库已保存: id={}, name={}, category={}, hash={}", saved.id, saved.name, saved.category, fileHash)
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
        val kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "知识库不存在") }

        kb.vectorStatus = VectorStatus.PENDING
        kb.vectorError = null
        knowledgeBaseRepository.save(kb)
        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId)
    }

    /**
     * 从文件名提取知识库名称（去除扩展名）
     */
    private fun extractNameFromFilename(filename: String?): String {
        if (filename.isNullOrBlank()) {
            return "未命名知识库"
        }
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0) filename.substring(0, lastDot) else filename
    }
}

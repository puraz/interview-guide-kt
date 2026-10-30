package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.common.transaction.TransactionalExecutor
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Service
class KnowledgeBaseDeleteService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val sessionRepository: RagChatSessionRepository, // RAG会话仓库
    private val vectorService: KnowledgeBaseVectorService, // 向量服务
    private val storageService: FileStorageService, // 文件存储服务
    private val transactionalExecutor: TransactionalExecutor // 事务执行器
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseDeleteService::class.java)

    /**
     * 删除知识库
     * 包括：RAG会话关联、向量数据、RustFS文件、数据库记录
     *
     * @param id 知识库ID // 待删除知识库
     */
    fun deleteKnowledgeBase(id: Long) {
        val storageKey = transactionalExecutor.call { deleteKnowledgeBaseRecords(id) }

        vectorService.deleteByKnowledgeBaseId(id)

        try {
            storageService.deleteKnowledgeBase(storageKey)
        } catch (e: Exception) {
            log.warn(
                "知识库数据库记录已删除，但RustFS文件清理失败，可后续按storageKey补偿: kbId={}, storageKey={}, error={}",
                id, storageKey, e.message, e
            )
        }

        log.info("知识库已删除: id={}", id)
    }

    private fun deleteKnowledgeBaseRecords(id: Long): String? {
        // 1. 获取知识库信息
        val kb = knowledgeBaseRepository.findById(id).orElse(null)
            ?: throw BusinessException(ErrorCode.NOT_FOUND, "知识库不存在")
        val storageKey = kb.storageKey

        // 2. 删除所有RAG会话中的知识库关联（必须先删除关联，否则外键约束会阻止删除）
        val sessions = sessionRepository.findByKnowledgeBaseIds(listOf(id))
        for (session in sessions) {
            session.knowledgeBases.removeAll { it.id == id }
            sessionRepository.save(session)
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.id, id)
        }
        if (sessions.isNotEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size, id)
        }

        // 3. 删除知识库记录（在事务中）
        knowledgeBaseRepository.deleteById(id)
        return storageKey
    }
}

package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.file.FileStorageService
import interview.guide.repository.KnowledgeBaseRepository
import interview.guide.repository.RagChatSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Service
class KnowledgeBaseDeleteService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository, // 知识库仓库
    private val sessionRepository: RagChatSessionRepository, // 会话仓库
    private val vectorService: KnowledgeBaseVectorService, // 向量服务
    private val storageService: FileStorageService // 存储服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseDeleteService::class.java)

    /**
     * 删除知识库
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteKnowledgeBase(id: Long) {
        val kb = knowledgeBaseRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND, "知识库不存在") }

        val sessions = sessionRepository.findByKnowledgeBaseIds(listOf(id))
        for (session in sessions) {
            session.knowledgeBases.removeIf { it.id == id }
            sessionRepository.save(session)
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.id, id)
        }
        if (sessions.isNotEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size, id)
        }

        try {
            vectorService.deleteByKnowledgeBaseId(id)
        } catch (e: Exception) {
            log.warn("删除向量数据失败，继续删除知识库: kbId={}, error={}", id, e.message)
        }

        try {
            storageService.deleteKnowledgeBase(kb.storageKey)
        } catch (e: Exception) {
            log.warn("删除RustFS文件失败，继续删除知识库记录: kbId={}, error={}", id, e.message)
        }

        knowledgeBaseRepository.deleteById(id)
        log.info("知识库已删除: id={}", id)
    }
}

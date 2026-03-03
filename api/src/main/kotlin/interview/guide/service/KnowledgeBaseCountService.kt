package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.repository.KnowledgeBaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 知识库计数服务
 */
@Service
class KnowledgeBaseCountService(
    private val knowledgeBaseRepository: KnowledgeBaseRepository // 知识库仓库
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseCountService::class.java)

    /**
     * 批量更新知识库提问计数
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateQuestionCounts(knowledgeBaseIds: List<Long>?) {
        if (knowledgeBaseIds.isNullOrEmpty()) {
            return
        }
        val uniqueIds = knowledgeBaseIds.distinct()
        val existingIds = knowledgeBaseRepository.findAllById(uniqueIds)
            .mapNotNull { it.id }
            .toSet()

        for (id in uniqueIds) {
            if (!existingIds.contains(id)) {
                throw BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: $id")
            }
        }

        val updated = knowledgeBaseRepository.incrementQuestionCountBatch(uniqueIds)
        log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated)
    }
}

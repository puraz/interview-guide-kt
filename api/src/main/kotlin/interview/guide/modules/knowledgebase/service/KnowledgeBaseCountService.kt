package interview.guide.modules.knowledgebase.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository
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
     * 批量更新知识库提问计数（使用单条 SQL 批量更新）
     * 每个知识库的 questionCount +1，表示该知识库参与回答的次数
     *
     * @param knowledgeBaseIds 知识库ID列表
     */
    @Transactional(rollbackFor = [Exception::class])
    fun updateQuestionCounts(knowledgeBaseIds: List<Long>?) {
        if (knowledgeBaseIds.isNullOrEmpty()) {
            return
        }

        // 去重
        val uniqueIds = knowledgeBaseIds.distinct()

        // 验证所有知识库是否存在
        val existingIds = knowledgeBaseRepository.findAllById(uniqueIds)
            .mapNotNull { it.id }
            .toSet()

        for (id in uniqueIds) {
            if (!existingIds.contains(id)) {
                throw BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: $id")
            }
        }

        // 批量更新（单条 SQL）
        val updated = knowledgeBaseRepository.incrementQuestionCountBatch(uniqueIds)
        log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated)
    }
}

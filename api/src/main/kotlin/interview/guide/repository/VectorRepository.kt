package interview.guide.repository

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Repository
class VectorRepository(
    private val jdbcTemplate: JdbcTemplate // JDBC 模板
) {

    private val log = LoggerFactory.getLogger(VectorRepository::class.java)

    /**
     * 删除指定知识库的所有向量数据
     *
     * @param knowledgeBaseId 知识库ID // 知识库主键
     * @return 删除的行数 // 删除数量
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long): Int {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId)

        val sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
        """.trimIndent()

        return try {
            val deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId)
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows)
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId)
            }
            deletedRows
        } catch (e: Exception) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.message)
            throw RuntimeException("删除向量数据失败", e)
        }
    }
}

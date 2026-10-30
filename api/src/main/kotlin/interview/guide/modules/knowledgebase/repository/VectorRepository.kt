package interview.guide.modules.knowledgebase.repository

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

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
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     *
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     *
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    fun deleteByKnowledgeBaseId(knowledgeBaseId: Long): Int {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId)

        /*
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        val sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
        """.trimIndent()

        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            val deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId)

            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows)
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId)
            }

            return deletedRows
        } catch (e: Exception) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.message, e)
            // 抛出异常以触发事务回滚
            throw BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败")
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    fun deleteByVectorJobId(jobId: String): Int {
        val sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
        """.trimIndent()
        try {
            val deletedRows = jdbcTemplate.update(sql, jobId)
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows)
            return deletedRows
        } catch (e: Exception) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.message, e)
            throw BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败"
            )
        }
    }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    fun promoteVectorJob(knowledgeBaseId: Long, jobId: String): Int {
        val sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
        """.trimIndent()
        try {
            val updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId)
            log.info(
                "临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows
            )
            return updatedRows
        } catch (e: Exception) {
            log.error(
                "提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.message, e
            )
            throw BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败"
            )
        }
    }
}

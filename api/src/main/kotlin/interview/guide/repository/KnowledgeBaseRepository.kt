package interview.guide.repository

import interview.guide.entity.KnowledgeBaseEntity
import interview.guide.entity.VectorStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 知识库Repository
 */
@Repository
interface KnowledgeBaseRepository : JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    fun findByFileHash(fileHash: String): KnowledgeBaseEntity?

    /**
     * 检查文件哈希是否存在
     */
    fun existsByFileHash(fileHash: String): Boolean

    /**
     * 按上传时间倒序查找所有知识库
     */
    fun findAllByOrderByUploadedAtDesc(): List<KnowledgeBaseEntity>

    /**
     * 获取所有不同的分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    fun findAllCategories(): List<String>

    /**
     * 根据分类查找知识库
     */
    fun findByCategoryOrderByUploadedAtDesc(category: String?): List<KnowledgeBaseEntity>

    /**
     * 查找未分类的知识库
     */
    fun findByCategoryIsNullOrderByUploadedAtDesc(): List<KnowledgeBaseEntity>

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("""
        SELECT k FROM KnowledgeBaseEntity k
        WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY k.uploadedAt DESC
    """)
    fun searchByKeyword(@Param("keyword") keyword: String): List<KnowledgeBaseEntity>

    /**
     * 按文件大小排序
     */
    fun findAllByOrderByFileSizeDesc(): List<KnowledgeBaseEntity>

    /**
     * 按访问次数排序
     */
    fun findAllByOrderByAccessCountDesc(): List<KnowledgeBaseEntity>

    /**
     * 按提问次数排序
     */
    fun findAllByOrderByQuestionCountDesc(): List<KnowledgeBaseEntity>

    /**
     * 批量增加知识库提问计数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    fun incrementQuestionCountBatch(@Param("ids") ids: List<Long>): Int

    /**
     * 统计总提问次数
     */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    fun sumQuestionCount(): Long

    /**
     * 统计总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    fun sumAccessCount(): Long

    /**
     * 按向量化状态统计数量
     */
    fun countByVectorStatus(vectorStatus: VectorStatus): Long

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    fun findByVectorStatusOrderByUploadedAtDesc(vectorStatus: VectorStatus): List<KnowledgeBaseEntity>
}

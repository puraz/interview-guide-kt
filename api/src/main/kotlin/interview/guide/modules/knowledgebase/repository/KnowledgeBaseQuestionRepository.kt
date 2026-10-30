package interview.guide.modules.knowledgebase.repository

import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 知识库题目Repository
 */
interface KnowledgeBaseQuestionRepository : JpaRepository<KnowledgeBaseQuestionEntity, Long> {

    fun findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId: Long): List<KnowledgeBaseQuestionEntity>

    fun findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        status: KnowledgeBaseQuestionStatus
    ): List<KnowledgeBaseQuestionEntity>

    fun findTop50ByKnowledgeBase_IdAndSkillIdAndDifficultyOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        skillId: String,
        difficulty: String
    ): List<KnowledgeBaseQuestionEntity>

    fun findByKnowledgeBase_IdAndSkillIdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        skillId: String,
        difficulty: String,
        status: KnowledgeBaseQuestionStatus
    ): List<KnowledgeBaseQuestionEntity>

    fun findByKnowledgeBase_IdAndSkillIdAndDifficulty(
        knowledgeBaseId: Long,
        skillId: String,
        difficulty: String
    ): List<KnowledgeBaseQuestionEntity>

    /**
     * 同知识库内按难度筛选的题目（不限定 skillId，因为知识库题目 skillId 都是默认值）。
     */
    fun findByKnowledgeBase_IdAndDifficulty(
        knowledgeBaseId: Long,
        difficulty: String
    ): List<KnowledgeBaseQuestionEntity>

    fun findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        difficulty: String
    ): List<KnowledgeBaseQuestionEntity>

    /**
     * 同知识库 + 难度 + 方向（category）下的已启用题目，用于开始知识库面试抽题。
     */
    fun findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        difficulty: String,
        category: String,
        status: KnowledgeBaseQuestionStatus
    ): List<KnowledgeBaseQuestionEntity>

    /**
     * 同知识库 + 难度下的已启用题目，category 为空时表示覆盖所有方向。
     */
    fun findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId: Long,
        difficulty: String,
        status: KnowledgeBaseQuestionStatus
    ): List<KnowledgeBaseQuestionEntity>

    /**
     * 列出某知识库下出现过的方向（含计数），用于前端下拉。
     * 只统计有非空 category 的题目，按出现次数降序。
     */
    @Query(
        "select q.category as category, count(q) as count " +
            "from KnowledgeBaseQuestionEntity q " +
            "where q.knowledgeBase.id = :kbId and q.category is not null and q.category <> '' " +
            "group by q.category order by count(q) desc, q.category asc"
    )
    fun findCategoryCounts(@Param("kbId") knowledgeBaseId: Long): List<CategoryCount>

    /**
     * 方向计数投影
     */
    interface CategoryCount {
        fun getCategory(): String // 方向名称

        fun getCount(): Long // 题目数量
    }

    /**
     * 删除某知识库下的所有题目（用于重新生成时替换）
     */
    @Modifying
    @Query("DELETE FROM KnowledgeBaseQuestionEntity q WHERE q.knowledgeBase.id = :kbId")
    fun deleteByKnowledgeBaseId(@Param("kbId") knowledgeBaseId: Long): Int
}

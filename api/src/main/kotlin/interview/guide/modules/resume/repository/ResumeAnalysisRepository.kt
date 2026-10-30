package interview.guide.modules.resume.repository

import interview.guide.modules.resume.model.ResumeAnalysisEntity
import interview.guide.modules.resume.model.ResumeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 简历评测Repository
 */
@Repository
interface ResumeAnalysisRepository : JpaRepository<ResumeAnalysisEntity, Long> {

    /**
     * 根据简历查找所有评测记录
     *
     * @param resume 简历实体 // 关联简历
     * @return 评测记录 // 按评测时间倒序
     */
    fun findByResumeOrderByAnalyzedAtDesc(resume: ResumeEntity): List<ResumeAnalysisEntity>

    /**
     * 根据简历ID查找最新评测记录
     *
     * @param resumeId 简历ID // 关联简历主键
     * @return 最新评测记录 // 不存在时为 null
     */
    fun findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId: Long): ResumeAnalysisEntity?

    /**
     * 根据简历ID查找所有评测记录
     *
     * @param resumeId 简历ID // 关联简历主键
     * @return 评测记录 // 按评测时间倒序
     */
    fun findByResumeIdOrderByAnalyzedAtDesc(resumeId: Long): List<ResumeAnalysisEntity>
}

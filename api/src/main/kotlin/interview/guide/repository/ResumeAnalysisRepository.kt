package interview.guide.repository

import interview.guide.entity.ResumeAnalysisEntity
import interview.guide.entity.ResumeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 简历评测Repository
 */
@Repository
interface ResumeAnalysisRepository : JpaRepository<ResumeAnalysisEntity, Long> {

    /**
     * 根据简历查找所有评测记录
     */
    fun findByResumeOrderByAnalyzedAtDesc(resume: ResumeEntity): List<ResumeAnalysisEntity>

    /**
     * 根据简历ID查找最新评测记录
     */
    fun findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId: Long): ResumeAnalysisEntity?

    /**
     * 根据简历ID查找所有评测记录
     */
    fun findByResumeIdOrderByAnalyzedAtDesc(resumeId: Long): List<ResumeAnalysisEntity>
}

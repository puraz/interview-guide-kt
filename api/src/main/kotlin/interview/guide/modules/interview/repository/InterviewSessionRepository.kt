package interview.guide.modules.interview.repository

import interview.guide.modules.interview.model.InterviewSessionEntity
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus
import interview.guide.modules.resume.model.ResumeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 面试会话Repository
 */
@Repository
interface InterviewSessionRepository : JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    fun findBySessionId(sessionId: String): InterviewSessionEntity?

    /**
     * 根据创建请求幂等键查找
     */
    fun findByRequestId(requestId: String): InterviewSessionEntity?

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    fun findBySessionIdWithResume(@Param("sessionId") sessionId: String): InterviewSessionEntity?

    /**
     * 根据简历查找所有面试记录
     */
    fun findByResumeOrderByCreatedAtDesc(resume: ResumeEntity): List<InterviewSessionEntity>

    /**
     * 根据简历ID查找所有面试记录
     */
    fun findByResumeIdOrderByCreatedAtDesc(resumeId: Long): List<InterviewSessionEntity>

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    fun findTop10ByResumeIdOrderByCreatedAtDesc(resumeId: Long): List<InterviewSessionEntity>

    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    fun findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        resumeId: Long,
        statuses: List<SessionStatus>
    ): InterviewSessionEntity?

    /**
     * 根据简历ID和状态查找会话
     */
    fun findByResumeIdAndStatusIn(
        resumeId: Long,
        statuses: List<SessionStatus>
    ): InterviewSessionEntity?

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    fun findAllByOrderByCreatedAtDesc(): List<InterviewSessionEntity>

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    fun findTop10BySkillIdOrderByCreatedAtDesc(skillId: String): List<InterviewSessionEntity>

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    fun findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(
        resumeId: Long,
        skillId: String
    ): List<InterviewSessionEntity>
}

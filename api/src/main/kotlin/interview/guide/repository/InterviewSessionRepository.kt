package interview.guide.repository

import interview.guide.entity.InterviewSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 面试会话 Repository
 */
@Repository
interface InterviewSessionRepository : JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查询
     */
    fun findBySessionId(sessionId: String): InterviewSessionEntity?

    /**
     * 根据会话ID查询并预加载简历
     */
    @Query("select s from InterviewSessionEntity s join fetch s.resume where s.sessionId = :sessionId")
    fun findBySessionIdWithResume(@Param("sessionId") sessionId: String): InterviewSessionEntity?

    /**
     * 根据简历ID查询会话列表
     */
    fun findByResumeIdOrderByCreatedAtDesc(resumeId: Long): List<InterviewSessionEntity>

    /**
     * 查找未完成的会话（CREATED/IN_PROGRESS）
     */
    fun findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        resumeId: Long,
        status: List<InterviewSessionEntity.SessionStatus>
    ): InterviewSessionEntity?

    /**
     * 查询最近10个会话
     */
    fun findTop10ByResumeIdOrderByCreatedAtDesc(resumeId: Long): List<InterviewSessionEntity>
}

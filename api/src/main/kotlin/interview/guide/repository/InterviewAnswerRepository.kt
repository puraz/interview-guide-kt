package interview.guide.repository

import interview.guide.entity.InterviewAnswerEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 面试答案 Repository
 */
@Repository
interface InterviewAnswerRepository : JpaRepository<InterviewAnswerEntity, Long> {

    /**
     * 根据会话ID查询所有答案
     */
    fun findBySession_SessionIdOrderByQuestionIndex(sessionId: String): List<InterviewAnswerEntity>

    /**
     * 根据会话ID与问题索引查询
     */
    fun findBySession_SessionIdAndQuestionIndex(sessionId: String, questionIndex: Int): InterviewAnswerEntity?
}

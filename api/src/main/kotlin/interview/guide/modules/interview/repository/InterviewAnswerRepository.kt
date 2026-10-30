package interview.guide.modules.interview.repository

import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 面试答案Repository
 */
@Repository
interface InterviewAnswerRepository : JpaRepository<InterviewAnswerEntity, Long> {

    /**
     * 根据会话查找所有答案
     */
    fun findBySessionOrderByQuestionIndex(session: InterviewSessionEntity): List<InterviewAnswerEntity>

    /**
     * 根据会话ID查找所有答案
     */
    fun findBySessionIdOrderByQuestionIndex(sessionId: Long): List<InterviewAnswerEntity>

    /**
     * 根据会话 sessionId 字符串查找所有答案
     */
    fun findBySession_SessionIdOrderByQuestionIndex(sessionId: String): List<InterviewAnswerEntity>

    /**
     * 根据会话 sessionId 和问题索引查找单条答案（用于 upsert）
     */
    fun findBySession_SessionIdAndQuestionIndex(
        sessionId: String,
        questionIndex: Int
    ): InterviewAnswerEntity?
}

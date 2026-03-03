package interview.guide.infrastructure.redis

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.service.InterviewQuestionVo
import interview.guide.service.InterviewSessionStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Duration

/**
 * 面试会话 Redis 缓存服务
 * 管理面试会话在 Redis 中的存储
 */
@Service
class InterviewSessionCache(
    private val redisService: RedisService, // Redis 操作封装
    private val objectMapper: ObjectMapper // JSON 序列化工具
) {

    private val log = LoggerFactory.getLogger(InterviewSessionCache::class.java)

    /**
     * 缓存的会话数据
     */
    data class CachedSession(
        var sessionId: String? = null, // 会话ID
        var resumeText: String? = null, // 简历文本
        var resumeId: Long? = null, // 简历ID
        var questionsJson: String? = null, // 问题列表 JSON
        var currentIndex: Int = 0, // 当前问题索引
        var status: InterviewSessionStatus = InterviewSessionStatus.CREATED // 会话状态
    ) : Serializable {

        constructor(
            sessionId: String,
            resumeText: String,
            resumeId: Long?,
            questions: List<InterviewQuestionVo>,
            currentIndex: Int,
            status: InterviewSessionStatus,
            objectMapper: ObjectMapper
        ) : this(
            sessionId = sessionId,
            resumeText = resumeText,
            resumeId = resumeId,
            questionsJson = objectMapper.writeValueAsString(questions),
            currentIndex = currentIndex,
            status = status
        )

        /**
         * 获取问题列表
         */
        fun getQuestions(objectMapper: ObjectMapper): List<InterviewQuestionVo> {
            return objectMapper.readValue(questionsJson ?: "[]", object : TypeReference<List<InterviewQuestionVo>>() {})
        }
    }

    companion object {
        private const val SESSION_KEY_PREFIX = "interview:session:" // 会话缓存前缀
        private const val RESUME_SESSION_KEY_PREFIX = "interview:resume:" // 简历映射前缀
        private val SESSION_TTL: Duration = Duration.ofHours(24) // 会话缓存时长
    }

    /**
     * 保存会话到缓存
     */
    fun saveSession(
        sessionId: String,
        resumeText: String,
        resumeId: Long?,
        questions: List<InterviewQuestionVo>,
        currentIndex: Int,
        status: InterviewSessionStatus
    ) {
        val key = buildSessionKey(sessionId)
        val cachedSession = CachedSession(sessionId, resumeText, resumeId, questions, currentIndex, status, objectMapper)

        redisService.set(key, cachedSession, SESSION_TTL)

        // 如果有 resumeId，建立映射关系（用于查找未完成会话）
        if (resumeId != null && isUnfinishedStatus(status)) {
            saveResumeSessionMapping(resumeId, sessionId)
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, status={}", sessionId, resumeId, status)
    }

    /**
     * 获取缓存的会话
     */
    fun getSession(sessionId: String): CachedSession? {
        val key = buildSessionKey(sessionId)
        val session = redisService.get<CachedSession>(key)
        if (session != null) {
            log.debug("从缓存获取会话: sessionId={}", sessionId)
        }
        return session
    }

    /**
     * 更新会话状态
     */
    fun updateSessionStatus(sessionId: String, status: InterviewSessionStatus) {
        getSession(sessionId)?.let { session ->
            session.status = status
            val key = buildSessionKey(sessionId)
            redisService.set(key, session, SESSION_TTL)

            // 如果会话已完成，移除映射
            if (!isUnfinishedStatus(status) && session.resumeId != null) {
                removeResumeSessionMapping(session.resumeId!!, sessionId)
            }

            log.debug("更新会话状态: sessionId={}, status={}", sessionId, status)
        }
    }

    /**
     * 更新当前问题索引
     */
    fun updateCurrentIndex(sessionId: String, currentIndex: Int) {
        getSession(sessionId)?.let { session ->
            session.currentIndex = currentIndex
            val key = buildSessionKey(sessionId)
            redisService.set(key, session, SESSION_TTL)
            log.debug("更新会话进度: sessionId={}, currentIndex={}", sessionId, currentIndex)
        }
    }

    /**
     * 更新问题列表（用于保存答案）
     */
    fun updateQuestions(sessionId: String, questions: List<InterviewQuestionVo>) {
        getSession(sessionId)?.let { session ->
            session.questionsJson = objectMapper.writeValueAsString(questions)
            val key = buildSessionKey(sessionId)
            redisService.set(key, session, SESSION_TTL)
            log.debug("更新会话问题: sessionId={}", sessionId)
        }
    }

    /**
     * 删除会话缓存
     */
    fun deleteSession(sessionId: String) {
        getSession(sessionId)?.let { session ->
            if (session.resumeId != null) {
                removeResumeSessionMapping(session.resumeId!!, sessionId)
            }
        }

        val key = buildSessionKey(sessionId)
        redisService.delete(key)
        log.debug("删除会话缓存: sessionId={}", sessionId)
    }

    /**
     * 根据简历ID查找未完成的会话ID
     */
    fun findUnfinishedSessionId(resumeId: Long): String? {
        val key = buildResumeSessionKey(resumeId)
        val sessionId = redisService.get<String>(key)
        if (sessionId != null) {
            val session = getSession(sessionId)
            if (session != null && isUnfinishedStatus(session.status)) {
                return sessionId
            }
            redisService.delete(key)
        }
        return null
    }

    /**
     * 刷新会话过期时间
     */
    fun refreshSessionTTL(sessionId: String) {
        val key = buildSessionKey(sessionId)
        redisService.expire(key, SESSION_TTL)
    }

    /**
     * 检查会话是否在缓存中
     */
    fun exists(sessionId: String): Boolean {
        val key = buildSessionKey(sessionId)
        return redisService.exists(key)
    }

    private fun buildSessionKey(sessionId: String): String {
        return SESSION_KEY_PREFIX + sessionId
    }

    private fun buildResumeSessionKey(resumeId: Long): String {
        return RESUME_SESSION_KEY_PREFIX + resumeId
    }

    private fun saveResumeSessionMapping(resumeId: Long, sessionId: String) {
        val key = buildResumeSessionKey(resumeId)
        redisService.set(key, sessionId, SESSION_TTL)
    }

    private fun removeResumeSessionMapping(resumeId: Long, sessionId: String) {
        val key = buildResumeSessionKey(resumeId)
        val currentSessionId = redisService.get<String>(key)
        if (sessionId == currentSessionId) {
            redisService.delete(key)
        }
    }

    private fun isUnfinishedStatus(status: InterviewSessionStatus): Boolean {
        return status == InterviewSessionStatus.CREATED || status == InterviewSessionStatus.IN_PROGRESS
    }
}

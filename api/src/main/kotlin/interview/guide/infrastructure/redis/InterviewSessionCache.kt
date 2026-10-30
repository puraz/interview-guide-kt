package interview.guide.infrastructure.redis

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.interview.model.InterviewQuestionVo
import interview.guide.modules.interview.model.InterviewSessionVo
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
        var knowledgeBaseId: Long? = null, // 知识库ID
        var interviewCategory: String? = null, // 面试分类
        var questionsJson: String? = null, // 序列化的问题列表
        var currentIndex: Int = 0, // 当前问题索引
        var status: InterviewSessionVo.SessionStatus = InterviewSessionVo.SessionStatus.CREATED // 会话状态
    ) : Serializable {

        constructor(
            sessionId: String,
            resumeText: String,
            resumeId: Long?,
            knowledgeBaseId: Long?,
            interviewCategory: String?,
            questions: List<InterviewQuestionVo>,
            currentIndex: Int,
            status: InterviewSessionVo.SessionStatus,
            objectMapper: ObjectMapper
        ) : this(
            sessionId = sessionId,
            resumeText = resumeText,
            resumeId = resumeId,
            knowledgeBaseId = knowledgeBaseId,
            interviewCategory = interviewCategory,
            currentIndex = currentIndex,
            status = status
        ) {
            try {
                this.questionsJson = objectMapper.writeValueAsString(questions)
            } catch (e: Exception) {
                throw BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e)
            }
        }

        /**
         * 获取问题列表
         *
         * @param objectMapper JSON 序列化工具 // 反序列化使用
         * @return 问题列表 // 缓存中的面试问题
         */
        fun getQuestions(objectMapper: ObjectMapper): List<InterviewQuestionVo> {
            return try {
                objectMapper.readValue(
                    questionsJson ?: "[]",
                    object : TypeReference<List<InterviewQuestionVo>>() {}
                )
            } catch (e: Exception) {
                throw BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化问题列表失败")
            }
        }
    }

    companion object {
        /**
         * 缓存键前缀
         */
        private const val SESSION_KEY_PREFIX = "interview:session:"

        /**
         * 简历ID到会话ID的映射前缀（用于查找未完成会话）
         */
        private const val RESUME_SESSION_KEY_PREFIX = "interview:resume:"

        /**
         * 会话默认过期时间（24小时）
         */
        private val SESSION_TTL: Duration = Duration.ofHours(24)
    }

    /**
     * 保存会话到缓存
     *
     * @param sessionId         会话ID // 唯一标识
     * @param resumeText        简历文本 // 会话使用的简历
     * @param resumeId          简历ID // 关联简历，可为 null
     * @param knowledgeBaseId   知识库ID // 关联知识库，可为 null
     * @param interviewCategory 面试分类 // 面试方向，可为 null
     * @param questions         问题列表 // 面试问题
     * @param currentIndex      当前问题索引 // 进度
     * @param status            会话状态 // 当前状态
     */
    fun saveSession(
        sessionId: String,
        resumeText: String,
        resumeId: Long?,
        knowledgeBaseId: Long?,
        interviewCategory: String?,
        questions: List<InterviewQuestionVo>,
        currentIndex: Int,
        status: InterviewSessionVo.SessionStatus
    ) {
        val key = buildSessionKey(sessionId)
        val cachedSession = CachedSession(
            sessionId,
            resumeText,
            resumeId,
            knowledgeBaseId,
            interviewCategory,
            questions,
            currentIndex,
            status,
            objectMapper
        )

        redisService.set(key, cachedSession, SESSION_TTL)

        // 如果有 resumeId，建立映射关系（用于查找未完成会话）
        if (resumeId != null && isUnfinishedStatus(status)) {
            saveResumeSessionMapping(resumeId, sessionId)
        }

        log.debug("会话已缓存: sessionId={}, resumeId={}, kbId={}, status={}",
            sessionId, resumeId, knowledgeBaseId, status)
    }

    /**
     * 获取缓存的会话
     *
     * @param sessionId 会话ID // 唯一标识
     * @return 缓存的会话 // 不存在时为 null
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
     *
     * @param sessionId 会话ID // 唯一标识
     * @param status    新状态 // 会话状态
     */
    fun updateSessionStatus(sessionId: String, status: InterviewSessionVo.SessionStatus) {
        getSession(sessionId)?.let { session ->
            session.status = status
            val key = buildSessionKey(sessionId)
            redisService.set(key, session, SESSION_TTL)

            // 如果会话已完成，移除映射
            if (!isUnfinishedStatus(status)) {
                session.resumeId?.let { removeResumeSessionMapping(it, sessionId) }
            }

            log.debug("更新会话状态: sessionId={}, status={}", sessionId, status)
        }
    }

    /**
     * 更新当前问题索引
     *
     * @param sessionId    会话ID // 唯一标识
     * @param currentIndex 当前索引 // 进度
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
     *
     * @param sessionId 会话ID // 唯一标识
     * @param questions 问题列表 // 最新问题内容
     */
    fun updateQuestions(sessionId: String, questions: List<InterviewQuestionVo>) {
        getSession(sessionId)?.let { session ->
            try {
                session.questionsJson = objectMapper.writeValueAsString(questions)
                val key = buildSessionKey(sessionId)
                redisService.set(key, session, SESSION_TTL)
                log.debug("更新会话问题: sessionId={}", sessionId)
            } catch (e: Exception) {
                log.error("序列化问题列表失败", e)
            }
        }
    }

    /**
     * 删除会话缓存
     *
     * @param sessionId 会话ID // 唯一标识
     */
    fun deleteSession(sessionId: String) {
        getSession(sessionId)?.let { session ->
            session.resumeId?.let { removeResumeSessionMapping(it, sessionId) }
        }

        val key = buildSessionKey(sessionId)
        redisService.delete(key)
        log.debug("删除会话缓存: sessionId={}", sessionId)
    }

    /**
     * 根据简历ID查找未完成的会话ID
     *
     * @param resumeId 简历ID // 关联简历
     * @return 未完成会话ID // 不存在时为 null
     */
    fun findUnfinishedSessionId(resumeId: Long): String? {
        val key = buildResumeSessionKey(resumeId)
        val sessionId = redisService.get<String>(key)
        if (sessionId != null) {
            // 验证会话是否仍然存在且未完成
            val session = getSession(sessionId)
            if (session != null && isUnfinishedStatus(session.status)) {
                return sessionId
            } else {
                // 会话已不存在或已完成，清理映射
                redisService.delete(key)
            }
        }
        return null
    }

    /**
     * 刷新会话过期时间
     *
     * @param sessionId 会话ID // 唯一标识
     */
    fun refreshSessionTTL(sessionId: String) {
        val key = buildSessionKey(sessionId)
        redisService.expire(key, SESSION_TTL)
    }

    /**
     * 检查会话是否在缓存中
     *
     * @param sessionId 会话ID // 唯一标识
     * @return 是否存在 // 存在为 true
     */
    fun exists(sessionId: String): Boolean {
        val key = buildSessionKey(sessionId)
        return redisService.exists(key)
    }

    // ==================== 私有方法 ====================

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
        // 只有当前映射的是这个 sessionId 时才删除
        if (sessionId == currentSessionId) {
            redisService.delete(key)
        }
    }

    private fun isUnfinishedStatus(status: InterviewSessionVo.SessionStatus): Boolean {
        return status == InterviewSessionVo.SessionStatus.CREATED || status == InterviewSessionVo.SessionStatus.IN_PROGRESS
    }
}

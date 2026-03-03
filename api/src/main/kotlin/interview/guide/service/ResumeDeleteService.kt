package interview.guide.service

import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.infrastructure.file.FileStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 简历删除服务
 * 处理简历删除的业务逻辑
 */
@Service
class ResumeDeleteService(
    private val persistenceService: ResumePersistenceService, // 简历持久化服务
    private val interviewPersistenceService: InterviewPersistenceService, // 面试持久化服务
    private val storageService: FileStorageService // 存储服务
) {

    private val log = LoggerFactory.getLogger(ResumeDeleteService::class.java)

    /**
     * 删除简历
     *
     * @param id 简历ID // 简历主键
     */
    fun deleteResume(id: Long) {
        log.info("收到删除简历请求: id={}", id)

        val resume = persistenceService.findById(id)
            ?: throw BusinessException(ErrorCode.RESUME_NOT_FOUND)

        try {
            storageService.deleteResume(resume.storageKey)
        } catch (e: Exception) {
            log.warn("删除存储文件失败，继续删除数据库记录: {}", e.message)
        }

        interviewPersistenceService.deleteSessionsByResumeId(id)
        persistenceService.deleteResume(id)
        log.info("简历删除完成: id={}", id)
    }
}

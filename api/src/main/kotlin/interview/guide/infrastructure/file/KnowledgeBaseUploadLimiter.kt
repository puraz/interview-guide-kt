package interview.guide.infrastructure.file

import interview.guide.common.config.KnowledgeBaseUploadProperties
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import org.springframework.stereotype.Component
import org.springframework.util.Assert
import java.util.concurrent.Semaphore

/**
 * 限制一个应用实例中正在执行的知识库上传，不排队等待。
 *
 * 不替代请求频率限制，也不限制 multipart 接收或后台向量化任务。
 */
@Component
class KnowledgeBaseUploadLimiter(properties: KnowledgeBaseUploadProperties) {

    private val permits: Semaphore // 上传并发许可

    init {
        Assert.isTrue(properties.maxConcurrent > 0, "知识库上传并发上限必须大于 0")
        permits = Semaphore(properties.maxConcurrent)
    }

    /**
     * 在并发许可内执行上传
     *
     * @param upload 上传动作 // 实际业务逻辑
     * @return 上传结果 // 业务返回值
     */
    fun <T> execute(upload: () -> T): T {
        if (!permits.tryAcquire()) {
            throw BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "知识库上传繁忙，请稍后重试")
        }
        try {
            return upload()
        } finally {
            permits.release()
        }
    }
}

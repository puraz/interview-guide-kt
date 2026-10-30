package interview.guide.infrastructure.file

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文档解析专用有界线程池。
 */
@Configuration
class DocumentParseConfiguration {

    /**
     * 创建文档解析线程池（有界队列 + 拒绝策略，避免无限制堆积）
     *
     * @param properties 解析配置 // 线程数与队列容量
     * @return 文档解析线程池 // 容器关闭时自动 shutdown
     */
    @Bean(name = ["documentParseExecutor"], destroyMethod = "shutdown")
    fun documentParseExecutor(properties: DocumentParseProperties): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        return ThreadPoolExecutor(
            properties.poolSize,
            properties.poolSize,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(properties.queueCapacity),
            ThreadFactory { runnable ->
                val thread = Thread(runnable, "document-parse-" + sequence.incrementAndGet())
                thread.isDaemon = true
                thread
            },
            ThreadPoolExecutor.AbortPolicy()
        )
    }
}

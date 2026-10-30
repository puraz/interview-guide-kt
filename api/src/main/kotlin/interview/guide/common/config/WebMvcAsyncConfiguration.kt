package interview.guide.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 让 Spring MVC 的 SSE/异步响应复用统一管理的应用执行器。
 *
 * Java HEAD 使用 `VirtualThreadTaskExecutor`（Java 21+）；Kotlin 版按 Java 17 编译，
 * 改为等价的平台线程池实现，线程名前缀保持 `mvc-async-`，超时/并发语义保持一致。
 */
@Configuration
class WebMvcAsyncConfiguration : WebMvcConfigurer {

    private val mvcAsyncTaskExecutor: AsyncTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 8 // 常驻线程数
        maxPoolSize = 200 // 峰值线程数（SSE 长连接会占住线程）
        queueCapacity = 500 // 等待队列容量
        keepAliveSeconds = 60 // 空闲线程存活秒数
        setThreadNamePrefix("mvc-async-") // 线程名称前缀（与 Java HEAD 一致）
        setWaitForTasksToCompleteOnShutdown(true) // 关闭时等待任务完成
        initialize()
    }

    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor)
    }
}

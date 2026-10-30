package interview.guide.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson Configuration
 * Jackson配置
 *
 * 说明：Java HEAD 直接返回 `new ObjectMapper()`；Kotlin 版必须注册 KotlinModule 才能反序列化
 * data class 请求体（无 KotlinModule 时缺少默认值/空值语义），同时注册 Jdk8/JavaTime 模块
 * 以保持 Optional 与 LocalDateTime 的序列化能力，因此此处是相对 Java HEAD 的必要适配。
 */
@Configuration
class JacksonConfig {

    /**
     * ObjectMapper Bean
     *
     * @return ObjectMapper // JSON 序列化/反序列化工具
     */
    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper()
            .registerModule(KotlinModule.Builder().build()) // Kotlin data class 支持
            .registerModule(Jdk8Module()) // Optional 等 JDK8 类型支持
            .registerModule(JavaTimeModule()) // Java 时间类型支持
    }
}

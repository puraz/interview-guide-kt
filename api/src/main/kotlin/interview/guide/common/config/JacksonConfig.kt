package interview.guide.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson 配置
 * 统一提供 ObjectMapper Bean，避免自动装配缺失
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
            .registerModule(KotlinModule.Builder().build()) // Kotlin 数据类支持
            .registerModule(Jdk8Module()) // Optional 等 JDK8 类型支持
            .registerModule(JavaTimeModule()) // Java 时间类型支持
    }
}

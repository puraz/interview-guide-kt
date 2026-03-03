package com.example.framework.core.jackson

import cn.hutool.core.date.DatePattern
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 自定义Jackson配置
 * @author gcc
 */
@Configuration
class JacksonConfiguration {

    /**
     * 全局Jackson序列化配置
     */
    @Bean
    fun objectMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        mapper.registerModule(KotlinModule.Builder().build()) // Kotlin 类型支持
        mapper.findAndRegisterModules() // 注册默认模块（含 JSR310）

        val timeModule = JavaTimeModule()
        timeModule.addSerializer(
            LocalDateTime::class.java,
            LocalDateTimeSerializer(DatePattern.NORM_DATETIME_FORMATTER)
        )
        timeModule.addSerializer(LocalTime::class.java, LocalTimeSerializer(DatePattern.NORM_TIME_FORMATTER))
        timeModule.addSerializer(LocalDate::class.java, LocalDateSerializer(DatePattern.NORM_DATE_FORMATTER))
        timeModule.addDeserializer(
            LocalDateTime::class.java,
            LocalDateTimeDeserializer(DatePattern.NORM_DATETIME_FORMATTER)
        )
        timeModule.addDeserializer(LocalTime::class.java, LocalTimeDeserializer(DatePattern.NORM_TIME_FORMATTER))
        timeModule.addDeserializer(LocalDate::class.java, LocalDateDeserializer(DatePattern.NORM_DATE_FORMATTER))

        val numberModule = SimpleModule()
        numberModule.addSerializer(Long::class.javaObjectType, ToStringSerializer.instance)
        numberModule.addSerializer(java.lang.Long.TYPE, ToStringSerializer.instance)
        numberModule.addSerializer(BigDecimal::class.java, ToStringSerializer.instance)
        numberModule.addSerializer(BigInteger::class.java, ToStringSerializer.instance)

        mapper.registerModule(timeModule) // 自定义时间序列化
        mapper.registerModule(numberModule) // 数值序列化为字符串
        mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING) // 枚举序列化使用 toString
        return mapper
    }
}

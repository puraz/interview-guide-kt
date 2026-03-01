package com.example

import org.babyfish.jimmer.client.EnableImplicitApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import kotlin.jvm.java

@SpringBootApplication
@EnableImplicitApi
class InterviewGuideApplication

private val log: Logger = LoggerFactory.getLogger(InterviewGuideApplication::class.java)
fun main(args: Array<String>) {
    val context = runApplication<InterviewGuideApplication>(*args)
    val env = context.environment
    log.info(
        """
----------------------------------------------------------
	项目 '${env.getProperty("spring.application.name")}' 启动成功! 访问连接:
	Swagger文档: 	 http://127.0.0.1:${env.getProperty("server.port", "8080")}/swagger-ui.html
	数据库监控: 		 http://127.0.0.1:${env.getProperty("server.port", "8080")}/druid
----------------------------------------------------------
    """
    )
}

/*
 * AI Interview Platform - Backend Application (Kotlin)
 * Spring Boot 4.1 + Spring AI 2.0 + PostgreSQL + pgvector + RustFS + Redis
 */

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    kotlin("plugin.allopen")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.interview"
version = "1.0.0"
description = "AI 面试平台后端（Kotlin 重写版）"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public/") }
    maven { url = uri("https://maven.aliyun.com/repository/spring/") }
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        javaParameters.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    sourceSets {
        main {
            java.srcDir("src/main/kotlin")
        }
    }
}

// JPA 实体需要非 final 的 getter，Hibernate 才能生成懒加载代理
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

kotlin {
    sourceSets {
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("org.springaicommunity:spring-ai-agent-utils-bom:0.10.0")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

dependencies {
    // Spring Boot（Boot 4 模块化 starter）
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 缓存、Redis 相关
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson-spring-boot-starter:4.0.0")
    implementation("org.apache.commons:commons-pool2")

    // Kotlin 相关
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // 数据库相关
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation("com.alibaba:druid-spring-boot-starter:1.2.27")

    // Hibernate Vector（pgvector）
    implementation("org.hibernate.orm:hibernate-vector")

    // Spring AI（OpenAI 兼容模式 + pgvector）
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springaicommunity:spring-ai-agent-utils")

    implementation("org.springframework.security:spring-security-crypto")

    // JSON 相关
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // OpenAPI 3 / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // 指标监控
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // OSS 相关（S3 兼容）
    implementation("software.amazon.awssdk:s3:2.29.51")

    // PDF 导出相关（iText 8）
    implementation("com.itextpdf:itext-core:8.0.5")
    implementation("com.itextpdf:font-asian:8.0.5")

    // 文档解析相关（Apache Tika）
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")

    // 拼音转换（文件名清理）
    implementation("com.belerweb:pinyin4j:2.5.0")

    implementation("com.google.code.gson:gson:2.13.2")

    // 阿里云 DashScope SDK（Qwen3 ASR/TTS）
    implementation("com.alibaba:dashscope-sdk-java:2.22.7") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    testImplementation("io.mockk:mockk:1.13.11")
}

tasks.bootJar {
    archiveFileName.set("interview-guide-api-1.0.0.jar")
}

// 处理资源文件
sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
        }
    }
}

// 添加 Kotlin 源码目录中的 XML 文件到资源
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("src/main/kotlin") {
        include("**/*.xml")
    }
}

// bootRun 自动加载仓库根目录 .env，并统一 JVM 编码
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
    )
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val eq = trimmed.indexOf('=')
            if (eq <= 0) {
                return@forEach
            }
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            // 已显式导出的环境变量优先，避免 .env 覆盖 shell/CI 配置
            if (System.getenv(key) == null) {
                environment(key, value)
            }
        }
    }
}

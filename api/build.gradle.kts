plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.example"
version = "1.1.12"
description = "使用kotlin语言基于spring boot、spring data jpa、sa-token等框架开发的项目脚手架。"
val wxJavaSdkVersion = "4.7.7.B"
val langchain4jVersion = "1.10.0" // LangChain4j 版本：用于统一大模型调用抽象与 OpenAI 兼容适配 // DeepSeek/Qwen/OpenAI 可复用

repositories {
    mavenCentral()
    maven {
        url = uri("https://artifacts-cn-beijing.volces.com/repository/douyin-openapi/")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        javaParameters = true   // 关键
        freeCompilerArgs += "-Xjvm-default=all"
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

kotlin {
    sourceSets {
        main {
            // kotlin.srcDir("src/main/kotlin")
        }
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
        mavenBom("cn.dev33:sa-token-bom:1.44.0")
        mavenBom("cn.hutool:hutool-bom:5.8.43")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))

    // Spring Boot 相关
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 缓存、Redis 相关
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.apache.commons:commons-pool2")

    // Kotlin 相关
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // 数据库 相关
    runtimeOnly("org.postgresql:postgresql:42.7.1")
    implementation("com.alibaba:druid-spring-boot-starter:1.2.27")

    // Hibernate Vector（pgvector）
    implementation("org.hibernate.orm:hibernate-vector")

    // Security 相关
    implementation("cn.dev33:sa-token-spring-boot3-starter")
    implementation("cn.dev33:sa-token-redis-jackson")
    implementation("cn.dev33:sa-token-spring-aop")
    implementation("cn.dev33:sa-token-jwt")
    implementation("org.springframework.security:spring-security-crypto")

    // JSON 相关
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // OpenAPI 3 / Swagger UI（Spring Boot 3 官方推荐：springdoc-openapi）
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")

    // OSS 相关
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.18.3")
    implementation("io.minio:minio:8.5.17")
    implementation("com.qiniu:qiniu-java-sdk:7.19.0")

    // POI 相关
    implementation("cn.afterturn:easypoi-annotation:4.5.0")
    implementation("cn.afterturn:easypoi-web:4.5.0")
    implementation("cn.afterturn:easypoi-base:4.5.0")
    implementation("cn.afterturn:easypoi-spring-boot-starter:4.5.0") {
        exclude(group = "cn.hutool", module = "hutool-all")
    }

    // PDF 导出相关（iText 8）
    implementation("com.itextpdf:itext-core:8.0.5")
    implementation("com.itextpdf:font-asian:8.0.5")

    // LangChain4j（大模型统一调用框架）
    implementation("dev.langchain4j:langchain4j:${langchain4jVersion}") // 核心抽象与通用能力 // 业务层只依赖自定义 AiChatClient
    implementation("dev.langchain4j:langchain4j-open-ai:${langchain4jVersion}") // OpenAI 兼容实现（DeepSeek 兼容）// 用于 DeepSeek Adapter
    implementation("com.google.code.gson:gson:2.13.2")

    // Utils 相关
    implementation("cn.hutool:hutool-core")
    implementation("cn.hutool:hutool-extra")
    implementation("cn.hutool:hutool-crypto")
    implementation("cn.hutool:hutool-http")
    implementation("cn.hutool:hutool-jwt")
    implementation("com.github.whvcse:easy-captcha:1.6.2")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")

    // 其它
    implementation("com.alibaba:transmittable-thread-local:2.14.5")
    implementation("org.lionsoul:ip2region:2.7.0")
    implementation("com.aizuda:aizuda-monitor:1.0.0")

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

// 添加Kotlin源码目录中的XML文件到资源
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("src/main/kotlin") {
        include("**/*.xml")
    }
}

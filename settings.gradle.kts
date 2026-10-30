pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = uri("https://maven.aliyun.com/repository/spring-plugin/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 允许自动下载缺失的 JDK 工具链
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "interview-guide-kt"
include("api")

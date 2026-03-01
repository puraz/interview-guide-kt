package com.example.framework.core.satoken.annotation

/**
 * 免登录注解
 *
 * 说明：
 * 被此注解标记的接口或类将跳过登录验证
 *
 * 使用方式:
 * ```
 *      // 类级别：整个Controller跳过登录验证
 *      @SkipLogin
 *      class PublicController
 *
 *      // 方法级别：单个接口跳过登录验证
 *      @SkipLogin
 *      fun publicApi()
 * ```
 * @author gcc
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipLogin
package com.example.framework.core.xss.annotation

/**
 * 排除xss过滤
 *
 * @author gcc
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoXss()

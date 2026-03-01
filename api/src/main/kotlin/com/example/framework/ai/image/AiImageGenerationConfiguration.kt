package com.example.framework.ai.image

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 图片生成（智谱 CogView 等）配置装配入口。//
 *
 * 说明：//
 * - 当前用于 CookDailyPlan 的“菜品图片生成 -> 上传七牛 -> 落库 image 字段”流程//
 * - 只提供通用的 HTTP 调用能力，业务侧决定提示词与落库策略//
 */
@Configuration
@EnableConfigurationProperties(AiImageGenerationProperties::class)
class AiImageGenerationConfiguration


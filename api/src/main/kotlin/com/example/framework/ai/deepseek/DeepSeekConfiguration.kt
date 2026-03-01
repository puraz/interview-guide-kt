package com.example.framework.ai.deepseek

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(DeepSeekProperties::class)
class DeepSeekConfiguration

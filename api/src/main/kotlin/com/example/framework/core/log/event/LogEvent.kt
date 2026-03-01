package com.example.framework.core.log.event

import com.example.framework.core.log.model.LogDTO
import org.springframework.context.ApplicationEvent

/**
 * 系统日志 事件
 *
 * 说明：
 * 在[LogListener]中处理本事件
 * @author gcc
 */
class LogEvent(source: LogDTO) : ApplicationEvent(source)

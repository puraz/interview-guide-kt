package com.example.framework.core.log.event

import com.example.framework.core.log.model.LoginLogDTO
import org.springframework.context.ApplicationEvent

/**
 * 登录日志 事件
 *
 * 说明：
 * 在[LoginLogListener]中处理本事件
 * @author gcc
 */
class LoginEvent(source: LoginLogDTO) : ApplicationEvent(source)

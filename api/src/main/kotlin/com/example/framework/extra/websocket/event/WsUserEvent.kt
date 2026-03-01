package com.example.framework.extra.websocket.event

import com.example.framework.extra.websocket.enums.WsUserTypeEnum
import com.example.framework.extra.websocket.model.WsUser
import org.springframework.context.ApplicationEvent

/**
 * Websocket用户 事件
 *
 * 说明：
 * 主要用来发送用户上线、下线事件通知
 * @author gcc
 */
class WsUserEvent(val user: WsUser?, source: WsUserTypeEnum) : ApplicationEvent(source)

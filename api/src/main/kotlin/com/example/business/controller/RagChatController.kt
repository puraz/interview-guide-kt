package com.example.business.controller

import com.example.business.service.RagChatSessionService
import com.example.framework.base.controller.SuperBaseController
import com.example.framework.base.result.ApiResult
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * RAG 聊天控制器
 *
 * 提供会话管理与流式聊天接口。
 */
@Validated
@RestController
class RagChatController(
    private val sessionService: RagChatSessionService
) : SuperBaseController {
    private val logger = LoggerFactory.getLogger(RagChatController::class.java)

    /**
     * 创建会话
     *
     * @param param 创建参数
     * @return 会话信息
     */
    @PostMapping("/api/rag-chat/sessions")
    fun createSession(
        @Valid @RequestBody param: RagChatSessionService.CreateSessionParam
    ): ApiResult<RagChatSessionService.SessionVo> {
        return success(sessionService.createSession(param))
    }

    /**
     * 获取会话列表
     *
     * @return 会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    fun listSessions(): ApiResult<List<RagChatSessionService.SessionListItemVo>> {
        return success(sessionService.listSessions())
    }

    /**
     * 获取会话详情
     *
     * @param sessionId 会话ID
     * @return 会话详情
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    fun getSessionDetail(
        @PathVariable sessionId: Long
    ): ApiResult<RagChatSessionService.SessionDetailVo> {
        return success(sessionService.getSessionDetail(sessionId))
    }

    /**
     * 更新会话标题
     *
     * @param sessionId 会话ID
     * @param param 更新参数
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    fun updateSessionTitle(
        @PathVariable sessionId: Long,
        @Valid @RequestBody param: RagChatSessionService.UpdateTitleParam
    ): ApiResult<Void> {
        sessionService.updateSessionTitle(sessionId, param.title)
        return success()
    }

    /**
     * 切换置顶状态
     *
     * @param sessionId 会话ID
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    fun togglePin(@PathVariable sessionId: Long): ApiResult<Void> {
        sessionService.togglePin(sessionId)
        return success()
    }

    /**
     * 更新会话知识库
     *
     * @param sessionId 会话ID
     * @param param 更新参数
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    fun updateSessionKnowledgeBases(
        @PathVariable sessionId: Long,
        @Valid @RequestBody param: RagChatSessionService.UpdateKnowledgeBasesParam
    ): ApiResult<Void> {
        sessionService.updateSessionKnowledgeBases(sessionId, param.knowledgeBaseIds)
        return success()
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话ID
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    fun deleteSession(@PathVariable sessionId: Long): ApiResult<Void> {
        sessionService.deleteSession(sessionId)
        return success()
    }

    /**
     * 发送消息（流式）
     *
     * @param sessionId 会话ID
     * @param param 消息参数
     * @return SSE 流式响应
     */
    @PostMapping(value = ["/api/rag-chat/sessions/{sessionId}/messages/stream"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sendMessageStream(
        @PathVariable sessionId: Long,
        @Valid @RequestBody param: RagChatSessionService.SendMessageParam
    ): Flux<ServerSentEvent<String>> {
        logger.info("RAG流式请求: sessionId={}, question={}", sessionId, param.question)
        val messageId = sessionService.prepareStreamMessage(sessionId, param.question)
        val fullContent = StringBuilder()
        return sessionService.getStreamAnswer(sessionId, param.question)
            .doOnNext { fullContent.append(it) }
            .map { chunk ->
                ServerSentEvent.builder<String>()
                    .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                    .build()
            }
            .doOnComplete {
                sessionService.completeStreamMessage(messageId, fullContent.toString())
            }
            .doOnError { ex ->
                val content = if (fullContent.isNotEmpty()) {
                    fullContent.toString()
                } else {
                    "【错误】回答生成失败：${ex.message}"
                }
                sessionService.completeStreamMessage(messageId, content)
            }
    }
}

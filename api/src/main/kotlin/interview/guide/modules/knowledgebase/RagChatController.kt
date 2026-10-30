package interview.guide.modules.knowledgebase

import interview.guide.common.result.Result
import interview.guide.modules.knowledgebase.model.RagChatVo
import interview.guide.modules.knowledgebase.service.RagChatSessionService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
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
 */
@RestController
class RagChatController(
    private val sessionService: RagChatSessionService // RAG 会话服务
) {

    private val log = LoggerFactory.getLogger(RagChatController::class.java)

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    fun createSession(@Valid @RequestBody request: RagChatVo.CreateSessionRequest): Result<RagChatVo.SessionDTO> {
        return Result.success(sessionService.createSession(request))
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    fun listSessions(): Result<List<RagChatVo.SessionListItemDTO>> {
        return Result.success(sessionService.listSessions())
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    fun getSessionDetail(@PathVariable sessionId: Long): Result<RagChatVo.SessionDetailDTO> {
        return Result.success(sessionService.getSessionDetail(sessionId))
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    fun updateSessionTitle(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: RagChatVo.UpdateTitleRequest
    ): Result<Void> {
        sessionService.updateSessionTitle(sessionId, request.title)
        return Result.success(null)
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    fun togglePin(@PathVariable sessionId: Long): Result<Void> {
        sessionService.togglePin(sessionId)
        return Result.success(null)
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    fun updateSessionKnowledgeBases(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: RagChatVo.UpdateKnowledgeBasesRequest
    ): Result<Void> {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds)
        return Result.success(null)
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    fun deleteSession(@PathVariable sessionId: Long): Result<Void> {
        sessionService.deleteSession(sessionId)
        return Result.success(null)
    }

    /**
     * 发送消息（流式SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 返回流式响应
     * 3. 流式完成后通过回调更新消息
     */
    @PostMapping(
        value = ["/api/rag-chat/sessions/{sessionId}/messages/stream"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun sendMessageStream(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: RagChatVo.SendMessageRequest
    ): Flux<ServerSentEvent<String>> {

        // Java 17 无虚拟线程 API（Java 25 的 Thread.isVirtual），这里固定记录 false
        log.info(
            "收到 RAG 聊天流式请求: sessionId={}, questionLength={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question.length, Thread.currentThread(), false
        )

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        val messageId = sessionService.prepareStreamMessage(sessionId, request.question)

        // 2. 获取流式响应
        val fullContent = StringBuilder()

        return sessionService.getStreamAnswer(sessionId, request.question)
            .doOnNext { chunk -> fullContent.append(chunk) }
            // 使用 ServerSentEvent 包装，转义换行符避免破坏 SSE 格式
            .map { chunk ->
                ServerSentEvent.builder<String>()
                    .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                    .build()
            }
            .doOnComplete {
                // 3. 流式完成后更新消息内容
                sessionService.completeStreamMessage(messageId, fullContent.toString())
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId)
            }
            .doOnCancel {
                // 客户端中断时保存已生成的部分内容，并结束 AI 消息占位
                val content = if (fullContent.isNotEmpty()) {
                    fullContent.toString()
                } else {
                    "【已中断】回答生成已取消"
                }
                sessionService.completeStreamMessage(messageId, content)
                log.info("RAG 聊天流式取消: sessionId={}, messageId={}", sessionId, messageId)
            }
            .doOnError { e ->
                // 错误时也保存已接收的内容
                val content = if (fullContent.isNotEmpty()) {
                    fullContent.toString()
                } else {
                    "【错误】回答生成失败：" + e.message
                }
                sessionService.completeStreamMessage(messageId, content)
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e)
            }
    }
}

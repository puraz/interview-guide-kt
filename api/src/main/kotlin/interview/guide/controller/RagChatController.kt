package interview.guide.controller

import interview.guide.common.result.Result
import interview.guide.common.util.stripCodeFences
import interview.guide.service.CreateSessionRequest
import interview.guide.service.RagChatSessionService
import interview.guide.service.RagSessionDetailVo
import interview.guide.service.RagSessionListItemVo
import interview.guide.service.RagSessionVo
import interview.guide.service.SendMessageRequest
import interview.guide.service.UpdateKnowledgeBasesRequest
import interview.guide.service.UpdateTitleRequest
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
 * 提供会话创建、列表、详情及流式聊天接口
 */
@RestController
class RagChatController(
    private val sessionService: RagChatSessionService // RAG 会话服务
) {

    private val log = LoggerFactory.getLogger(RagChatController::class.java)

    /**
     * 创建新会话
     *
     * @param request 创建会话请求 // 包含知识库ID列表和标题
     */
    @PostMapping("/api/rag-chat/sessions")
    fun createSession(@Valid @RequestBody request: CreateSessionRequest): Result<RagSessionVo> {
        return Result.success(sessionService.createSession(request))
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    fun listSessions(): Result<List<RagSessionListItemVo>> {
        return Result.success(sessionService.listSessions())
    }

    /**
     * 获取会话详情（包含消息历史）
     *
     * @param sessionId 会话ID // 路径参数
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    fun getSessionDetail(@PathVariable sessionId: Long): Result<RagSessionDetailVo> {
        return Result.success(sessionService.getSessionDetail(sessionId))
    }

    /**
     * 更新会话标题
     *
     * @param sessionId 会话ID // 路径参数
     * @param request 更新标题请求 // 包含新标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    fun updateSessionTitle(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateTitleRequest
    ): Result<Void> {
        sessionService.updateSessionTitle(sessionId, request.title)
        return Result.success(null)
    }

    /**
     * 切换会话置顶状态
     *
     * @param sessionId 会话ID // 路径参数
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    fun togglePin(@PathVariable sessionId: Long): Result<Void> {
        sessionService.togglePin(sessionId)
        return Result.success(null)
    }

    /**
     * 更新会话知识库
     *
     * @param sessionId 会话ID // 路径参数
     * @param request 更新知识库请求 // 包含知识库ID列表
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    fun updateSessionKnowledgeBases(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: UpdateKnowledgeBasesRequest
    ): Result<Void> {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds)
        return Result.success(null)
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话ID // 路径参数
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
     *
     * @param sessionId 会话ID // 路径参数
     * @param request 发送消息请求 // 包含问题
     */
    @PostMapping(
        value = ["/api/rag-chat/sessions/{sessionId}/messages/stream"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun sendMessageStream(
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: SendMessageRequest
    ): Flux<ServerSentEvent<String>> {
        log.info(
            "收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
            sessionId,
            request.question,
            Thread.currentThread(),
            Thread.currentThread().isVirtual
        )

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        val messageId = sessionService.prepareStreamMessage(sessionId, request.question)

        // 2. 获取流式响应并累积完整内容
        val fullContent = StringBuilder()

        return sessionService.getStreamAnswer(sessionId, request.question)
            .doOnNext { chunk ->
                // 拼接完整内容，用于流式结束后落库
                fullContent.append(chunk)
            }
            // 使用 ServerSentEvent 包装，转义换行符避免破坏 SSE 格式
            .map { chunk ->
                val cleanedChunk = chunk.stripCodeFences()
                ServerSentEvent.builder<String>()
                    .data(cleanedChunk.replace("\n", "\\n").replace("\r", "\\r"))
                    .build()
            }
            .doOnComplete {
                // 3. 流式完成后更新消息内容并去除代码块标记
                val cleaned = fullContent.toString().stripCodeFences()
                sessionService.completeStreamMessage(messageId, cleaned)
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId)
            }
            .doOnError { e ->
                // 错误时也保存已接收的内容
                val content = if (fullContent.isNotEmpty()) {
                    fullContent.toString().stripCodeFences()
                } else {
                    "【错误】回答生成失败：" + e.message
                }
                sessionService.completeStreamMessage(messageId, content)
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e)
            }
    }
}

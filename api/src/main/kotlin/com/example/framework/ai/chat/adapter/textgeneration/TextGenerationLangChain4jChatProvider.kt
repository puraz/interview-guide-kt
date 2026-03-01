package com.example.framework.ai.chat.adapter.textgeneration

import com.example.framework.ai.chat.AiChatRequest
import com.example.framework.ai.chat.AiChatResult
import com.example.framework.ai.chat.AiChatRole
import com.example.framework.ai.chat.AiChatStreamChunk
import com.example.framework.ai.chat.spi.AiChatProvider
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.stripCodeFences
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * 文本生成 Chat 适配器（LangChain4j 实现，OpenAI 兼容协议）。//
 *
 * 使用场景：//
 * - “做饭/菜谱”等需要稳定产出 JSON 的文本生成能力//
 *
 * 关键约束：//
 * - 下游为 OpenAI 兼容网关（如 302AI），因此使用 LangChain4j 的 OpenAiChatModel / OpenAiStreamingChatModel 直接对接//
 * - 必须对模型输出执行 stripCodeFences()，避免 AI 返回 ```json 代码块导致 JSON 解析失败//
 */
@Service
class TextGenerationLangChain4jChatProvider(
    private val properties: TextGenerationLangChain4jProperties, // 文本生成配置：baseUrl/apiKey/模型/超时等 // 运维可通过 yml 调整
) : AiChatProvider {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val providerName: String = "text-generation" // 路由标识：ai.chat.provider=text-generation 时命中 // 与配置强关联

    override fun chat(request: AiChatRequest): AiChatResult {
        val startTime = System.currentTimeMillis()
        val resolvedApiKey = properties.apiKey.trim()
        if (resolvedApiKey.isBlank()) {
            throw BusinessException("文本生成 ApiKey 未配置")
        }
        val resolvedModel = request.model?.takeIf { it.isNotBlank() } ?: properties.defaultModel // 业务指定优先，否则走默认模型
        val resolvedTemperature = request.temperature ?: properties.defaultTemperature // 业务指定优先，否则走默认温度
        val resolvedMaxTokens = request.maxTokens ?: properties.defaultMaxTokens // 业务指定优先，否则走默认 token 上限
        return try {
            val model = OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl) // OpenAI 兼容入口 // 例如 https://api.302ai.cn/v1/
                .apiKey(resolvedApiKey) // ApiKey：建议通过环境变量注入 // 避免泄露
                .modelName(resolvedModel) // 模型名：如 doubao-1.5-pro-32k // 便于统一切换
                .temperature(resolvedTemperature) // 温度：控制发散程度 // 菜谱场景更偏稳定
                .timeout(properties.timeout) // 超时保护：避免线程被长期占用//
                .apply {
                    if (resolvedMaxTokens != null) {
                        maxTokens(resolvedMaxTokens) // 最大输出 token：防止过长回复导致解析困难或成本过高//
                    }
                }
                .build()

            val messages = toLangChainMessages(request) // 消息映射：将统一消息结构转为 LangChain4j 消息
            val chatResponse = model.chat(messages) // 由 LangChain4j 发起请求 // 不再自行拼 HTTP
            val rawText = chatResponse.aiMessage().text()
            val cleaned = rawText.stripCodeFences() // 统一去除代码块标记 // 避免 ```json 包裹
            val duration = System.currentTimeMillis() - startTime
            AiChatResult(
                success = cleaned.isNotBlank(),
                content = cleaned,
                token = chatResponse.tokenUsage()?.totalTokenCount()?.toLong(), // usage 若下游返回则可读取 // 不支持时为 null
                durationMillis = duration,
                provider = providerName,
                resolvedModel = resolvedModel,
                errorMessage = null,
                errorBody = null,
            )
        } catch (ex: Exception) {
            logger.error("文本生成 Chat 调用失败, model={}", resolvedModel, ex)
            val duration = System.currentTimeMillis() - startTime
            AiChatResult(
                success = false,
                content = null,
                token = null,
                durationMillis = duration,
                provider = providerName,
                resolvedModel = resolvedModel,
                errorMessage = ex.message ?: "文本生成调用失败",
                errorBody = null,
            )
        }
    }

    override fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit) {
        // 说明：流式对接使用 LangChain4j 的 OpenAiStreamingChatModel；若下游不支持，异常会被捕获并以 finished=true 结束。//
        val startTime = System.currentTimeMillis()
        val resolvedApiKey = properties.apiKey.trim()
        if (resolvedApiKey.isBlank()) {
            throw BusinessException("文本生成 ApiKey 未配置")
        }
        val resolvedModel = request.model?.takeIf { it.isNotBlank() } ?: properties.defaultModel
        val resolvedTemperature = request.temperature ?: properties.defaultTemperature
        val resolvedMaxTokens = request.maxTokens ?: properties.defaultMaxTokens
        val fullBuilder = StringBuilder()
        val latch = CountDownLatch(1) // 将异步流式回调转换为同步生命周期控制 // 方便上层收尾
        val errorRef = AtomicReference<Throwable?>(null) // 记录流式过程中异常 // 结束后统一处理
        try {
            val model = OpenAiStreamingChatModel.builder()
                .baseUrl(properties.baseUrl) // OpenAI 兼容入口//
                .apiKey(resolvedApiKey) // ApiKey：建议通过环境变量注入//
                .modelName(resolvedModel) // 模型名//
                .temperature(resolvedTemperature) // 温度//
                .timeout(properties.timeout) // 超时//
                .apply {
                    if (resolvedMaxTokens != null) {
                        maxTokens(resolvedMaxTokens) // 最大输出 token//
                    }
                }
                .build()

            val messages = toLangChainMessages(request)

            model.chat(messages, object : dev.langchain4j.model.chat.response.StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String?) {
                    if (partialResponse.isNullOrBlank()) {
                        return
                    }
                    val cleanedDelta = partialResponse.stripCodeFences() // 增量也要去代码块 // 避免分片中出现 ``` 影响渲染
                    fullBuilder.append(cleanedDelta)
                    chunkConsumer(
                        AiChatStreamChunk(
                            contentDelta = cleanedDelta, // 逐片推送增量 // SSE 前端可直接追加
                            fullContent = fullBuilder.toString(), // 当前累计文本 // 便于落库
                            finished = false,
                        )
                    )
                }

                override fun onCompleteResponse(completeResponse: ChatResponse?) {
                    val cleaned = fullBuilder.toString().stripCodeFences() // 收尾再做一次去代码块 // 防止首尾多余
                    val duration = System.currentTimeMillis() - startTime
                    val tokenUsage =
                        completeResponse?.tokenUsage()?.totalTokenCount()?.toLong() // 读取总 token 用量（可能为空）// 供业务扣费
                    chunkConsumer(
                        AiChatStreamChunk(
                            contentDelta = "",
                            fullContent = cleaned, // finished=true 时返回最终值 // 业务层用于落库
                            finished = true,
                            token = tokenUsage, // 透出 token 总数（若支持）// 业务层可按 token 计费
                        )
                    )
                    logger.info(
                        "文本生成 Stream 完成, model={}, durationMs={}, token={}",
                        resolvedModel,
                        duration,
                        tokenUsage
                    )
                    latch.countDown()
                }

                override fun onError(error: Throwable) {
                    errorRef.set(error)
                    latch.countDown()
                }
            })

            latch.await() // 等待流式结束（complete 或 error）// 保证调用方在方法返回前能收到 finished
            errorRef.get()?.let { throw it }
        } catch (ex: Exception) {
            logger.error("文本生成 Stream 调用失败, model={}", resolvedModel, ex)
            chunkConsumer(
                AiChatStreamChunk(
                    contentDelta = "",
                    fullContent = fullBuilder.toString().stripCodeFences(),
                    finished = true,
                )
            )
            // 这里不抛出异常，避免上层 SSE 线程中断导致无法通知前端；上层可通过 finished=true + fullContent 判断是否兜底。//
        }
    }

    private fun toLangChainMessages(request: AiChatRequest): List<ChatMessage> {
        // 将项目内部的 AiChatMessage 映射为 LangChain4j 的 ChatMessage；此处是唯一的模型 SDK 依赖点。//
        return request.messages
            .asSequence()
            .filter { it.content.isNotBlank() } // 过滤空内容，避免下游报错或浪费 token//
            .map { message ->
                when (message.role) {
                    AiChatRole.SYSTEM -> SystemMessage(message.content)
                    AiChatRole.USER -> UserMessage(message.content)
                    AiChatRole.ASSISTANT -> AiMessage(message.content)
                }
            }
            .toList()
    }
}

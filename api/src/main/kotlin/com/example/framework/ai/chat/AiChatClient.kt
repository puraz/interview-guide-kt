package com.example.framework.ai.chat

/**
 * 通用大模型 Chat 调用入口（业务层唯一依赖）。
 *
 * 设计目标：
 * 1. 业务层只依赖此接口，不感知 DeepSeek/Qwen/OpenAI 等具体实现 // 解耦模型厂商与业务
 * 2. 通过 Spring 注入具体实现（通常为路由器 AiChatRouter） // 方便按配置切换/扩展
 * 3. 统一承载非流式与流式两种调用方式 // 满足报表生成与聊天流式两类场景
 */
interface AiChatClient {

    /**
     * 非流式对话：一次请求得到一段完整回答。
     *
     * @param request AiChatRequest 对话请求，包含消息列表与可选参数（温度/最大Token/模型名等） // 业务层构造上下文的唯一输入
     * @return AiChatResult 返回统一结构，包含 content、token、耗时与错误信息 // 业务层按 success 判断后续逻辑
     */
    fun chat(request: AiChatRequest): AiChatResult

    /**
     * 流式对话：逐片返回增量内容，适用于 SSE/WebSocket 推送。
     *
     * @param request AiChatRequest 对话请求，messages 中通常包含 system + 历史 + user // 与 chat 保持同一入参模型
     * @param chunkConsumer Function1<AiChatStreamChunk, Unit> 每次收到增量内容/完成信号后回调 // 业务层负责推送给前端
     */
    fun streamChat(request: AiChatRequest, chunkConsumer: (AiChatStreamChunk) -> Unit)
}


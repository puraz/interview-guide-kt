package interview.guide.modules.knowledgebase.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.LocalDateTime

/**
 * RAG聊天相关VO
 */
class RagChatVo {

    // ========== 请求 DTO ==========

    /**
     * 创建会话请求
     */
    data class CreateSessionRequest(
        @field:NotEmpty(message = "至少选择一个知识库")
        val knowledgeBaseIds: List<Long>, // 知识库ID列表

        val title: String? // 可选，为空则自动生成
    )

    /**
     * 发送消息请求
     */
    data class SendMessageRequest(
        @field:NotBlank(message = "问题不能为空")
        val question: String // 用户问题
    )

    /**
     * 更新标题请求
     */
    data class UpdateTitleRequest(
        @field:NotBlank(message = "标题不能为空")
        val title: String // 标题
    )

    /**
     * 更新知识库请求
     */
    data class UpdateKnowledgeBasesRequest(
        @field:NotEmpty(message = "至少选择一个知识库")
        val knowledgeBaseIds: List<Long> // 知识库ID列表
    )

    // ========== 响应 DTO ==========

    /**
     * 会话基础信息
     */
    data class SessionDTO(
        val id: Long?, // 会话ID
        val title: String?, // 会话标题
        val knowledgeBaseIds: List<Long>, // 关联知识库ID列表
        val createdAt: LocalDateTime? // 创建时间
    )

    /**
     * 会话列表项
     */
    data class SessionListItemDTO(
        val id: Long?, // 会话ID
        val title: String?, // 会话标题
        val messageCount: Int?, // 消息数量
        val knowledgeBaseNames: List<String>, // 关联知识库名称
        val updatedAt: LocalDateTime?, // 更新时间
        val isPinned: Boolean? // 是否置顶
    )

    /**
     * 会话详情（含消息）
     */
    data class SessionDetailDTO(
        val id: Long?, // 会话ID
        val title: String?, // 会话标题
        val knowledgeBases: List<KnowledgeBaseListItemVo>, // 关联知识库列表
        val messages: List<MessageDTO>, // 消息列表
        val createdAt: LocalDateTime?, // 创建时间
        val updatedAt: LocalDateTime? // 更新时间
    )

    /**
     * 消息 DTO
     */
    data class MessageDTO(
        val id: Long?, // 消息ID
        val type: String?, // "user" | "assistant"
        val content: String?, // 消息内容
        val createdAt: LocalDateTime? // 创建时间
    )
}

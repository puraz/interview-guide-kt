package interview.guide.service

import interview.guide.entity.VectorStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.LocalDateTime

/**
 * 知识库列表项返回对象
 */
data class KnowledgeBaseListItemVo(
    val id: Long?, // 知识库ID
    val name: String?, // 知识库名称
    val category: String?, // 分类
    val originalFilename: String?, // 原始文件名
    val fileSize: Long?, // 文件大小
    val contentType: String?, // 文件类型
    val uploadedAt: LocalDateTime?, // 上传时间
    val lastAccessedAt: LocalDateTime?, // 最后访问时间
    val accessCount: Int?, // 访问次数
    val questionCount: Int?, // 提问次数
    val vectorStatus: VectorStatus?, // 向量化状态
    val vectorError: String?, // 向量化错误
    val chunkCount: Int? // 分块数量
)

/**
 * 知识库统计信息返回对象
 */
data class KnowledgeBaseStatsVo(
    val totalCount: Long, // 知识库总数
    val totalQuestionCount: Long, // 总提问次数
    val totalAccessCount: Long, // 总访问次数
    val completedCount: Long, // 已完成向量化数量
    val processingCount: Long // 处理中数量
)

/**
 * 知识库查询请求
 */
data class QueryRequest(
    @field:NotEmpty(message = "至少选择一个知识库")
    val knowledgeBaseIds: List<Long>, // 知识库ID列表
    @field:NotBlank(message = "问题不能为空")
    val question: String // 用户问题
) {
    constructor(knowledgeBaseId: Long, question: String) : this(listOf(knowledgeBaseId), question) // 兼容单知识库
}

/**
 * 知识库查询响应
 */
data class QueryResponseVo(
    val answer: String, // AI 回答
    val knowledgeBaseId: Long?, // 主知识库ID
    val knowledgeBaseName: String? // 知识库名称
)

/**
 * RAG聊天创建会话请求
 */
data class CreateSessionRequest(
    @field:NotEmpty(message = "至少选择一个知识库")
    val knowledgeBaseIds: List<Long>, // 知识库ID列表
    val title: String? // 会话标题
)

/**
 * RAG聊天发送消息请求
 */
data class SendMessageRequest(
    @field:NotBlank(message = "问题不能为空")
    val question: String // 用户问题
)

/**
 * RAG聊天更新标题请求
 */
data class UpdateTitleRequest(
    @field:NotBlank(message = "标题不能为空")
    val title: String // 会话标题
)

/**
 * RAG聊天更新知识库请求
 */
data class UpdateKnowledgeBasesRequest(
    @field:NotEmpty(message = "至少选择一个知识库")
    val knowledgeBaseIds: List<Long> // 知识库ID列表
)

/**
 * RAG会话基础信息返回对象
 */
data class RagSessionVo(
    val id: Long?, // 会话ID
    val title: String?, // 会话标题
    val knowledgeBaseIds: List<Long>, // 知识库ID列表
    val createdAt: LocalDateTime? // 创建时间
)

/**
 * RAG会话列表项返回对象
 */
data class RagSessionListItemVo(
    val id: Long?, // 会话ID
    val title: String?, // 会话标题
    val messageCount: Int?, // 消息数量
    val knowledgeBaseNames: List<String>, // 知识库名称列表
    val updatedAt: LocalDateTime?, // 更新时间
    val isPinned: Boolean? // 是否置顶
)

/**
 * RAG会话详情返回对象
 */
data class RagSessionDetailVo(
    val id: Long?, // 会话ID
    val title: String?, // 会话标题
    val knowledgeBases: List<KnowledgeBaseListItemVo>, // 知识库列表
    val messages: List<RagMessageVo>, // 消息列表
    val createdAt: LocalDateTime?, // 创建时间
    val updatedAt: LocalDateTime? // 更新时间
)

/**
 * RAG消息返回对象
 */
data class RagMessageVo(
    val id: Long?, // 消息ID
    val type: String?, // 消息类型
    val content: String?, // 消息内容
    val createdAt: LocalDateTime? // 创建时间
)

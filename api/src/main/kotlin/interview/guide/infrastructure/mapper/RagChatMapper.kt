package interview.guide.infrastructure.mapper

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemVo
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity
import interview.guide.modules.knowledgebase.model.RagChatVo
import org.springframework.stereotype.Component

/**
 * RAG聊天相关实体到VO的映射器
 * 原 Java 版使用 MapStruct 自动生成，此处改为手写映射，字段一一对应
 */
@Component
class RagChatMapper(
    private val knowledgeBaseMapper: KnowledgeBaseMapper // 知识库实体映射器
) {

    /**
     * 将会话实体转换为会话VO
     *
     * @param session 会话实体 // 数据库实体
     * @return 会话VO // 接口返回对象
     */
    fun toSessionDTO(session: RagChatSessionEntity): RagChatVo.SessionDTO {
        return RagChatVo.SessionDTO(
            id = session.id,
            title = session.title,
            knowledgeBaseIds = extractKnowledgeBaseIds(session),
            createdAt = session.createdAt
        )
    }

    /**
     * 将消息实体转换为消息VO
     *
     * @param message 消息实体 // 数据库实体
     * @return 消息VO // 接口返回对象
     */
    fun toMessageDTO(message: RagChatMessageEntity): RagChatVo.MessageDTO {
        return RagChatVo.MessageDTO(
            id = message.id,
            type = getTypeString(message),
            content = message.content,
            createdAt = message.createdAt
        )
    }

    /**
     * 将消息实体列表转换为消息VO列表
     */
    fun toMessageDTOList(messages: List<RagChatMessageEntity>): List<RagChatVo.MessageDTO> {
        return messages.map { toMessageDTO(it) }
    }

    /**
     * 将知识库实体集合转换为知识库名称列表
     * 支持 Set 和 List 类型
     */
    fun extractKnowledgeBaseNames(knowledgeBases: Collection<KnowledgeBaseEntity>): List<String> {
        return knowledgeBases.map { it.name ?: "" }
    }

    /**
     * 从会话实体中提取知识库ID列表
     */
    fun extractKnowledgeBaseIds(session: RagChatSessionEntity): List<Long> {
        return session.getKnowledgeBaseIds()
    }

    /**
     * 获取消息类型字符串
     */
    fun getTypeString(message: RagChatMessageEntity): String {
        return message.getTypeString()
    }

    /**
     * 将会话实体转换为会话列表项VO
     * 需要特殊处理：提取知识库名称列表和处理isPinned的null值
     */
    fun toSessionListItemDTO(session: RagChatSessionEntity): RagChatVo.SessionListItemDTO {
        return RagChatVo.SessionListItemDTO(
            id = session.id,
            title = session.title,
            messageCount = session.messageCount,
            knowledgeBaseNames = extractKnowledgeBaseNames(session.knowledgeBases),
            updatedAt = session.updatedAt,
            isPinned = getIsPinnedWithDefault(session)
        )
    }

    /**
     * 处理isPinned的null值，默认为false
     */
    fun getIsPinnedWithDefault(session: RagChatSessionEntity): Boolean {
        return session.isPinned ?: false
    }

    /**
     * 将会话实体和消息列表转换为会话详情VO
     * 注意：这个方法需要手动组合多个数据源，知识库列表的转换由调用方使用 KnowledgeBaseMapper 完成
     */
    fun toSessionDetailDTO(
        session: RagChatSessionEntity,
        messages: List<RagChatMessageEntity>,
        knowledgeBases: List<KnowledgeBaseListItemVo>
    ): RagChatVo.SessionDetailDTO {
        return RagChatVo.SessionDetailDTO(
            id = session.id,
            title = session.title,
            knowledgeBases = knowledgeBases,
            messages = toMessageDTOList(messages),
            createdAt = session.createdAt,
            updatedAt = session.updatedAt
        )
    }

    /**
     * 将会话关联的知识库实体集合转换为列表项VO列表（供 Service 组装详情使用）
     */
    fun toKnowledgeBaseListItems(knowledgeBases: Collection<KnowledgeBaseEntity>): List<KnowledgeBaseListItemVo> {
        return knowledgeBaseMapper.toListItemDTOList(knowledgeBases.toList())
    }
}

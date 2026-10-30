package interview.guide.infrastructure.mapper

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemVo
import org.springframework.stereotype.Component

/**
 * 知识库实体到VO的映射器
 * 原 Java 版使用 MapStruct 自动生成，此处改为手写映射，字段一一对应
 */
@Component
class KnowledgeBaseMapper {

    /**
     * 将知识库实体转换为列表项VO
     *
     * @param entity 知识库实体 // 数据库实体
     * @return 列表项VO // 接口返回对象
     */
    fun toListItemDTO(entity: KnowledgeBaseEntity): KnowledgeBaseListItemVo {
        return KnowledgeBaseListItemVo(
            id = entity.id,
            name = entity.name,
            category = entity.category,
            originalFilename = entity.originalFilename,
            fileSize = entity.fileSize,
            contentType = entity.contentType,
            uploadedAt = entity.uploadedAt,
            lastAccessedAt = entity.lastAccessedAt,
            accessCount = entity.accessCount,
            questionCount = entity.questionCount,
            vectorStatus = entity.vectorStatus,
            vectorError = entity.vectorError,
            chunkCount = entity.chunkCount,
            questionGenStatus = entity.questionGenStatus,
            questionGenError = entity.questionGenError
        )
    }

    /**
     * 将知识库实体列表转换为列表项VO列表
     *
     * @param entities 知识库实体列表 // 数据库实体集合
     * @return 列表项VO列表 // 接口返回对象集合
     */
    fun toListItemDTOList(entities: List<KnowledgeBaseEntity>): List<KnowledgeBaseListItemVo> {
        return entities.map { toListItemDTO(it) }
    }
}

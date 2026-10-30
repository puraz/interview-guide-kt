package interview.guide.modules.knowledgebase.model

/**
 * 知识库列表项VO
 * 由 KnowledgeBaseMapper 手动映射生成
 */
data class KnowledgeBaseListItemVo(
    val id: Long?, // 知识库ID
    val name: String?, // 知识库名称
    val category: String?, // 分类
    val originalFilename: String?, // 原始文件名
    val fileSize: Long?, // 文件大小
    val contentType: String?, // 文件类型
    val uploadedAt: java.time.LocalDateTime?, // 上传时间
    val lastAccessedAt: java.time.LocalDateTime?, // 最后访问时间
    val accessCount: Int?, // 访问次数
    val questionCount: Int?, // 提问次数
    val vectorStatus: VectorStatus?, // 向量化状态
    val vectorError: String?, // 向量化错误信息
    val chunkCount: Int?, // 分块数量
    val questionGenStatus: QuestionGenStatus?, // 题目生成状态
    val questionGenError: String? // 题目生成错误信息
)

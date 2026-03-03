package interview.guide.entity

/**
 * 知识库向量化状态
 */
enum class VectorStatus {
    PENDING, // 待处理
    PROCESSING, // 处理中
    COMPLETED, // 完成
    FAILED // 失败
}

package interview.guide.common.constant

/**
 * 通用常量定义
 */
object CommonConstants {

    /**
     * 状态码
     */
    object StatusCode {
        const val SUCCESS: Int = 200 // 成功
        const val BAD_REQUEST: Int = 400 // 参数错误
        const val UNAUTHORIZED: Int = 401 // 未授权
        const val FORBIDDEN: Int = 403 // 禁止访问
        const val NOT_FOUND: Int = 404 // 资源不存在
        const val SERVER_ERROR: Int = 500 // 服务器错误
    }

    /**
     * 分页默认值
     */
    object Pagination {
        const val DEFAULT_PAGE: Int = 1 // 默认页码
        const val DEFAULT_SIZE: Int = 20 // 默认每页大小
        const val MAX_SIZE: Int = 100 // 最大每页大小
    }
}

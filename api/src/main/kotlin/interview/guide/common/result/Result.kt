package interview.guide.common.result

import interview.guide.common.constant.CommonConstants
import interview.guide.common.exception.ErrorCode

/**
 * 统一响应结果
 */
class Result<T> private constructor(
    val code: Int, // 状态码
    val message: String, // 响应消息
    val data: T? // 响应数据
) {

    companion object {
        // ========== 成功响应 ==========

        /**
         * 成功响应（无数据）
         */
        fun <T> success(): Result<T> {
            return Result(CommonConstants.StatusCode.SUCCESS, "success", null)
        }

        /**
         * 成功响应（带数据）
         *
         * @param data 响应数据 // 业务数据
         */
        fun <T> success(data: T?): Result<T> {
            return Result(CommonConstants.StatusCode.SUCCESS, "success", data)
        }

        /**
         * 成功响应（自定义消息）
         *
         * @param message 响应消息 // 自定义成功提示
         * @param data 响应数据 // 业务数据
         */
        fun <T> success(message: String, data: T?): Result<T> {
            return Result(CommonConstants.StatusCode.SUCCESS, message, data)
        }

        // ========== 失败响应 ==========

        /**
         * 失败响应（默认服务器错误码）
         *
         * @param message 错误消息 // 失败原因
         */
        fun <T> error(message: String): Result<T> {
            return Result(CommonConstants.StatusCode.SERVER_ERROR, message, null)
        }

        /**
         * 失败响应（自定义错误码）
         *
         * @param code 错误码 // 业务错误码
         * @param message 错误消息 // 失败原因
         */
        fun <T> error(code: Int, message: String): Result<T> {
            return Result(code, message, null)
        }

        /**
         * 失败响应（使用枚举错误码）
         *
         * @param errorCode 错误码枚举 // 统一错误码
         */
        fun <T> error(errorCode: ErrorCode): Result<T> {
            return Result(errorCode.code, errorCode.message, null)
        }

        /**
         * 失败响应（使用枚举错误码+自定义消息）
         *
         * @param errorCode 错误码枚举 // 统一错误码
         * @param message 错误消息 // 自定义失败原因
         */
        fun <T> error(errorCode: ErrorCode, message: String): Result<T> {
            return Result(errorCode.code, message, null)
        }
    }

    /**
     * 是否成功
     */
    fun isSuccess(): Boolean {
        return code == CommonConstants.StatusCode.SUCCESS
    }
}

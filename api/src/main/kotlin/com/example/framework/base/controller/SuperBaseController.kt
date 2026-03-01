package com.example.framework.base.controller

import com.example.framework.base.result.ApiResult
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.ContextUtil

/**
 * 基础接口。//
 *
 * 设计目的：//
 * - 提供统一的 ApiResult 组装方法，减少各 Controller 重复代码。//
 * - 提供统一的“当前登录用户ID”获取方式，避免每个接口都手写一套登录校验。//
 *
 * @author gcc
 */
interface SuperBaseController {

    /**
     * 返回成功（无 data）。//
     *
     * 返回字段用途：//
     * - code：0 表示成功（由 ApiResult.success() 内部约定）。//
     * - message：默认成功提示（由 ApiResult.success() 内部约定）。//
     * - data：无。//
     *
     * @return ApiResult<T>
     */
    fun <T> success(): ApiResult<T> = ApiResult.success()

    /**
     * 返回成功（自定义 message）。//
     *
     * @param message 成功提示文案 // 用于前端 toast/提示条展示
     *
     * @return ApiResult<T>
     */
    fun <T> success(message: String): ApiResult<T> = ApiResult.success(message = message)

    /**
     * 返回成功（携带 data）。//
     *
     * @param data 业务返回数据 // 接口真正返回体
     *
     * @return ApiResult<T>
     */
    fun <T> success(data: T): ApiResult<T> = ApiResult.success(data = data)

    /**
     * 返回成功（自定义 message + 携带 data）。//
     *
     * @param message 成功提示文案 // 用于前端 toast/提示条展示
     * @param data 业务返回数据 // 接口真正返回体
     *
     * @return ApiResult<T>
     */
    fun <T> success(message: String, data: T): ApiResult<T> = ApiResult.success(message = message, data = data)

    /**
     * 返回失败（无 data）。//
     *
     * 返回字段用途：//
     * - code：非 0 表示失败（由 ApiResult.fail() 内部约定）。//
     * - message：默认失败提示（由 ApiResult.fail() 内部约定）。//
     * - data：无。//
     *
     * @return ApiResult<T>
     */
    fun <T> fail(): ApiResult<T> = ApiResult.fail()

    /**
     * 返回失败（自定义 message）。//
     *
     * @param message 失败提示文案 // 用于前端 toast/提示条展示
     *
     * @return ApiResult<T>
     */
    fun <T> fail(message: String): ApiResult<T> = ApiResult.fail(message = message)

    /**
     * 返回失败（携带 data）。//
     *
     * @param data 业务返回数据 // 失败时用于返回额外信息（如：表单错误详情）
     *
     * @return ApiResult<T>
     */
    fun <T> fail(data: T): ApiResult<T> = ApiResult.fail(data = data)

    /**
     * 返回失败（自定义 message + 携带 data）。//
     *
     * @param message 失败提示文案 // 用于前端 toast/提示条展示
     * @param data 业务返回数据 // 失败时用于返回额外信息（如：表单错误详情）
     *
     * @return ApiResult<T>
     */
    fun <T> fail(message: String, data: T): ApiResult<T> = ApiResult.fail(message = message, data = data)

    /**
     * 获取当前登录用户ID（统一入口）。//
     *
     * 使用场景：//
     * - 需要将业务数据与用户绑定（如：user_id 落库、鉴权、按用户查询）时使用。//
     *
     * 行为说明：//
     * - 当未登录（userId <= 0）时，直接抛出 BusinessException 终止请求。//
     *
     * @param notLoginMessage 未登录时的提示文案 // 业务侧可按场景自定义（如：请先登录后再体验xx）
     * @return Long 当前登录用户ID // 用于业务层传参、落库关联、权限判断
     */
    fun currentUserId(notLoginMessage: String = "请先登录"): Long {
        val userId = ContextUtil.getUserId() // 从上下文获取用户ID（通常由鉴权拦截器写入）
        if (userId <= 0) {
            throw BusinessException(notLoginMessage) // 未登录直接拦截，避免出现匿名数据落库
        }
        return userId
    }

}

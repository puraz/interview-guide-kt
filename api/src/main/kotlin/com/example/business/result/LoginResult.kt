package com.example.business.result

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 登录返回结果
 * @author gcc
 */
@Schema(description = "登录返回结果")
data class LoginResult(

    /** token名称 */
    @field:Schema(description = "token名称")
    val tokenName: String? = null,

    /** token值 */
    @field:Schema(description = "token值")
    val token: String? = null
)

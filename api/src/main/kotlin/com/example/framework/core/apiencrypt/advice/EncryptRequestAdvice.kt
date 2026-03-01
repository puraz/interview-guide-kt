package com.example.framework.core.apiencrypt.advice

import com.example.framework.base.result.ApiResult
import com.example.framework.core.apiencrypt.annotation.ApiEncrypt
import com.example.framework.core.apiencrypt.service.ApiEncryptService
import com.example.framework.core.enums.DataTypeEnum
import com.example.framework.core.utils.JSONUtil
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

@ControllerAdvice
class EncryptRequestAdvice(
    private val apiEncryptService: ApiEncryptService
) : ResponseBodyAdvice<ApiResult<Any>> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>?>
    ): Boolean {
        return returnType.hasMethodAnnotation(ApiEncrypt::class.java) || returnType.containingClass
            .isAnnotationPresent(ApiEncrypt::class.java)
    }

    override fun beforeBodyWrite(
        body: ApiResult<Any>?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>?>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): ApiResult<Any>? {
        val data = body?.data ?: return body

        val jsonStr = JSONUtil.toJsonStr(data) ?: return body

        body.data = apiEncryptService.encrypt(jsonStr)
        body.dataType = DataTypeEnum.ENCRYPT.code
        return body
    }
}
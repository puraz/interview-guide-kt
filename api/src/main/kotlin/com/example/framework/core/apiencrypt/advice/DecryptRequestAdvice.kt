package com.example.framework.core.apiencrypt.advice

import com.example.framework.core.apiencrypt.annotation.ApiEncrypt
import com.example.framework.core.apiencrypt.domain.ApiEncryptForm
import com.example.framework.core.apiencrypt.service.ApiEncryptService
import com.example.framework.core.utils.JSONUtil
import org.apache.commons.io.IOUtils
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import java.io.InputStream
import java.lang.reflect.Type

@ControllerAdvice
class DecryptRequestAdvice(
    private val apiEncryptService: ApiEncryptService
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>?>
    ): Boolean {
        return methodParameter
            .hasMethodAnnotation(ApiEncrypt::class.java)
                || methodParameter.hasParameterAnnotation(ApiEncrypt::class.java)
                || methodParameter.containingClass.isAnnotationPresent(ApiEncrypt::class.java)
    }

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>?>
    ): HttpInputMessage {
        val bodyStr: String? = IOUtils.toString(inputMessage.body, "utf-8")
        val apiEncryptForm = JSONUtil.parseObject(bodyStr, ApiEncryptForm::class.java)
        if (apiEncryptForm == null || apiEncryptForm.encryptData.isBlank()) {
            return inputMessage
        }
        val decrypt = apiEncryptService.decrypt(apiEncryptForm.encryptData)
        return DecryptHttpInputMessage(inputMessage.headers, IOUtils.toInputStream(decrypt, "utf-8"))
    }

    override fun afterBodyRead(
        body: Any,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>?>
    ): Any {
        return super.afterBodyRead(body, inputMessage, parameter, targetType, converterType)
    }

    override fun handleEmptyBody(
        body: Any?,
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>?>
    ): Any? {
        return super.handleEmptyBody(body, inputMessage, parameter, targetType, converterType)
    }

    class DecryptHttpInputMessage(private val headers: HttpHeaders, private val body: InputStream) :
        HttpInputMessage {
        override fun getBody(): InputStream {
            return body
        }

        override fun getHeaders(): HttpHeaders {
            return headers
        }
    }
}


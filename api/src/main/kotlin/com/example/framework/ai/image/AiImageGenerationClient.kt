package com.example.framework.ai.image

import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.util.*

/**
 * 图片生成 HTTP 客户端（智谱 CogView OpenAPI）。//
 *
 * 使用说明：//
 * - 只负责：prompt -> 调用下游 -> 获取图片（base64 或 url）-> 返回 ByteArray//
 * - 不负责：七牛上传、落库字段写入（由业务服务负责）//
 *
 * 返回兼容策略：//
 * - 优先读取 data[0].b64_json（无需二次下载）//
 * - 若只有 data[0].url，则会二次 GET 拉取图片字节//
 */
@Service
class AiImageGenerationClient(
    private val properties: AiImageGenerationProperties, // 图片生成配置：baseUrl/apiKey/model/timeout//
) {

    private val logger = LoggerFactory.getLogger(AiImageGenerationClient::class.java)

    private val restTemplate: RestTemplate by lazy {
        val timeoutMillis = properties.timeout.toMillis().coerceIn(1_000L, 600_000L).toInt() // 超时限制：1s~10min//
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeoutMillis) // 连接超时//
            setReadTimeout(timeoutMillis) // 读取超时//
        }
        RestTemplate(factory)
    }

    /**
     * 生成图片并返回图片字节（PNG/JPG 均可，业务侧按 mimeType 上传）。//
     *
     * @param prompt String 图片提示词（建议中文，描述越具体越稳定）// 由业务侧组装
     * @param size String 图片尺寸（默认 1024x1024）// 需要正方形便于小程序卡片裁剪
     * @param responseFormat String 返回格式（默认 b64_json）// 优先走 base64，避免二次下载
     * @return GeneratedImage 返回图片字节与 mimeType//
     */
    fun generateImage(prompt: String, size: String = "1024x1024", responseFormat: String = "b64_json"): GeneratedImage {
        val apiKey = properties.apiKey.trim()
        if (apiKey.isBlank()) {
            throw BusinessException("图片生成 ApiKey 未配置")
        }
        val baseUrl = properties.baseUrl.trim()
        if (baseUrl.isBlank()) {
            throw BusinessException("图片生成 baseUrl 未配置")
        }
        if (prompt.isBlank()) {
            throw BusinessException("图片生成 prompt 为空")
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
            setBearerAuth(apiKey) // 智谱 OpenAPI：Authorization: Bearer {apiKey}//
        }

        val requestBody: Map<String, Any?> = mapOf(
            "model" to properties.model, // 图片生成模型：cogview-3-flash//
            "prompt" to prompt, // 图片提示词//
            "size" to size, // 图片尺寸：1024x1024//
            "n" to 1, // 生成张数：固定 1 张//
            "response_format" to responseFormat, // 返回格式：优先 b64_json，避免二次下载//
        )

        val responseText = try {
            val entity = HttpEntity(requestBody, headers)
            val response: ResponseEntity<String> =
                restTemplate.exchange(baseUrl, HttpMethod.POST, entity, String::class.java)
            response.body?.trim().orEmpty()
        } catch (ex: RestClientException) {
            logger.error("图片生成请求失败, baseUrl={}, model={}", baseUrl, properties.model, ex)
            throw BusinessException("图片生成失败，请稍后再试")
        }

        if (responseText.isBlank()) {
            throw BusinessException("图片生成失败：响应为空")
        }

        // 解析响应：优先 b64_json，其次 url；统一使用 JSONUtil，避免引入额外 JSON 依赖。//
        val root = runCatching { JSONUtil.parseObject(responseText, Map::class.java) as Map<*, *> }.getOrElse {
            logger.error("图片生成响应解析失败, response={}", responseText, it)
            throw BusinessException("图片生成失败：响应解析失败")
        }

        val data = (root["data"] as? List<*>)?.firstOrNull() as? Map<*, *>
        if (data == null) {
            val message = (root["error"] as? Map<*, *>)?.get("message")?.toString()
            logger.warn("图片生成响应缺少 data 字段, message={}, response={}", message, responseText)
            throw BusinessException(message ?: "图片生成失败：响应格式不正确")
        }

        val b64 = data["b64_json"]?.toString()?.trim().orEmpty()
        if (b64.isNotBlank()) {
            val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrElse {
                logger.error("图片生成 base64 解码失败", it)
                throw BusinessException("图片生成失败：图片解码失败")
            }
            return GeneratedImage(bytes = bytes, mimeType = "image/png") // 默认按 png 上传；若下游实际为 jpg，也可正常展示//
        }

        val url = data["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            logger.warn("图片生成响应缺少 b64_json/url, response={}", responseText)
            throw BusinessException("图片生成失败：未返回图片内容")
        }

        // url 下载兜底：若下游未返回 base64，则二次 GET 拉取图片字节。//
        val imageBytes = try {
            val imgHeaders = HttpHeaders().apply { accept = listOf(MediaType.ALL) } // 下游可能返回 image/png 或 image/jpeg//
            val entity = HttpEntity<Void>(imgHeaders)
            val response: ResponseEntity<ByteArray> =
                restTemplate.exchange(url, HttpMethod.GET, entity, ByteArray::class.java)
            response.body ?: ByteArray(0)
        } catch (ex: RestClientException) {
            logger.error("图片生成 url 下载失败, url={}", url, ex)
            throw BusinessException("图片生成失败：图片下载失败")
        }

        if (imageBytes.isEmpty()) {
            throw BusinessException("图片生成失败：图片内容为空")
        }

        return GeneratedImage(bytes = imageBytes, mimeType = "image/png") // mimeType 兜底为 png，业务侧可按需调整//
    }

    /**
     * 图片生成结果。//
     */
    data class GeneratedImage(
        val bytes: ByteArray, // 图片字节内容//
        val mimeType: String, // 图片 mimeType：image/png 等//
    )
}


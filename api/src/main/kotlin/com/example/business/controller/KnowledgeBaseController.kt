package com.example.business.controller

import com.example.business.enums.VectorStatus
import com.example.business.service.KnowledgeBaseService
import com.example.framework.base.controller.SuperBaseController
import com.example.framework.base.result.ApiResult
import com.example.framework.core.exception.BusinessException
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Flux
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 知识库控制器
 *
 * 提供知识库上传、检索、统计等接口。
 */
@Validated
@RestController
class KnowledgeBaseController(
    private val knowledgeBaseService: KnowledgeBaseService
) : SuperBaseController {
    private val logger = LoggerFactory.getLogger(KnowledgeBaseController::class.java)

    /**
     * 获取知识库列表
     *
     * @param sortBy 排序字段
     * @param vectorStatus 向量化状态
     * @return 知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    fun getAllKnowledgeBases(
        @RequestParam(value = "sortBy", required = false) sortBy: String?,
        @RequestParam(value = "vectorStatus", required = false) vectorStatus: String?
    ): ApiResult<List<KnowledgeBaseService.KnowledgeBaseListItemVo>> {
        val status = vectorStatus?.takeIf { it.isNotBlank() }?.let {
            runCatching { VectorStatus.valueOf(it.uppercase()) }
                .getOrElse { throw BusinessException("无效的向量化状态: $vectorStatus") }
        }
        return success(knowledgeBaseService.listKnowledgeBases(status, sortBy))
    }

    /**
     * 获取知识库详情
     *
     * @param id 知识库ID
     * @return 知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    fun getKnowledgeBase(@PathVariable id: Long): ApiResult<KnowledgeBaseService.KnowledgeBaseListItemVo> {
        val info = knowledgeBaseService.getKnowledgeBase(id) ?: throw BusinessException("知识库不存在")
        return success(info)
    }

    /**
     * 删除知识库
     *
     * @param id 知识库ID
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    fun deleteKnowledgeBase(@PathVariable id: Long): ApiResult<Void> {
        knowledgeBaseService.deleteKnowledgeBase(id)
        return success()
    }

    /**
     * 知识库查询
     *
     * @param param 查询参数
     * @return 查询结果
     */
    @PostMapping("/api/knowledgebase/query")
    fun queryKnowledgeBase(
        @Valid @RequestBody param: KnowledgeBaseService.QueryParam
    ): ApiResult<KnowledgeBaseService.QueryResponseVo> {
        return success(knowledgeBaseService.queryKnowledgeBase(param))
    }

    /**
     * 知识库查询（流式）
     *
     * @param param 查询参数
     * @return 流式响应
     */
    @PostMapping(value = ["/api/knowledgebase/query/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun queryKnowledgeBaseStream(
        @Valid @RequestBody param: KnowledgeBaseService.QueryParam
    ): Flux<String> {
        logger.debug("知识库流式查询: kbIds={}, question={}", param.knowledgeBaseIds, param.question)
        return knowledgeBaseService.answerQuestionStream(param.knowledgeBaseIds, param.question)
    }

    /**
     * 获取所有分类
     *
     * @return 分类列表
     */
    @GetMapping("/api/knowledgebase/categories")
    fun getAllCategories(): ApiResult<List<String>> {
        return success(knowledgeBaseService.getAllCategories())
    }

    /**
     * 根据分类获取列表
     *
     * @param category 分类名称
     * @return 知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    fun getByCategory(@PathVariable category: String): ApiResult<List<KnowledgeBaseService.KnowledgeBaseListItemVo>> {
        return success(knowledgeBaseService.listByCategory(category))
    }

    /**
     * 获取未分类知识库
     *
     * @return 知识库列表
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    fun getUncategorized(): ApiResult<List<KnowledgeBaseService.KnowledgeBaseListItemVo>> {
        return success(knowledgeBaseService.listByCategory(null))
    }

    /**
     * 更新分类
     *
     * @param id 知识库ID
     * @param category 分类名称
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    fun updateCategory(
        @PathVariable id: Long,
        @RequestParam("category") category: String?
    ): ApiResult<Void> {
        knowledgeBaseService.updateCategory(id, category)
        return success()
    }

    /**
     * 上传知识库
     *
     * @param file 上传文件
     * @param name 知识库名称
     * @param category 分类
     * @return 上传结果
     */
    @PostMapping(value = ["/api/knowledgebase/upload"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadKnowledgeBase(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(value = "name", required = false) name: String?,
        @RequestParam(value = "category", required = false) category: String?
    ): ApiResult<KnowledgeBaseService.KnowledgeBaseUploadVo> {
        return success(knowledgeBaseService.uploadKnowledgeBase(file, name, category))
    }

    /**
     * 下载知识库文件
     *
     * @param id 知识库ID
     * @return 文件字节流
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    fun downloadKnowledgeBase(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val info = knowledgeBaseService.getEntityForDownload(id)
        val bytes = knowledgeBaseService.downloadFile(id)
        val encoded = URLEncoder.encode(info.originalFilename, StandardCharsets.UTF_8)
            .replace("+", "%20")
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"$encoded\"; filename*=UTF-8''$encoded"
            )
            .header(HttpHeaders.CONTENT_TYPE, info.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .body(bytes)
    }

    /**
     * 搜索知识库
     *
     * @param keyword 关键字
     * @return 搜索结果
     */
    @GetMapping("/api/knowledgebase/search")
    fun search(@RequestParam("keyword") keyword: String): ApiResult<List<KnowledgeBaseService.KnowledgeBaseListItemVo>> {
        return success(knowledgeBaseService.search(keyword))
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    fun getStatistics(): ApiResult<KnowledgeBaseService.KnowledgeBaseStatsVo> {
        return success(knowledgeBaseService.getStatistics())
    }

    /**
     * 重新向量化
     *
     * @param id 知识库ID
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    fun revectorize(@PathVariable id: Long): ApiResult<Void> {
        knowledgeBaseService.revectorize(id)
        return success()
    }
}

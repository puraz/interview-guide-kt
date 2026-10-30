package interview.guide.modules.knowledgebase

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemVo
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsVo
import interview.guide.modules.knowledgebase.model.QueryRequest
import interview.guide.modules.knowledgebase.model.QueryResponse
import interview.guide.modules.knowledgebase.model.VectorStatus
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
 */
@RestController
class KnowledgeBaseController(
    private val uploadService: KnowledgeBaseUploadService, // 知识库上传服务
    private val queryService: KnowledgeBaseQueryService, // 知识库查询服务
    private val listService: KnowledgeBaseListService, // 知识库列表服务
    private val deleteService: KnowledgeBaseDeleteService // 知识库删除服务
) {

    private val log = LoggerFactory.getLogger(KnowledgeBaseController::class.java)

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    fun getAllKnowledgeBases(
        @RequestParam(value = "sortBy", required = false) sortBy: String?,
        @RequestParam(value = "vectorStatus", required = false) vectorStatus: String?
    ): Result<List<KnowledgeBaseListItemVo>> {
        var status: VectorStatus? = null
        if (!vectorStatus.isNullOrBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.uppercase())
            } catch (e: IllegalArgumentException) {
                return Result.error("无效的向量化状态: $vectorStatus")
            }
        }

        return Result.success(listService.listKnowledgeBases(status, sortBy))
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    fun getKnowledgeBase(@PathVariable id: Long): Result<KnowledgeBaseListItemVo> {
        return listService.getKnowledgeBase(id)
            .map { vo -> Result.success(vo) }
            .orElse(Result.error("知识库不存在"))
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    fun deleteKnowledgeBase(@PathVariable id: Long): Result<Void> {
        deleteService.deleteKnowledgeBase(id)
        return Result.success(null)
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10.0)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10.0)
    fun queryKnowledgeBase(@Valid @RequestBody request: QueryRequest): Result<QueryResponse> {
        return Result.success(queryService.queryKnowledgeBase(request))
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     */
    @PostMapping(value = ["/api/knowledgebase/query/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5.0)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5.0)
    fun queryKnowledgeBaseStream(@Valid @RequestBody request: QueryRequest): Flux<String> {
        // Java 17 无虚拟线程 API（Java 25 的 Thread.isVirtual），这里固定记录 false
        log.debug(
            "收到知识库流式查询请求: kbIds={}, questionLength={}, 线程: {} (虚拟线程: {})",
            request.knowledgeBaseIds, request.question.length, Thread.currentThread(), false
        )
        return queryService.answerQuestionStream(request.knowledgeBaseIds, request.question)
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    fun getAllCategories(): Result<List<String>> {
        return Result.success(listService.getAllCategories())
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    fun getByCategory(@PathVariable category: String): Result<List<KnowledgeBaseListItemVo>> {
        return Result.success(listService.listByCategory(category))
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    fun getUncategorized(): Result<List<KnowledgeBaseListItemVo>> {
        return Result.success(listService.listByCategory(null))
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    fun updateCategory(@PathVariable id: Long, @RequestBody body: Map<String, String>): Result<Void> {
        listService.updateCategory(id, body["category"])
        return Result.success(null)
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件
     */
    @PostMapping(value = ["/api/knowledgebase/upload"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3.0)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3.0)
    fun uploadKnowledgeBase(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(value = "name", required = false) name: String?,
        @RequestParam(value = "category", required = false) category: String?
    ): Result<Map<String, Any?>> {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category))
    }

    /**
     * 下载知识库文件
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    fun downloadKnowledgeBase(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val entity = listService.getEntityForDownload(id)
        val fileContent = listService.downloadFile(id)

        val filename = entity.originalFilename ?: ""
        val encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
            .replace("\\+".toRegex(), "%20")

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename
            )
            .header(
                HttpHeaders.CONTENT_TYPE,
                entity.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
            )
            .body(fileContent)
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    fun search(@RequestParam("keyword") keyword: String): Result<List<KnowledgeBaseListItemVo>> {
        return Result.success(listService.search(keyword))
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    fun getStatistics(): Result<KnowledgeBaseStatsVo> {
        return Result.success(listService.getStatistics())
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * 用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2.0)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2.0)
    fun revectorize(@PathVariable id: Long): Result<Void> {
        uploadService.revectorize(id)
        return Result.success(null)
    }
}

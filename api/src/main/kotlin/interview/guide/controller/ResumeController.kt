package interview.guide.controller

import interview.guide.common.annotation.RateLimit
import interview.guide.common.result.Result
import interview.guide.service.ResumeDeleteService
import interview.guide.service.ResumeHistoryService
import interview.guide.service.ResumeListItemVo
import interview.guide.service.ResumeUploadService
import interview.guide.service.ResumeDetailVo
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 简历控制器
 * 处理简历上传、查询、导出等接口
 */
@RestController
class ResumeController(
    private val uploadService: ResumeUploadService, // 简历上传服务
    private val deleteService: ResumeDeleteService, // 简历删除服务
    private val historyService: ResumeHistoryService // 简历历史服务
) {

    private val log = LoggerFactory.getLogger(ResumeController::class.java)

    /**
     * 上传简历并获取分析结果
     *
     * @param file 简历文件 // 支持 PDF、DOCX、DOC、TXT 等
     * @return 分析结果 // 返回分析任务或历史结果
     */
    @PostMapping(value = ["/api/resumes/upload"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RateLimit(dimensions = [RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP], count = 5.0)
    fun uploadAndAnalyze(@RequestParam("file") file: MultipartFile): Result<Map<String, Any>> {
        val result = uploadService.uploadAndAnalyze(file)
        val isDuplicate = result["duplicate"] as? Boolean ?: false
        return if (isDuplicate) {
            Result.success("检测到相同简历，已返回历史分析结果", result)
        } else {
            Result.success(result)
        }
    }

    /**
     * 获取所有简历列表
     */
    @GetMapping("/api/resumes")
    fun getAllResumes(): Result<List<ResumeListItemVo>> {
        val resumes = historyService.getAllResumes()
        return Result.success(resumes)
    }

    /**
     * 获取简历详情（包含分析历史）
     *
     * @param id 简历ID // 简历主键
     */
    @GetMapping("/api/resumes/{id}/detail")
    fun getResumeDetail(@PathVariable id: Long): Result<ResumeDetailVo> {
        val detail = historyService.getResumeDetail(id)
        return Result.success(detail)
    }

    /**
     * 导出简历分析报告为PDF
     *
     * @param id 简历ID // 简历主键
     */
    @GetMapping("/api/resumes/{id}/export")
    fun exportAnalysisPdf(@PathVariable id: Long): ResponseEntity<ByteArray> {
        return try {
            val result = historyService.exportAnalysisPdf(id)
            val filename = URLEncoder.encode(result.filename, StandardCharsets.UTF_8)
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''$filename")
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdfBytes)
        } catch (e: Exception) {
            log.error("导出PDF失败: resumeId={}", id, e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * 删除简历
     *
     * @param id 简历ID // 简历主键
     */
    @DeleteMapping("/api/resumes/{id}")
    fun deleteResume(@PathVariable id: Long): Result<Void> {
        deleteService.deleteResume(id)
        return Result.success(null)
    }

    /**
     * 重新分析简历（手动重试）
     *
     * @param id 简历ID // 简历主键
     */
    @PostMapping("/api/resumes/{id}/reanalyze")
    @RateLimit(dimensions = [RateLimit.Dimension.GLOBAL, RateLimit.Dimension.IP], count = 2.0)
    fun reanalyze(@PathVariable id: Long): Result<Void> {
        uploadService.reanalyze(id)
        return Result.success(null)
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/api/resumes/health")
    fun health(): Result<Map<String, String>> {
        return Result.success(
            mapOf(
                "status" to "UP",
                "service" to "AI Interview Platform - Resume Service"
            )
        )
    }
}

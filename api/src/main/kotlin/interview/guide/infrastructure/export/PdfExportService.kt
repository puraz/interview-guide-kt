package interview.guide.infrastructure.export

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfOutline
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.navigation.PdfStringDestination
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.font.FontProvider
import com.itextpdf.layout.font.selectorstrategy.BestMatchFontSelectorStrategy.BestMatchFontSelectorStrategyFactory
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.modules.interview.model.InterviewAnswerEntity
import interview.guide.modules.interview.model.InterviewSessionEntity
import interview.guide.modules.interview.model.ResumeAnalysisVo
import interview.guide.modules.resume.model.ResumeEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * PDF导出服务
 * PDF Export Service for resume analysis and interview reports
 */
@Service
class PdfExportService(
    private val objectMapper: ObjectMapper // JSON 序列化工具
) {

    private val log = LoggerFactory.getLogger(PdfExportService::class.java)

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") // 日期格式
        private val HEADER_COLOR = DeviceRgb(41, 128, 185) // 标题颜色
        private val SECTION_COLOR = DeviceRgb(52, 73, 94) // 小节颜色
        private const val QUESTIONS_DESTINATION: String = "interview-questions" // 问答详情书签目标
    }

    /**
     * 使用中文字体，并为其缺少的带圈编号等字符提供内嵌回退字体。
     */
    private fun configureFonts(document: Document) {
        val fontProvider = FontProvider()
        fontProvider.setFontSelectorStrategyFactory(BestMatchFontSelectorStrategyFactory())
        registerFont(fontProvider, "fonts/ZhuqueFangsong-Regular.ttf", "report-cjk")
        registerFont(fontProvider, "fonts/NotoSansSymbols-Regular.ttf", "report-symbols")
        document.setFontProvider(fontProvider)
        document.setFontFamily("report-cjk", "report-symbols")
    }

    /**
     * 注册内嵌字体到 FontProvider
     */
    private fun registerFont(fontProvider: FontProvider, resource: String, alias: String) {
        try {
            val fontStream = javaClass.classLoader.getResourceAsStream(resource)
            if (fontStream != null) {
                fontStream.use { stream ->
                    if (!fontProvider.fontSet.addFont(
                            stream.readAllBytes(), PdfEncodings.IDENTITY_H, alias
                        )
                    ) {
                        throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "字体文件无效，请联系管理员")
                    }
                }
                log.debug("使用项目内嵌字体: {}", resource)
                return
            }

            log.error("未找到字体文件: {}", resource)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "字体文件缺失，请联系管理员")
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("创建中文字体失败: {}", e.message, e)
            throw BusinessException(ErrorCode.EXPORT_PDF_FAILED, "创建字体失败: ${e.message}")
        }
    }

    /**
     * 清理文本中可能导致字体问题的字符
     */
    private fun sanitizeText(text: String?): String {
        if (text == null) return ""
        // 移除可能导致问题的特殊字符（如 emoji）
        return text.replace(Regex("[\\p{So}\\p{Cs}]"), "").trim()
    }

    /**
     * 导出简历分析报告为PDF
     */
    fun exportResumeAnalysis(resume: ResumeEntity, analysis: ResumeAnalysisVo): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = PdfWriter(baos)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)

        // 使用支持中文的字体
        configureFonts(document)

        // 标题
        val title = Paragraph("简历分析报告")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR)
        document.add(title)

        // 基本信息
        document.add(Paragraph("\n"))
        document.add(createSectionTitle("基本信息"))
        document.add(Paragraph("文件名: ${resume.originalFilename}"))
        document.add(
            Paragraph(
                "上传时间: " + (resume.uploadedAt?.let { DATE_FORMAT.format(it) } ?: "未知")
            )
        )

        // 总分
        document.add(Paragraph("\n"))
        document.add(createSectionTitle("综合评分"))
        val overallScoreValue = analysis.overallScore ?: 0
        val scoreP = Paragraph("总分: $overallScoreValue / 100")
            .setFontSize(18f)
            .setBold()
            .setFontColor(getScoreColor(overallScoreValue))
        document.add(scoreP)

        // 各维度评分
        val scoreDetail = analysis.scoreDetail
        if (scoreDetail != null) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("各维度评分"))

            val scoreTable = Table(UnitValue.createPercentArray(floatArrayOf(2f, 1f)))
                .useAllAvailableWidth()
            addScoreRow(scoreTable, "项目经验", scoreDetail.projectScore, 40)
            addScoreRow(scoreTable, "技能匹配度", scoreDetail.skillMatchScore, 20)
            addScoreRow(scoreTable, "内容完整性", scoreDetail.contentScore, 15)
            addScoreRow(scoreTable, "结构清晰度", scoreDetail.structureScore, 15)
            addScoreRow(scoreTable, "表达专业性", scoreDetail.expressionScore, 10)
            document.add(scoreTable)
        }

        // 简历摘要
        if (analysis.summary != null) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("简历摘要"))
            document.add(Paragraph(sanitizeText(analysis.summary)))
        }

        // 优势亮点
        if (!analysis.strengths.isNullOrEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("优势亮点"))
            for (strength in analysis.strengths ?: emptyList()) {
                document.add(Paragraph("• ${sanitizeText(strength)}"))
            }
        }

        // 改进建议
        if (!analysis.suggestions.isNullOrEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("改进建议"))
            for (suggestion in analysis.suggestions ?: emptyList()) {
                document.add(
                    Paragraph("【${suggestion.priority}】${sanitizeText(suggestion.category)}")
                        .setBold()
                )
                document.add(Paragraph("问题: ${sanitizeText(suggestion.issue)}"))
                document.add(Paragraph("建议: ${sanitizeText(suggestion.recommendation)}"))
                document.add(Paragraph("\n"))
            }
        }

        document.close()
        return baos.toByteArray()
    }

    /**
     * 导出面试报告为PDF
     */
    fun exportInterviewReport(session: InterviewSessionEntity): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = PdfWriter(baos)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)

        // 使用支持中文的字体
        configureFonts(document)

        // 标题
        val title = Paragraph("模拟面试报告")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR)
        document.add(title)

        // 基本信息
        document.add(Paragraph("\n"))
        document.add(createSectionTitle("面试信息"))
        document.add(Paragraph("会话ID: ${session.sessionId}"))
        document.add(Paragraph("题目数量: ${session.totalQuestions}"))
        document.add(Paragraph("面试状态: ${getStatusText(session.status)}"))
        document.add(
            Paragraph(
                "开始时间: " + (session.createdAt?.let { DATE_FORMAT.format(it) } ?: "未知")
            )
        )
        if (session.completedAt != null) {
            document.add(Paragraph("完成时间: ${DATE_FORMAT.format(session.completedAt)}"))
        }

        // 总分
        val overallScore = session.overallScore
        if (overallScore != null) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("综合评分"))
            val scoreP = Paragraph("总分: $overallScore / 100")
                .setFontSize(18f)
                .setBold()
                .setFontColor(getScoreColor(overallScore))
            document.add(scoreP)
        }

        // 总体评价
        if (session.overallFeedback != null) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("总体评价"))
            document.add(Paragraph(sanitizeText(session.overallFeedback)))
        }

        // 优势
        if (session.strengthsJson != null) {
            try {
                val strengths: List<String> = objectMapper.readValue(
                    session.strengthsJson,
                    object : TypeReference<List<String>>() {}
                )
                if (strengths.isNotEmpty()) {
                    document.add(Paragraph("\n"))
                    document.add(createSectionTitle("表现优势"))
                    for (s in strengths) {
                        document.add(Paragraph("• ${sanitizeText(s)}"))
                    }
                }
            } catch (e: Exception) {
                log.error("解析优势JSON失败: sessionId={}", session.sessionId, e)
            }
        }

        // 改进建议
        if (session.improvementsJson != null) {
            try {
                val improvements: List<String> = objectMapper.readValue(
                    session.improvementsJson,
                    object : TypeReference<List<String>>() {}
                )
                if (improvements.isNotEmpty()) {
                    document.add(Paragraph("\n"))
                    document.add(createSectionTitle("改进建议"))
                    for (s in improvements) {
                        document.add(Paragraph("• ${sanitizeText(s)}"))
                    }
                }
            } catch (e: Exception) {
                log.error("解析改进建议JSON失败: sessionId={}", session.sessionId, e)
            }
        }

        // 问答详情（带书签导航）
        val answers: List<InterviewAnswerEntity> = session.answers
        if (answers.isNotEmpty()) {
            val questions: PdfOutline = pdfDoc.getOutlines(false).addOutline("问答详情")
            questions.addDestination(PdfStringDestination(QUESTIONS_DESTINATION))
            questions.setOpen(true)
            pdfDoc.getCatalog().setPageMode(PdfName.UseOutlines)
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("问答详情").setDestination(QUESTIONS_DESTINATION))
            var bookmarkIndex = 0

            for (answer in answers.sortedBy { it.questionIndex ?: 0 }) {
                val destination = "interview-question-" + bookmarkIndex++
                questions.addOutline(questionBookmarkTitle(answer))
                    .addDestination(PdfStringDestination(destination))
                document.add(Paragraph("\n"))
                document.add(
                    Paragraph(
                        "问题 " + ((answer.questionIndex ?: 0) + 1) +
                            " [" + (answer.category ?: "综合") + "]"
                    )
                        .setBold()
                        .setFontSize(12f)
                        .setKeepWithNext(true)
                        .setDestination(destination)
                )
                document.add(Paragraph("Q: ${sanitizeText(answer.question)}"))
                document.add(
                    Paragraph("A: " + sanitizeText(answer.userAnswer ?: "未回答"))
                )
                document.add(
                    Paragraph("得分: ${answer.score ?: 0}/100")
                        .setFontColor(getScoreColor(answer.score ?: 0))
                )
                if (answer.feedback != null) {
                    document.add(
                        Paragraph("评价: ${sanitizeText(answer.feedback)}")
                            .setItalic()
                    )
                }
                if (answer.referenceAnswer != null) {
                    document.add(
                        Paragraph("参考答案: ${sanitizeText(answer.referenceAnswer)}")
                            .setFontColor(DeviceRgb(39, 174, 96))
                    )
                }
            }
        }

        document.close()
        return baos.toByteArray()
    }

    /**
     * 构建题目书签标题（问题 N：题面）
     */
    private fun questionBookmarkTitle(answer: InterviewAnswerEntity): String {
        val number = "问题 " + ((answer.questionIndex ?: 0) + 1)
        val question = answer.question
            ?.replace(Regex("(?U)\\s+"), " ")
            ?.trim() ?: ""
        return if (question.isEmpty()) number else "$number：$question"
    }

    /**
     * 创建小节标题
     */
    private fun createSectionTitle(title: String): Paragraph {
        return Paragraph(title)
            .setFontSize(14f)
            .setBold()
            .setFontColor(SECTION_COLOR)
            .setMarginTop(10f)
    }

    /**
     * 追加评分行
     */
    private fun addScoreRow(table: Table, dimension: String, score: Int, maxScore: Int) {
        table.addCell(Cell().add(Paragraph(dimension)))
        table.addCell(
            Cell().add(
                Paragraph("$score / $maxScore")
                    .setFontColor(getScoreColor(score * 100 / maxScore))
            )
        )
    }

    /**
     * 按分数区间返回颜色
     */
    private fun getScoreColor(score: Int): DeviceRgb {
        if (score >= 80) return DeviceRgb(39, 174, 96) // 绿色
        if (score >= 60) return DeviceRgb(241, 196, 15) // 黄色
        return DeviceRgb(231, 76, 60) // 红色
    }

    /**
     * 会话状态转展示文案
     */
    private fun getStatusText(status: InterviewSessionEntity.SessionStatus?): String {
        return when (status) {
            InterviewSessionEntity.SessionStatus.CREATED -> "已创建"
            InterviewSessionEntity.SessionStatus.IN_PROGRESS -> "进行中"
            InterviewSessionEntity.SessionStatus.COMPLETED -> "已完成"
            InterviewSessionEntity.SessionStatus.EVALUATED -> "已评估"
            null -> "未知"
        }
    }
}

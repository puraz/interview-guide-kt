package interview.guide.infrastructure.export

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import interview.guide.common.exception.BusinessException
import interview.guide.common.exception.ErrorCode
import interview.guide.entity.InterviewAnswerEntity
import interview.guide.entity.InterviewSessionEntity
import interview.guide.entity.ResumeEntity
import interview.guide.service.ResumeAnalysisVo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * PDF导出服务
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
    }

    /**
     * 创建支持中文的字体
     */
    private fun createChineseFont(): PdfFont {
        try {
            val fontStream = this::class.java.classLoader.getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")
            if (fontStream != null) {
                val fontBytes = fontStream.readBytes()
                fontStream.close()
                log.debug("使用项目内嵌字体: fonts/ZhuqueFangsong-Regular.ttf")
                return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
            }
            log.error("未找到字体文件: fonts/ZhuqueFangsong-Regular.ttf")
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
        return text.replace(Regex("[\\p{So}\\p{Cs}]") , "").trim()
    }

    /**
     * 导出简历分析报告为PDF
     *
     * @param resume 简历实体 // 简历基础信息
     * @param analysis 简历分析结果 // AI 分析结果
     * @return PDF 字节数组 // 导出结果
     */
    fun exportResumeAnalysis(resume: ResumeEntity, analysis: ResumeAnalysisVo): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = PdfWriter(baos)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)

        val font = createChineseFont()
        document.setFont(font)

        val title = Paragraph("简历分析报告")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR)
        document.add(title)

        document.add(Paragraph("\n"))
        document.add(createSectionTitle("基本信息"))
        document.add(Paragraph("文件名: ${resume.originalFilename}"))
        document.add(Paragraph("上传时间: ${resume.uploadedAt?.let { DATE_FORMAT.format(it) } ?: "未知"}"))

        document.add(Paragraph("\n"))
        document.add(createSectionTitle("综合评分"))
        val scoreP = Paragraph("总分: ${analysis.overallScore} / 100")
            .setFontSize(18f)
            .setBold()
            .setFontColor(getScoreColor(analysis.overallScore))
        document.add(scoreP)

        analysis.scoreDetail?.let { detail ->
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("各维度评分"))

            val scoreTable = Table(UnitValue.createPercentArray(floatArrayOf(2f, 1f))).useAllAvailableWidth()
            addScoreRow(scoreTable, "项目经验", detail.projectScore, 40)
            addScoreRow(scoreTable, "技能匹配度", detail.skillMatchScore, 20)
            addScoreRow(scoreTable, "内容完整性", detail.contentScore, 15)
            addScoreRow(scoreTable, "结构清晰度", detail.structureScore, 15)
            addScoreRow(scoreTable, "表达专业性", detail.expressionScore, 10)
            document.add(scoreTable)
        }

        analysis.summary?.let {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("简历摘要"))
            document.add(Paragraph(sanitizeText(it)))
        }

        if (!analysis.strengths.isNullOrEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("优势亮点"))
            analysis.strengths!!.forEach { strength ->
                document.add(Paragraph("• ${sanitizeText(strength)}"))
            }
        }

        if (!analysis.suggestions.isNullOrEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("改进建议"))
            analysis.suggestions!!.forEach { suggestion ->
                document.add(Paragraph("【${suggestion.priority}】${sanitizeText(suggestion.category)}")
                    .setBold())
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

        val font = createChineseFont()
        document.setFont(font)

        val title = Paragraph("模拟面试报告")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR)
        document.add(title)

        document.add(Paragraph("\n"))
        document.add(createSectionTitle("面试信息"))
        document.add(Paragraph("会话ID: ${session.sessionId}"))
        document.add(Paragraph("题目数量: ${session.totalQuestions}"))
        document.add(Paragraph("面试状态: ${getStatusText(session.status)}"))
        document.add(Paragraph("开始时间: ${session.createdAt?.let { DATE_FORMAT.format(it) } ?: "未知"}"))
        if (session.completedAt != null) {
            document.add(Paragraph("完成时间: ${DATE_FORMAT.format(session.completedAt)}"))
        }

        session.overallScore?.let { score ->
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("综合评分"))
            val scoreP = Paragraph("总分: $score / 100")
                .setFontSize(18f)
                .setBold()
                .setFontColor(getScoreColor(score))
            document.add(scoreP)
        }

        session.overallFeedback?.let {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("总体评价"))
            document.add(Paragraph(sanitizeText(it)))
        }

        session.strengthsJson?.let { json ->
            try {
                val strengths: List<String> = objectMapper.readValue(json, object : TypeReference<List<String>>() {})
                if (strengths.isNotEmpty()) {
                    document.add(Paragraph("\n"))
                    document.add(createSectionTitle("表现优势"))
                    strengths.forEach { s ->
                        document.add(Paragraph("• ${sanitizeText(s)}"))
                    }
                }
            } catch (e: Exception) {
                log.error("解析优势JSON失败", e)
            }
        }

        session.improvementsJson?.let { json ->
            try {
                val improvements: List<String> = objectMapper.readValue(json, object : TypeReference<List<String>>() {})
                if (improvements.isNotEmpty()) {
                    document.add(Paragraph("\n"))
                    document.add(createSectionTitle("改进建议"))
                    improvements.forEach { s ->
                        document.add(Paragraph("• ${sanitizeText(s)}"))
                    }
                }
            } catch (e: Exception) {
                log.error("解析改进建议JSON失败", e)
            }
        }

        val answers: List<InterviewAnswerEntity> = session.answers ?: emptyList()
        if (answers.isNotEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("问答详情"))

            for (answer in answers) {
                document.add(Paragraph("\n"))
                document.add(
            Paragraph("问题 ${(answer.questionIndex ?: 0) + 1} [${answer.category ?: "综合"}]")
                        .setBold()
                        .setFontSize(12f)
                )
                document.add(Paragraph("Q: ${sanitizeText(answer.question)}"))
                document.add(Paragraph("A: ${sanitizeText(answer.userAnswer ?: "未回答")}"))
                document.add(Paragraph("得分: ${answer.score}/100").setFontColor(getScoreColor(answer.score ?: 0)))
                if (answer.feedback != null) {
                    document.add(Paragraph("评价: ${sanitizeText(answer.feedback)}").setItalic())
                }
                if (answer.referenceAnswer != null) {
                    document.add(Paragraph("参考答案: ${sanitizeText(answer.referenceAnswer)}")
                        .setFontColor(DeviceRgb(39, 174, 96)))
                }
            }
        }

        document.close()
        return baos.toByteArray()
    }

    private fun createSectionTitle(title: String): Paragraph {
        return Paragraph(title)
            .setFontSize(14f)
            .setBold()
            .setFontColor(SECTION_COLOR)
            .setMarginTop(10f)
    }

    private fun addScoreRow(table: Table, dimension: String, score: Int, maxScore: Int) {
        table.addCell(Cell().add(Paragraph(dimension)))
        table.addCell(Cell().add(Paragraph("$score / $maxScore")
            .setFontColor(getScoreColor(score * 100 / maxScore))))
    }

    private fun getScoreColor(score: Int): DeviceRgb {
        return when {
            score >= 80 -> DeviceRgb(39, 174, 96)
            score >= 60 -> DeviceRgb(241, 196, 15)
            else -> DeviceRgb(231, 76, 60)
        }
    }

    private fun getStatusText(status: InterviewSessionEntity.SessionStatus?): String {
        return when (status) {
            InterviewSessionEntity.SessionStatus.CREATED -> "已创建"
            InterviewSessionEntity.SessionStatus.IN_PROGRESS -> "进行中"
            InterviewSessionEntity.SessionStatus.COMPLETED -> "已完成"
            InterviewSessionEntity.SessionStatus.EVALUATED -> "已评估"
            else -> "未知"
        }
    }
}

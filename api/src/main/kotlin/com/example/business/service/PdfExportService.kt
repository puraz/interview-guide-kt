package com.example.business.service

import com.example.business.entity.InterviewAnswerEntity
import com.example.business.entity.InterviewSessionEntity
import com.example.framework.core.exception.BusinessException
import com.example.framework.core.utils.JSONUtil
import com.fasterxml.jackson.core.type.TypeReference
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * PDF导出服务
 *
 * 负责导出面试报告PDF。
 */
@Service
class PdfExportService {
    private val logger = LoggerFactory.getLogger(PdfExportService::class.java)

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val headerColor = DeviceRgb(41, 128, 185)
    private val sectionColor = DeviceRgb(52, 73, 94)

    /**
     * 导出面试报告为PDF
     *
     * @param session 面试会话
     * @param answers 面试答案列表
     * @return PDF字节
     */
    fun exportInterviewReport(session: InterviewSessionEntity, answers: List<InterviewAnswerEntity>): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val writer = PdfWriter(outputStream)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc)

        val font = createChineseFont()
        document.setFont(font)

        val title = Paragraph("模拟面试报告")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(headerColor)
        document.add(title)

        document.add(Paragraph("\n"))
        document.add(createSectionTitle("面试信息"))
        document.add(Paragraph("会话ID: ${session.sessionId}"))
        document.add(Paragraph("题目数量: ${session.totalQuestions ?: 0}"))
        document.add(Paragraph("面试状态: ${getStatusText(session.status)}"))
        document.add(Paragraph("开始时间: ${dateFormat.format(session.createdAt)}"))
        if (session.completedAt != null) {
            document.add(Paragraph("完成时间: ${dateFormat.format(session.completedAt)}"))
        }

        session.overallScore?.let { overallScore ->
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("综合评分"))
            val scoreP = Paragraph("总分: $overallScore / 100")
                .setFontSize(18f)
                .setBold()
                .setFontColor(getScoreColor(overallScore))
            document.add(scoreP)
        }

        if (!session.overallFeedback.isNullOrBlank()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("总体评价"))
            document.add(Paragraph(sanitizeText(session.overallFeedback)))
        }

        session.strengthsJson?.let {
            val strengths = parseList(it)
            if (strengths.isNotEmpty()) {
                document.add(Paragraph("\n"))
                document.add(createSectionTitle("表现优势"))
                strengths.forEach { s ->
                    document.add(Paragraph("• ${sanitizeText(s)}"))
                }
            }
        }

        session.improvementsJson?.let {
            val improvements = parseList(it)
            if (improvements.isNotEmpty()) {
                document.add(Paragraph("\n"))
                document.add(createSectionTitle("改进建议"))
                improvements.forEach { s ->
                    document.add(Paragraph("• ${sanitizeText(s)}"))
                }
            }
        }

        if (answers.isNotEmpty()) {
            document.add(Paragraph("\n"))
            document.add(createSectionTitle("问答详情"))
            for (answer in answers) {
                document.add(Paragraph("\n"))
                val questionIndex = answer.questionIndex ?: 0
                val titleText = "问题 ${questionIndex + 1} [${answer.category ?: "综合"}]"
                document.add(Paragraph(titleText).setBold().setFontSize(12f))
                document.add(Paragraph("Q: ${sanitizeText(answer.question)}"))
                val userAnswer = answer.userAnswer ?: "未回答"
                document.add(Paragraph("A: ${sanitizeText(userAnswer)}"))
                document.add(
                    Paragraph("得分: ${answer.score ?: 0} / 100")
                        .setFontColor(getScoreColor(answer.score ?: 0))
                )
                if (!answer.feedback.isNullOrBlank()) {
                    document.add(Paragraph("评价: ${sanitizeText(answer.feedback)}").setItalic())
                }
                if (!answer.referenceAnswer.isNullOrBlank()) {
                    document.add(
                        Paragraph("参考答案: ${sanitizeText(answer.referenceAnswer)}")
                            .setFontColor(DeviceRgb(39, 174, 96))
                    )
                }
            }
        }

        document.close()
        return outputStream.toByteArray()
    }

    private fun createChineseFont(): PdfFont {
        try {
            val stream = javaClass.classLoader.getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")
                ?: throw BusinessException("字体文件缺失，请联系管理员")
            val fontBytes = stream.use { it.readBytes() }
            return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
        } catch (ex: BusinessException) {
            throw ex
        } catch (ex: Exception) {
            logger.error("创建中文字体失败", ex)
            throw BusinessException("创建字体失败: ${ex.message}")
        }
    }

    private fun sanitizeText(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }
        return text.replace(Regex("[\\p{So}\\p{Cs}]"), "").trim()
    }

    private fun parseList(json: String): List<String> {
        return JSONUtil.parseObject(json, object : TypeReference<List<String>>() {}) ?: emptyList()
    }

    private fun createSectionTitle(title: String): Paragraph {
        return Paragraph(title)
            .setFontSize(14f)
            .setBold()
            .setFontColor(sectionColor)
            .setMarginTop(10f)
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

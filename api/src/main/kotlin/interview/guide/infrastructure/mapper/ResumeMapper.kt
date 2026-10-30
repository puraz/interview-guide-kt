package interview.guide.infrastructure.mapper

import interview.guide.modules.interview.model.ResumeAnalysisVo
import interview.guide.modules.resume.model.ResumeAnalysisEntity
import interview.guide.modules.resume.model.ResumeDetailVo
import interview.guide.modules.resume.model.ResumeEntity
import interview.guide.modules.resume.model.ResumeListItemVo
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 简历相关的对象映射器（原 MapStruct 接口改为手写映射）
 *
 * 注意：JSON字段(strengthsJson, suggestionsJson)需要在Service层手动处理
 */
@Component
class ResumeMapper {

    // ========== ScoreDetail 映射 ==========

    /**
     * 将实体基础字段映射到VO的ScoreDetail（null 按 0 处理）
     *
     * @param entity 分析实体 // 数据库记录
     * @return 各维度评分 // VO 对象
     */
    fun toScoreDetail(entity: ResumeAnalysisEntity): ResumeAnalysisVo.ScoreDetailVo {
        return ResumeAnalysisVo.ScoreDetailVo(
            nullToZero(entity.contentScore),
            nullToZero(entity.structureScore),
            nullToZero(entity.skillMatchScore),
            nullToZero(entity.expressionScore),
            nullToZero(entity.projectScore)
        )
    }

    // ========== ResumeListItemVo 映射 ==========

    /**
     * ResumeEntity 转换为 ResumeListItemVo
     * 需要额外传入 latestScore, lastAnalyzedAt, interviewCount
     *
     * @param resume         简历实体 // 基础信息
     * @param latestScore    最新总分 // 可为 null
     * @param lastAnalyzedAt 最近分析时间 // 可为 null
     * @param interviewCount 面试次数 // 关联统计
     * @return 简历列表项 // VO 对象
     */
    fun toListItemVo(
        resume: ResumeEntity,
        latestScore: Int?,
        lastAnalyzedAt: LocalDateTime?,
        interviewCount: Int
    ): ResumeListItemVo {
        return ResumeListItemVo(
            resume.id,
            resume.originalFilename,
            resume.fileSize,
            resume.uploadedAt,
            resume.accessCount,
            latestScore,
            lastAnalyzedAt,
            interviewCount,
            null,
            null
        )
    }

    /**
     * 简化版：从 ResumeEntity 直接映射（其他字段为 null）
     *
     * @param entity 简历实体 // 基础信息
     * @return 简历列表项 // VO 对象
     */
    fun toListItemVoBasic(entity: ResumeEntity): ResumeListItemVo {
        return ResumeListItemVo(
            entity.id,
            entity.originalFilename,
            entity.fileSize,
            entity.uploadedAt,
            entity.accessCount,
            null,
            null,
            0,
            null,
            null
        )
    }

    // ========== ResumeDetailVo 映射 ==========

    /**
     * ResumeEntity 转换为 ResumeDetailVo（不含 analyses 和 interviews）
     *
     * @param entity 简历实体 // 基础信息
     * @return 简历详情 // VO 对象
     */
    fun toDetailVoBasic(entity: ResumeEntity): ResumeDetailVo {
        return ResumeDetailVo(
            entity.id,
            entity.originalFilename,
            entity.fileSize,
            entity.contentType,
            entity.storageUrl,
            entity.uploadedAt,
            entity.accessCount,
            entity.resumeText,
            entity.analyzeStatus,
            entity.analyzeError,
            emptyList(),
            emptyList()
        )
    }

    // ========== AnalysisHistoryVo 映射 ==========

    /**
     * ResumeAnalysisEntity 转换为 AnalysisHistoryVo
     * 注意：strengths 和 suggestions 需要在 Service 层从 JSON 解析后传入
     *
     * @param entity      分析实体 // 数据库记录
     * @param strengths   优点列表 // 已解析的 JSON
     * @param suggestions 改进建议列表 // 已解析的 JSON
     * @return 分析历史项 // VO 对象
     */
    fun toAnalysisHistoryVo(
        entity: ResumeAnalysisEntity,
        strengths: List<String>,
        suggestions: List<Any>
    ): ResumeDetailVo.AnalysisHistoryVo {
        return ResumeDetailVo.AnalysisHistoryVo(
            entity.id,
            entity.overallScore,
            entity.contentScore,
            entity.structureScore,
            entity.skillMatchScore,
            entity.expressionScore,
            entity.projectScore,
            entity.summary,
            entity.analyzedAt,
            strengths,
            suggestions
        )
    }

    /**
     * 批量转换（需要在 Service 层处理 JSON）
     *
     * @param entities            分析实体列表 // 数据库记录
     * @param strengthsExtractor  优点解析函数 // JSON → 列表
     * @param suggestionsExtractor 建议解析函数 // JSON → 列表
     * @return 分析历史列表 // VO 列表
     */
    fun toAnalysisHistoryVoList(
        entities: List<ResumeAnalysisEntity>,
        strengthsExtractor: (ResumeAnalysisEntity) -> List<String>,
        suggestionsExtractor: (ResumeAnalysisEntity) -> List<Any>
    ): List<ResumeDetailVo.AnalysisHistoryVo> {
        return entities.map { entity ->
            toAnalysisHistoryVo(entity, strengthsExtractor(entity), suggestionsExtractor(entity))
        }
    }

    // ========== ResumeAnalysisEntity 创建映射 ==========

    /**
     * 从 ResumeAnalysisVo 创建 ResumeAnalysisEntity
     * 注意：JSON 字段和 Resume 关联需要在 Service 层设置
     *
     * @param response 分析结果 // AI 返回对象
     * @return 分析实体 // 待持久化对象
     */
    fun toAnalysisEntity(response: ResumeAnalysisVo): ResumeAnalysisEntity {
        val entity = ResumeAnalysisEntity()
        entity.overallScore = response.overallScore
        entity.contentScore = response.scoreDetail?.contentScore
        entity.structureScore = response.scoreDetail?.structureScore
        entity.skillMatchScore = response.scoreDetail?.skillMatchScore
        entity.expressionScore = response.scoreDetail?.expressionScore
        entity.projectScore = response.scoreDetail?.projectScore
        entity.summary = response.summary
        return entity
    }

    /**
     * 更新已有的 ResumeAnalysisEntity
     *
     * @param response 分析结果 // AI 返回对象
     * @param entity   待更新实体 // 数据库记录
     */
    fun updateAnalysisEntity(response: ResumeAnalysisVo, entity: ResumeAnalysisEntity) {
        entity.overallScore = response.overallScore
        entity.contentScore = response.scoreDetail?.contentScore
        entity.structureScore = response.scoreDetail?.structureScore
        entity.skillMatchScore = response.scoreDetail?.skillMatchScore
        entity.expressionScore = response.scoreDetail?.expressionScore
        entity.projectScore = response.scoreDetail?.projectScore
        entity.summary = response.summary
    }

    // ========== 工具方法 ==========

    /**
     * null 转为 0
     *
     * @param value 原始值 // 可能为 null
     * @return 非空数值 // null 时为 0
     */
    fun nullToZero(value: Int?): Int {
        return value ?: 0
    }
}

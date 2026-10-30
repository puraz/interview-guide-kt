package interview.guide.modules.knowledgebase.metrics

import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * RAG 业务指标统一封装（P1-04）。
 *
 * 固定 Meter 名称与有限标签，Service 不直接接触 MeterRegistry。
 * 标签只允许 mode/result/stage/variant/reason 五个低基数键，
 * 禁止用户 ID、会话 ID、知识库 ID、问题文本、异常消息与 Provider URL。
 *
 * 关闭开关（app.ai.rag.metrics-enabled=false）或 Registry 缺失时全部 no-op。
 * 底层模型 HTTP 指标已由 Spring AI Observation 覆盖，这里只补业务阶段。
 */
@Component
class RagMetrics(
    registryProvider: ObjectProvider<MeterRegistry>?, // 指标注册表提供者
    queryProperties: KnowledgeBaseQueryProperties // RAG 配置（指标开关）
) {

    private val registry: MeterRegistry? = registryProvider?.getIfAvailable() // 指标注册表

    private val enabled: Boolean = queryProperties.metricsEnabled && registry != null // 是否启用指标

    fun enabled(): Boolean {
        return enabled
    }

    /** 请求结果：mode=sync|stream，result=success|reject|error|cancel，一次请求只允许一次 */
    fun recordRequest(mode: String, result: String) {
        val reg = registry
        if (enabled && reg != null) {
            reg.counter(REQUESTS, "mode", mode, "result", result).increment()
        }
    }

    /** 阶段耗时：stage=rewrite|retrieve|generate|total */
    fun recordStageDuration(stage: String, result: String, durationNanos: Long) {
        val reg = registry
        if (enabled && reg != null) {
            Timer.builder(STAGE_DURATION)
                .tag("stage", stage)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(reg)
                .record(Duration.ofNanos(durationNanos))
        }
    }

    /** 检索命中数分布：variant=rewritten|original|single|merged */
    fun recordRetrievalHits(variant: String, hits: Int) {
        val reg = registry
        if (enabled && reg != null) {
            DistributionSummary.builder(RETRIEVAL_HITS)
                .tag("variant", variant)
                .register(reg)
                .record(hits.toDouble())
        }
    }

    /** 改写回退：reason=disabled|blank|error|unchanged；成功改写不记 */
    fun recordRewriteFallback(reason: String) {
        val reg = registry
        if (enabled && reg != null) {
            reg.counter(REWRITE_FALLBACKS, "reason", reason).increment()
        }
    }

    /** 拒答：reason=invalid_request|no_hit；异常与取消不记 */
    fun recordRefusal(reason: String) {
        val reg = registry
        if (enabled && reg != null) {
            reg.counter(REFUSALS, "reason", reason).increment()
        }
    }

    companion object {
        const val REQUESTS: String = "app.rag.requests"
        const val STAGE_DURATION: String = "app.rag.stage.duration"
        const val RETRIEVAL_HITS: String = "app.rag.retrieval.hits"
        const val REWRITE_FALLBACKS: String = "app.rag.rewrite.fallbacks"
        const val REFUSALS: String = "app.rag.refusals"
    }
}

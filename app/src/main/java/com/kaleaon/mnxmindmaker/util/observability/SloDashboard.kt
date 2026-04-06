package com.kaleaon.mnxmindmaker.util.observability

import com.kaleaon.mnxmindmaker.util.eval.BenchmarkSuiteResult
import java.text.DecimalFormat

data class SloTargets(
    val maxP95LatencyMs: Long = 4_000,
    val minSuccessRate: Double = 0.95,
    val maxFallbackFrequency: Double = 0.15,
    val maxHallucinationProxy: Double = 0.20
)

data class SloReport(
    val p95LatencyMs: Long,
    val successRate: Double,
    val fallbackFrequency: Double,
    val hallucinationProxyMean: Double,
    val violations: List<String>
) {
    val healthy: Boolean = violations.isEmpty()
}

data class DeployReliabilitySummary(
    val initiatedCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val completionRate: Double,
    val failureRate: Double
)

data class ProviderRuntimeQuality(
    val provider: String,
    val invocationCount: Int,
    val avgLatencyMs: Long,
    val errorRate: Double
)

data class RuntimeQualitySummary(
    val providers: List<ProviderRuntimeQuality>,
    val overallAvgLatencyMs: Long,
    val overallErrorRate: Double
)

object SloTracker {
    fun fromBenchmarks(result: BenchmarkSuiteResult, targets: SloTargets = SloTargets()): SloReport {
        val violations = mutableListOf<String>()

        if (result.p95LatencyMs > targets.maxP95LatencyMs) {
            violations += "p95 latency ${result.p95LatencyMs}ms > target ${targets.maxP95LatencyMs}ms"
        }
        if (result.successRate < targets.minSuccessRate) {
            violations += "success rate ${pct(result.successRate)} < target ${pct(targets.minSuccessRate)}"
        }
        if (result.fallbackFrequency > targets.maxFallbackFrequency) {
            violations += "fallback frequency ${pct(result.fallbackFrequency)} > target ${pct(targets.maxFallbackFrequency)}"
        }
        if (result.hallucinationProxyMean > targets.maxHallucinationProxy) {
            violations += "hallucination proxy ${pct(result.hallucinationProxyMean)} > target ${pct(targets.maxHallucinationProxy)}"
        }

        return SloReport(
            p95LatencyMs = result.p95LatencyMs,
            successRate = result.successRate,
            fallbackFrequency = result.fallbackFrequency,
            hallucinationProxyMean = result.hallucinationProxyMean,
            violations = violations
        )
    }

    fun summarizeDeployReliability(traces: List<RequestTrace>): DeployReliabilitySummary {
        val initiated = countEvents(traces, TraceEventType.DEPLOY_INITIATED)
        val completed = countEvents(traces, TraceEventType.DEPLOY_COMPLETED)
        val failed = countEvents(traces, TraceEventType.DEPLOY_FAILED)
        val attempts = (completed + failed).coerceAtLeast(1)

        return DeployReliabilitySummary(
            initiatedCount = initiated,
            completedCount = completed,
            failedCount = failed,
            completionRate = completed.toDouble() / attempts,
            failureRate = failed.toDouble() / attempts
        )
    }

    fun summarizeRuntimeQuality(traces: List<RequestTrace>): RuntimeQualitySummary {
        val invocationEvents = traces
            .flatMap { it.events }
            .filter { it.type == TraceEventType.INVOCATION_METRIC }

        val providerSummaries = invocationEvents
            .groupBy { it.payload["provider"].orEmpty().ifBlank { "unknown" } }
            .map { (provider, events) ->
                val latencies = events.mapNotNull { it.payload["latency_ms"]?.toLongOrNull() }
                val errors = events.mapNotNull { it.payload["error_rate"]?.toDoubleOrNull() }
                ProviderRuntimeQuality(
                    provider = provider,
                    invocationCount = events.size,
                    avgLatencyMs = if (latencies.isNotEmpty()) latencies.average().toLong() else 0L,
                    errorRate = if (errors.isNotEmpty()) errors.average() else 0.0
                )
            }
            .sortedBy { it.provider }

        val totalInvocations = providerSummaries.sumOf { it.invocationCount }
        val weightedLatency = if (totalInvocations == 0) {
            0L
        } else {
            providerSummaries.sumOf { it.avgLatencyMs * it.invocationCount }.toDouble().div(totalInvocations).toLong()
        }
        val weightedErrorRate = if (totalInvocations == 0) {
            0.0
        } else {
            providerSummaries.sumOf { it.errorRate * it.invocationCount }.div(totalInvocations)
        }

        return RuntimeQualitySummary(
            providers = providerSummaries,
            overallAvgLatencyMs = weightedLatency,
            overallErrorRate = weightedErrorRate
        )
    }

    fun renderInternalDashboard(report: SloReport, traces: List<RequestTrace>): String {
        val eventCounts = TraceEventType.entries.associateWith { eventType ->
            traces.sumOf { trace -> trace.events.count { it.type == eventType } }
        }
        val deploySummary = summarizeDeployReliability(traces)
        val runtimeSummary = summarizeRuntimeQuality(traces)

        return buildString {
            appendLine("# Internal GenAI Reliability Dashboard")
            appendLine()
            appendLine("## SLO Overview")
            appendLine("- **Healthy:** ${if (report.healthy) "yes" else "no"}")
            appendLine("- **p95 latency:** ${report.p95LatencyMs} ms")
            appendLine("- **Success rate:** ${pct(report.successRate)}")
            appendLine("- **Fallback frequency:** ${pct(report.fallbackFrequency)}")
            appendLine("- **Hallucination proxy (mean):** ${pct(report.hallucinationProxyMean)}")
            appendLine()
            appendLine("## Deploy Reliability")
            appendLine("- **Deploy initiated:** ${deploySummary.initiatedCount}")
            appendLine("- **Deploy completed:** ${deploySummary.completedCount}")
            appendLine("- **Deploy failed:** ${deploySummary.failedCount}")
            appendLine("- **Completion rate:** ${pct(deploySummary.completionRate)}")
            appendLine("- **Failure rate:** ${pct(deploySummary.failureRate)}")
            appendLine()
            appendLine("## Runtime Quality")
            appendLine("- **Overall avg provider latency:** ${runtimeSummary.overallAvgLatencyMs} ms")
            appendLine("- **Overall provider error rate:** ${pct(runtimeSummary.overallErrorRate)}")
            if (runtimeSummary.providers.isEmpty()) {
                appendLine("- Provider breakdown: none")
            } else {
                runtimeSummary.providers.forEach { providerSummary ->
                    appendLine(
                        "- ${providerSummary.provider}: " +
                            "invocations=${providerSummary.invocationCount}, " +
                            "avg_latency_ms=${providerSummary.avgLatencyMs}, " +
                            "error_rate=${pct(providerSummary.errorRate)}"
                    )
                }
            }
            appendLine()
            appendLine("## Violations")
            if (report.violations.isEmpty()) {
                appendLine("- None")
            } else {
                report.violations.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("## Trace Event Volumes")
            eventCounts.forEach { (event, count) ->
                appendLine("- ${event.name}: $count")
            }
            appendLine()
            appendLine("## Recent Requests")
            traces.takeLast(10).forEach { trace ->
                appendLine("- ${trace.requestId}: ${trace.durationMs} ms, events=${trace.events.size}")
            }
        }
    }

    private fun countEvents(traces: List<RequestTrace>, type: TraceEventType): Int {
        return traces.sumOf { trace -> trace.events.count { it.type == type } }
    }

    private fun pct(value: Double): String = DecimalFormat("0.00%").format(value)
}

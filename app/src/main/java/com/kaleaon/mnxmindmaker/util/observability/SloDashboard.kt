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

    fun renderInternalDashboard(report: SloReport, traces: List<RequestTrace>): String {
        val eventCounts = TraceEventType.entries.associateWith { eventType ->
            traces.sumOf { trace -> trace.events.count { it.type == eventType } }
        }

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

    private fun pct(value: Double): String = DecimalFormat("0.00%").format(value)
}

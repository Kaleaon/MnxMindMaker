package com.kaleaon.mnxmindmaker.observability

import com.kaleaon.mnxmindmaker.util.eval.BenchmarkOutcome
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkSuiteResult
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTask
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTaskType
import com.kaleaon.mnxmindmaker.util.observability.RequestTrace
import com.kaleaon.mnxmindmaker.util.observability.RetrievalTargets
import com.kaleaon.mnxmindmaker.util.observability.SloTargets
import com.kaleaon.mnxmindmaker.util.observability.SloTracker
import com.kaleaon.mnxmindmaker.util.observability.TraceEvent
import com.kaleaon.mnxmindmaker.util.observability.TraceEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SloDashboardTest {

    @Test
    fun `tracker surfaces violations and renders dashboard markdown`() {
        val outcomes = listOf(
            BenchmarkOutcome(
                task = BenchmarkTask("a", BenchmarkTaskType.FACTUAL_QA, "q1"),
                latencyMs = 5_500,
                success = true,
                fallbackUsed = true,
                hallucinationProxy = 0.35,
                invokedTools = emptySet(),
                responseText = "ok"
            ),
            BenchmarkOutcome(
                task = BenchmarkTask("b", BenchmarkTaskType.INSTRUCTION_FOLLOWING, "q2"),
                latencyMs = 3_200,
                success = false,
                fallbackUsed = false,
                hallucinationProxy = 0.40,
                invokedTools = emptySet(),
                responseText = "bad"
            )
        )

        val report = SloTracker.fromBenchmarks(
            BenchmarkSuiteResult(outcomes),
            SloTargets(maxP95LatencyMs = 4_000, minSuccessRate = 0.9, maxFallbackFrequency = 0.1, maxHallucinationProxy = 0.2)
        )

        assertFalse(report.healthy)
        assertTrue(report.violations.isNotEmpty())

        val dashboard = SloTracker.renderInternalDashboard(
            report,
            traces = listOf(
                RequestTrace(
                    requestId = "req-1",
                    startedAtEpochMs = 100,
                    completedAtEpochMs = 240,
                    events = listOf(
                        TraceEvent(TraceEventType.PROMPT_PIPELINE, 120, mapOf("stage" to "incoming")),
                        TraceEvent(
                            TraceEventType.DEPLOY_INITIATED,
                            150,
                            mapOf("persona_version" to "v4", "deployment_manifest_hash" to "abc")
                        ),
                        TraceEvent(
                            TraceEventType.DEPLOY_COMPLETED,
                            180,
                            mapOf("persona_version" to "v4", "deployment_manifest_hash" to "abc")
                        ),
                        TraceEvent(
                            TraceEventType.INVOCATION_METRIC,
                            210,
                            mapOf("provider" to "OPENAI", "latency_ms" to "220", "error_rate" to "0.05")
                        ),
                        TraceEvent(TraceEventType.PROVIDER_RESPONSE, 220, mapOf("provider" to "OPENAI"))
                    )
                )
            )
        )

        assertTrue(dashboard.contains("Internal GenAI Reliability Dashboard"))
        assertTrue(dashboard.contains("PROMPT_PIPELINE"))
        assertTrue(dashboard.contains("Deploy Reliability"))
        assertTrue(dashboard.contains("Runtime Quality"))
        assertTrue(dashboard.contains("Retrieval Benchmarks"))
        assertTrue(dashboard.contains("OPENAI"))
    }

    @Test
    fun `summary helpers aggregate deploy and runtime quality`() {
        val traces = listOf(
            RequestTrace(
                requestId = "req-deploy-1",
                startedAtEpochMs = 10,
                completedAtEpochMs = 20,
                events = listOf(
                    TraceEvent(TraceEventType.DEPLOY_INITIATED, 11, emptyMap()),
                    TraceEvent(TraceEventType.DEPLOY_COMPLETED, 12, emptyMap())
                )
            ),
            RequestTrace(
                requestId = "req-deploy-2",
                startedAtEpochMs = 30,
                completedAtEpochMs = 40,
                events = listOf(
                    TraceEvent(TraceEventType.DEPLOY_INITIATED, 31, emptyMap()),
                    TraceEvent(TraceEventType.DEPLOY_FAILED, 32, emptyMap())
                )
            ),
            RequestTrace(
                requestId = "req-runtime",
                startedAtEpochMs = 50,
                completedAtEpochMs = 70,
                events = listOf(
                    TraceEvent(
                        TraceEventType.INVOCATION_METRIC,
                        51,
                        mapOf("provider" to "OPENAI", "latency_ms" to "300", "error_rate" to "0.10")
                    ),
                    TraceEvent(
                        TraceEventType.INVOCATION_METRIC,
                        52,
                        mapOf("provider" to "OPENAI", "latency_ms" to "500", "error_rate" to "0.20")
                    ),
                    TraceEvent(
                        TraceEventType.INVOCATION_METRIC,
                        53,
                        mapOf("provider" to "LOCAL", "latency_ms" to "100", "error_rate" to "0.00")
                    )
                )
            )
        )

        val deploySummary = SloTracker.summarizeDeployReliability(traces)
        assertEquals(2, deploySummary.initiatedCount)
        assertEquals(1, deploySummary.completedCount)
        assertEquals(1, deploySummary.failedCount)
        assertEquals(0.5, deploySummary.completionRate, 0.001)
        assertEquals(0.5, deploySummary.failureRate, 0.001)

        val runtimeSummary = SloTracker.summarizeRuntimeQuality(traces)
        assertEquals(2, runtimeSummary.providers.size)
        assertEquals(300L, runtimeSummary.overallAvgLatencyMs)
        assertEquals(0.1, runtimeSummary.overallErrorRate, 0.001)
        assertTrue(runtimeSummary.providers.any { it.provider == "OPENAI" && it.avgLatencyMs == 400L })
        assertTrue(runtimeSummary.providers.any { it.provider == "LOCAL" && it.errorRate == 0.0 })
    }

    @Test
    fun `retrieval benchmarks publish markdown and json with regression alerts`() {
        val report = SloTracker.ingestRetrievalBenchmarks(
            perModeRecallAtK = mapOf("semantic" to 0.82, "hybrid" to 0.91),
            perModeMrr = mapOf("semantic" to 0.56, "hybrid" to 0.64),
            targets = RetrievalTargets(
                minRecallAtKByMode = mapOf("semantic" to 0.85, "hybrid" to 0.90),
                minMrrByMode = mapOf("semantic" to 0.60, "hybrid" to 0.60)
            )
        )

        assertEquals(2, report.metricsByMode.size)
        assertEquals(2, report.alerts.size)
        assertTrue(report.alerts.any { it.metric == "recall@k" && it.mode == "semantic" })
        assertTrue(report.alerts.any { it.metric == "mrr" && it.mode == "semantic" })

        val sloReport = SloTracker.fromBenchmarks(BenchmarkSuiteResult(emptyList()))
        val markdown = SloTracker.renderInternalDashboard(sloReport, traces = emptyList(), retrievalReport = report)
        assertTrue(markdown.contains("semantic: recall@k=82.00%, mrr=56.00%"))
        assertTrue(markdown.contains("Retrieval Regression Alerts"))

        val json = SloTracker.renderCiJsonReport(sloReport, traces = emptyList(), retrievalReport = report)
        assertTrue(json.contains("\"retrieval\""))
        assertTrue(json.contains("\"mode\":\"semantic\""))
        assertTrue(json.contains("\"regressionAlerts\""))
    }
}

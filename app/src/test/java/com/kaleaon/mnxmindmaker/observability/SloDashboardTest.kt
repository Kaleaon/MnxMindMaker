package com.kaleaon.mnxmindmaker.observability

import com.kaleaon.mnxmindmaker.util.eval.BenchmarkOutcome
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkSuiteResult
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTask
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTaskType
import com.kaleaon.mnxmindmaker.util.observability.RequestTrace
import com.kaleaon.mnxmindmaker.util.observability.SloTargets
import com.kaleaon.mnxmindmaker.util.observability.SloTracker
import com.kaleaon.mnxmindmaker.util.observability.TraceEvent
import com.kaleaon.mnxmindmaker.util.observability.TraceEventType
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
                        TraceEvent(TraceEventType.PROVIDER_RESPONSE, 220, mapOf("provider" to "OPENAI"))
                    )
                )
            )
        )

        assertTrue(dashboard.contains("Internal GenAI Reliability Dashboard"))
        assertTrue(dashboard.contains("PROMPT_PIPELINE"))
    }
}

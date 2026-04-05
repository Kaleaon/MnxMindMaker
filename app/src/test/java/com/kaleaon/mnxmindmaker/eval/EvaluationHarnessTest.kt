package com.kaleaon.mnxmindmaker.eval

import com.kaleaon.mnxmindmaker.util.eval.BenchmarkRunner
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTask
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTaskType
import com.kaleaon.mnxmindmaker.util.eval.EvaluationHarness
import com.kaleaon.mnxmindmaker.util.eval.ExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationHarnessTest {

    @Test
    fun `suite computes success, p95, and offline parity benchmark`() {
        val runner = BenchmarkRunner { prompt, offlineMode ->
            when {
                prompt.contains("reason") -> ExecutionResult(
                    text = "The answer includes chain and final result.",
                    latencyMs = 180,
                    fallbackUsed = false,
                    toolNames = emptySet()
                )
                prompt.contains("tool") -> ExecutionResult(
                    text = "Used list_nodes then get_node.",
                    latencyMs = 320,
                    fallbackUsed = true,
                    toolNames = setOf("list_nodes", "get_node")
                )
                offlineMode -> ExecutionResult(
                    text = "offline response with stable facts",
                    latencyMs = 260,
                    fallbackUsed = false
                )
                else -> ExecutionResult(
                    text = "online response with stable facts",
                    latencyMs = 240,
                    fallbackUsed = false
                )
            }
        }

        val suite = EvaluationHarness(runner).evaluate(
            listOf(
                BenchmarkTask(
                    id = "t1",
                    type = BenchmarkTaskType.REASONING,
                    prompt = "reason this out",
                    expectedKeywords = setOf("answer", "result")
                ),
                BenchmarkTask(
                    id = "t2",
                    type = BenchmarkTaskType.TOOL_USE_ACCURACY,
                    prompt = "tool check",
                    requiredToolNames = setOf("list_nodes")
                ),
                BenchmarkTask(
                    id = "t3",
                    type = BenchmarkTaskType.OFFLINE_PARITY_VS_ONLINE,
                    prompt = "parity"
                )
            )
        )

        assertEquals(3, suite.outcomes.size)
        assertTrue(suite.p95LatencyMs >= 260)
        assertTrue(suite.successRate > 0.5)
        assertTrue(suite.fallbackFrequency > 0.0)
    }
}

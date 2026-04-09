package com.kaleaon.mnxmindmaker.eval

import com.kaleaon.mnxmindmaker.util.eval.BenchmarkMode
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkRunner
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTask
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTaskType
import com.kaleaon.mnxmindmaker.util.eval.EvaluationHarness
import com.kaleaon.mnxmindmaker.util.eval.ExecutionResult
import com.kaleaon.mnxmindmaker.util.eval.RetrievalBenchmarkDatasetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `retrieval metrics are computed for gold labels`() {
        val runner = BenchmarkRunner { _, _ ->
            ExecutionResult(
                text = "retrieved deployment memories",
                latencyMs = 120,
                fallbackUsed = false,
                retrievedMemoryIds = listOf("mem-x", "mem-deploy-rollback", "mem-y")
            )
        }

        val suite = EvaluationHarness(runner).evaluate(
            listOf(
                BenchmarkTask(
                    id = "ret-1",
                    type = BenchmarkTaskType.RETRIEVAL,
                    prompt = "deployment rollback checklist",
                    expectedMemoryIds = setOf("mem-deploy-rollback", "mem-z"),
                    retrievalCutoffs = setOf(1, 2, 3)
                )
            )
        )

        val retrieval = suite.outcomes.single().retrievalMetrics
        assertNotNull(retrieval)
        assertEquals(0.0, retrieval!!.recallAtK[1] ?: -1.0, 0.0001)
        assertEquals(0.5, retrieval.recallAtK[2] ?: -1.0, 0.0001)
        assertFalse(retrieval.hitAtK[1] ?: true)
        assertTrue(retrieval.hitAtK[2] ?: false)
        assertEquals(0.5, retrieval.mrr, 0.0001)

        val aggregate = suite.retrievalMetrics
        assertNotNull(aggregate)
        assertEquals(0.5, aggregate!!.meanRecallAtK[2] ?: -1.0, 0.0001)
        assertEquals(0.5, aggregate.meanMrr, 0.0001)
    }

    @Test
    fun `retrieval dataset loader validates schema and creates tasks`() {
        val dataset = """
            {
              "samples": [
                {
                  "query": "Find deployment rollback memories",
                  "expected_memory_ids": ["mem-1", "mem-2"]
                }
              ]
            }
        """.trimIndent()

        val tasks = RetrievalBenchmarkDatasetLoader.toBenchmarkTasks(dataset, idPrefix = "kb")

        assertEquals(1, tasks.size)
        assertEquals(BenchmarkTaskType.RETRIEVAL, tasks.single().type)
        assertEquals(setOf("mem-1", "mem-2"), tasks.single().expectedMemoryIds)
        assertEquals(setOf(1, 3, 5), tasks.single().retrievalCutoffs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `retrieval dataset loader rejects invalid schema`() {
        val dataset = """
            {
              "samples": [
                {
                  "query": "",
                  "expected_memory_ids": []
                }
              ]
            }
        """.trimIndent()

        RetrievalBenchmarkDatasetLoader.toBenchmarkTasks(dataset)
    }

    @Test
    fun `evaluate by mode provides comparative per-mode task output`() {
        val runner = BenchmarkRunner { prompt, offlineMode ->
            if (prompt.contains("rollback") && !offlineMode) {
                ExecutionResult(
                    text = "online retrieval",
                    latencyMs = 80,
                    fallbackUsed = false,
                    retrievedMemoryIds = listOf("mem-rollback")
                )
            } else {
                ExecutionResult(
                    text = "offline retrieval",
                    latencyMs = 90,
                    fallbackUsed = false,
                    retrievedMemoryIds = emptyList()
                )
            }
        }

        val comparison = EvaluationHarness(runner).evaluateByMode(
            tasks = listOf(
                BenchmarkTask(
                    id = "ret",
                    type = BenchmarkTaskType.RETRIEVAL,
                    prompt = "rollback",
                    expectedMemoryIds = setOf("mem-rollback"),
                    retrievalCutoffs = setOf(1)
                )
            ),
            modes = listOf(
                BenchmarkMode(name = "online", offlineMode = false),
                BenchmarkMode(name = "offline", offlineMode = true)
            )
        )

        assertEquals(2, comparison.modeResults.size)
        assertEquals(1, comparison.taskComparisons.size)
        assertEquals(true, comparison.taskComparisons.single().perModeSuccess["online"])
        assertEquals(false, comparison.taskComparisons.single().perModeSuccess["offline"])
    }
}

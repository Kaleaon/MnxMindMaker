package com.kaleaon.mnxmindmaker.util.eval

import kotlin.math.max

enum class BenchmarkTaskType {
    REASONING,
    INSTRUCTION_FOLLOWING,
    FACTUAL_QA,
    TOOL_USE_ACCURACY,
    OFFLINE_PARITY_VS_ONLINE
}

data class BenchmarkTask(
    val id: String,
    val type: BenchmarkTaskType,
    val prompt: String,
    val expectedKeywords: Set<String> = emptySet(),
    val requiredToolNames: Set<String> = emptySet(),
    val expectedSuccess: Boolean = true
)

data class BenchmarkOutcome(
    val task: BenchmarkTask,
    val latencyMs: Long,
    val success: Boolean,
    val fallbackUsed: Boolean,
    val hallucinationProxy: Double,
    val invokedTools: Set<String>,
    val responseText: String
)

data class BenchmarkSuiteResult(
    val outcomes: List<BenchmarkOutcome>
) {
    val successRate: Double = if (outcomes.isEmpty()) 0.0 else outcomes.count { it.success }.toDouble() / outcomes.size
    val fallbackFrequency: Double = if (outcomes.isEmpty()) 0.0 else outcomes.count { it.fallbackUsed }.toDouble() / outcomes.size
    val hallucinationProxyMean: Double = if (outcomes.isEmpty()) 1.0 else outcomes.map { it.hallucinationProxy }.average()

    val p95LatencyMs: Long = percentile(outcomes.map { it.latencyMs }, 0.95)

    private fun percentile(values: List<Long>, quantile: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * quantile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}

data class ExecutionResult(
    val text: String,
    val latencyMs: Long,
    val fallbackUsed: Boolean,
    val toolNames: Set<String> = emptySet()
)

fun interface BenchmarkRunner {
    fun run(prompt: String, offlineMode: Boolean = false): ExecutionResult
}

class EvaluationHarness(
    private val runner: BenchmarkRunner
) {

    fun evaluate(tasks: List<BenchmarkTask>): BenchmarkSuiteResult {
        val outcomes = tasks.map { task ->
            val exec = when (task.type) {
                BenchmarkTaskType.OFFLINE_PARITY_VS_ONLINE -> {
                    val online = runner.run(task.prompt, offlineMode = false)
                    val offline = runner.run(task.prompt, offlineMode = true)
                    val parity = tokenJaccard(online.text, offline.text)
                    BenchmarkOutcome(
                        task = task,
                        latencyMs = max(online.latencyMs, offline.latencyMs),
                        success = parity >= 0.7,
                        fallbackUsed = online.fallbackUsed || offline.fallbackUsed,
                        hallucinationProxy = 1.0 - parity,
                        invokedTools = online.toolNames + offline.toolNames,
                        responseText = "online=${online.text}\noffline=${offline.text}"
                    )
                }

                else -> {
                    val result = runner.run(task.prompt, offlineMode = false)
                    val success = evaluateSuccess(task, result)
                    BenchmarkOutcome(
                        task = task,
                        latencyMs = result.latencyMs,
                        success = success,
                        fallbackUsed = result.fallbackUsed,
                        hallucinationProxy = hallucinationProxy(task, result),
                        invokedTools = result.toolNames,
                        responseText = result.text
                    )
                }
            }
            exec
        }
        return BenchmarkSuiteResult(outcomes)
    }

    private fun evaluateSuccess(task: BenchmarkTask, result: ExecutionResult): Boolean {
        val normalized = result.text.lowercase()
        val keywordOk = task.expectedKeywords.all { normalized.contains(it.lowercase()) }
        val toolOk = task.requiredToolNames.isEmpty() || task.requiredToolNames.all { it in result.toolNames }
        val expectationOk = task.expectedSuccess
        return keywordOk && toolOk && expectationOk
    }

    private fun hallucinationProxy(task: BenchmarkTask, result: ExecutionResult): Double {
        if (task.expectedKeywords.isEmpty()) return 0.15
        val normalized = result.text.lowercase()
        val hitCount = task.expectedKeywords.count { normalized.contains(it.lowercase()) }
        val coverage = hitCount.toDouble() / task.expectedKeywords.size.toDouble()
        return (1.0 - coverage).coerceIn(0.0, 1.0)
    }

    private fun tokenJaccard(a: String, b: String): Double {
        val left = tokenize(a)
        val right = tokenize(b)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return if (union == 0.0) 0.0 else (intersection / union)
    }

    private fun tokenize(input: String): Set<String> {
        return input
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()
    }
}

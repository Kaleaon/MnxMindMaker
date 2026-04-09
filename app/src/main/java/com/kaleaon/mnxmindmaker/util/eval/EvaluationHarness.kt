package com.kaleaon.mnxmindmaker.util.eval

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

enum class BenchmarkTaskType {
    REASONING,
    INSTRUCTION_FOLLOWING,
    FACTUAL_QA,
    TOOL_USE_ACCURACY,
    OFFLINE_PARITY_VS_ONLINE,
    RETRIEVAL
}

data class BenchmarkTask(
    val id: String,
    val type: BenchmarkTaskType,
    val prompt: String,
    val expectedKeywords: Set<String> = emptySet(),
    val requiredToolNames: Set<String> = emptySet(),
    val expectedSuccess: Boolean = true,
    val expectedMemoryIds: Set<String> = emptySet(),
    val retrievalCutoffs: Set<Int> = emptySet()
)

data class RetrievalMetrics(
    val recallAtK: Map<Int, Double>,
    val hitAtK: Map<Int, Boolean>,
    val mrr: Double
)

data class RetrievalSuiteMetrics(
    val meanRecallAtK: Map<Int, Double>,
    val meanHitAtK: Map<Int, Double>,
    val meanMrr: Double
)

data class BenchmarkOutcome(
    val task: BenchmarkTask,
    val latencyMs: Long,
    val success: Boolean,
    val fallbackUsed: Boolean,
    val hallucinationProxy: Double,
    val invokedTools: Set<String>,
    val responseText: String,
    val retrievalMetrics: RetrievalMetrics? = null
)

data class BenchmarkSuiteResult(
    val outcomes: List<BenchmarkOutcome>
) {
    val successRate: Double = if (outcomes.isEmpty()) 0.0 else outcomes.count { it.success }.toDouble() / outcomes.size
    val fallbackFrequency: Double = if (outcomes.isEmpty()) 0.0 else outcomes.count { it.fallbackUsed }.toDouble() / outcomes.size
    val hallucinationProxyMean: Double = if (outcomes.isEmpty()) 1.0 else outcomes.map { it.hallucinationProxy }.average()

    val p95LatencyMs: Long = percentile(outcomes.map { it.latencyMs }, 0.95)

    val retrievalMetrics: RetrievalSuiteMetrics? = aggregateRetrievalMetrics(outcomes)

    private fun percentile(values: List<Long>, quantile: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * quantile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun aggregateRetrievalMetrics(outcomes: List<BenchmarkOutcome>): RetrievalSuiteMetrics? {
        val retrievalOutcomes = outcomes.mapNotNull { it.retrievalMetrics }
        if (retrievalOutcomes.isEmpty()) return null

        val ks = retrievalOutcomes.flatMap { it.recallAtK.keys }.toSortedSet()
        val meanRecallAtK = ks.associateWith { k -> retrievalOutcomes.map { it.recallAtK[k] ?: 0.0 }.average() }
        val meanHitAtK = ks.associateWith { k ->
            retrievalOutcomes.map { metrics -> if (metrics.hitAtK[k] == true) 1.0 else 0.0 }.average()
        }
        val meanMrr = retrievalOutcomes.map { it.mrr }.average()
        return RetrievalSuiteMetrics(
            meanRecallAtK = meanRecallAtK,
            meanHitAtK = meanHitAtK,
            meanMrr = meanMrr
        )
    }
}

data class ExecutionResult(
    val text: String,
    val latencyMs: Long,
    val fallbackUsed: Boolean,
    val toolNames: Set<String> = emptySet(),
    val retrievedMemoryIds: List<String> = emptyList()
)

fun interface BenchmarkRunner {
    fun run(prompt: String, offlineMode: Boolean = false): ExecutionResult
}

data class BenchmarkMode(
    val name: String,
    val offlineMode: Boolean
)

data class BenchmarkModeResult(
    val mode: BenchmarkMode,
    val suite: BenchmarkSuiteResult
)

data class TaskModeComparison(
    val taskId: String,
    val perModeSuccess: Map<String, Boolean>
)

data class BenchmarkComparisonResult(
    val modeResults: List<BenchmarkModeResult>,
    val taskComparisons: List<TaskModeComparison>
)

data class RetrievalBenchmarkSample(
    val query: String,
    val expectedMemoryIds: Set<String>
)

object RetrievalBenchmarkDatasetLoader {

    fun parseSamples(datasetJson: String): List<RetrievalBenchmarkSample> {
        val trimmed = datasetJson.trim()
        val rootArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val rootObj = JSONObject(trimmed)
            rootObj.optJSONArray("samples")
                ?: throw IllegalArgumentException("Retrieval dataset must contain a 'samples' array.")
        }

        if (rootArray.length() == 0) {
            throw IllegalArgumentException("Retrieval dataset must contain at least one sample.")
        }

        return (0 until rootArray.length()).map { idx ->
            val obj = rootArray.optJSONObject(idx)
                ?: throw IllegalArgumentException("Sample at index $idx must be a JSON object.")
            parseSample(obj, idx)
        }
    }

    fun toBenchmarkTasks(
        datasetJson: String,
        idPrefix: String = "retrieval",
        retrievalCutoffs: Set<Int> = setOf(1, 3, 5)
    ): List<BenchmarkTask> {
        val validatedCutoffs = retrievalCutoffs.filter { it > 0 }.toSortedSet()
        if (validatedCutoffs.isEmpty()) {
            throw IllegalArgumentException("retrievalCutoffs must contain at least one positive k value.")
        }

        return parseSamples(datasetJson).mapIndexed { index, sample ->
            BenchmarkTask(
                id = "$idPrefix-${index + 1}",
                type = BenchmarkTaskType.RETRIEVAL,
                prompt = sample.query,
                expectedMemoryIds = sample.expectedMemoryIds,
                retrievalCutoffs = validatedCutoffs
            )
        }
    }

    private fun parseSample(obj: JSONObject, idx: Int): RetrievalBenchmarkSample {
        val query = obj.optString("query", "").trim()
        if (query.isEmpty()) {
            throw IllegalArgumentException("Sample at index $idx is missing a non-empty 'query' field.")
        }

        val memoryIdsArray = obj.optJSONArray("expected_memory_ids")
            ?: obj.optJSONArray("expectedMemoryIds")
            ?: throw IllegalArgumentException("Sample at index $idx is missing 'expected_memory_ids' array.")

        val expectedMemoryIds = mutableSetOf<String>()
        for (i in 0 until memoryIdsArray.length()) {
            val memoryId = memoryIdsArray.optString(i, "").trim()
            if (memoryId.isEmpty()) {
                throw IllegalArgumentException("Sample at index $idx has blank memory id at position $i.")
            }
            expectedMemoryIds += memoryId
        }

        if (expectedMemoryIds.isEmpty()) {
            throw IllegalArgumentException("Sample at index $idx must include at least one expected memory id.")
        }

        return RetrievalBenchmarkSample(query = query, expectedMemoryIds = expectedMemoryIds)
    }
}

class EvaluationHarness(
    private val runner: BenchmarkRunner
) {

    fun evaluate(tasks: List<BenchmarkTask>): BenchmarkSuiteResult {
        return evaluate(tasks, offlineMode = false)
    }

    fun evaluate(tasks: List<BenchmarkTask>, offlineMode: Boolean): BenchmarkSuiteResult {
        val outcomes = tasks.map { task -> evaluateTask(task, offlineMode) }
        return BenchmarkSuiteResult(outcomes)
    }

    fun evaluateByMode(tasks: List<BenchmarkTask>, modes: List<BenchmarkMode>): BenchmarkComparisonResult {
        val modeResults = modes.map { mode ->
            BenchmarkModeResult(
                mode = mode,
                suite = evaluate(tasks, offlineMode = mode.offlineMode)
            )
        }

        val taskComparisons = tasks.map { task ->
            val perMode = modeResults.associate { modeResult ->
                val success = modeResult.suite.outcomes.firstOrNull { it.task.id == task.id }?.success ?: false
                modeResult.mode.name to success
            }
            TaskModeComparison(task.id, perMode)
        }

        return BenchmarkComparisonResult(
            modeResults = modeResults,
            taskComparisons = taskComparisons
        )
    }

    private fun evaluateTask(task: BenchmarkTask, offlineMode: Boolean): BenchmarkOutcome {
        return when (task.type) {
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

            BenchmarkTaskType.RETRIEVAL -> evaluateRetrievalTask(task, offlineMode)

            else -> {
                val result = runner.run(task.prompt, offlineMode = offlineMode)
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
    }

    private fun evaluateRetrievalTask(task: BenchmarkTask, offlineMode: Boolean): BenchmarkOutcome {
        if (task.expectedMemoryIds.isEmpty()) {
            throw IllegalArgumentException("Retrieval task '${task.id}' must include expectedMemoryIds.")
        }
        val ks = task.retrievalCutoffs.filter { it > 0 }.toSortedSet().ifEmpty { sortedSetOf(1, 3, 5) }
        val result = runner.run(task.prompt, offlineMode = offlineMode)
        val metrics = computeRetrievalMetrics(
            expectedMemoryIds = task.expectedMemoryIds,
            retrievedMemoryIds = result.retrievedMemoryIds,
            cutoffs = ks
        )
        val success = metrics.hitAtK[ks.max()] == true

        return BenchmarkOutcome(
            task = task,
            latencyMs = result.latencyMs,
            success = success,
            fallbackUsed = result.fallbackUsed,
            hallucinationProxy = 1.0 - metrics.mrr.coerceIn(0.0, 1.0),
            invokedTools = result.toolNames,
            responseText = result.text,
            retrievalMetrics = metrics
        )
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

    private fun computeRetrievalMetrics(
        expectedMemoryIds: Set<String>,
        retrievedMemoryIds: List<String>,
        cutoffs: Set<Int>
    ): RetrievalMetrics {
        val normalizedGold = expectedMemoryIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalizedGold.isEmpty()) {
            return RetrievalMetrics(
                recallAtK = cutoffs.associateWith { 0.0 },
                hitAtK = cutoffs.associateWith { false },
                mrr = 0.0
            )
        }

        val normalizedRetrieved = retrievedMemoryIds.map { it.trim() }.filter { it.isNotEmpty() }
        val recallAtK = cutoffs.associateWith { k ->
            val topK = normalizedRetrieved.take(k).toSet()
            val hits = topK.intersect(normalizedGold).size
            hits.toDouble() / normalizedGold.size.toDouble()
        }
        val hitAtK = cutoffs.associateWith { k ->
            normalizedRetrieved.take(k).any { it in normalizedGold }
        }
        val firstRelevantRank = normalizedRetrieved.indexOfFirst { it in normalizedGold }
        val mrr = if (firstRelevantRank >= 0) 1.0 / (firstRelevantRank + 1).toDouble() else 0.0

        return RetrievalMetrics(recallAtK = recallAtK, hitAtK = hitAtK, mrr = mrr)
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

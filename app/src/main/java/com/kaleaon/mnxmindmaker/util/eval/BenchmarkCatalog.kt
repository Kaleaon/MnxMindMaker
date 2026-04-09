package com.kaleaon.mnxmindmaker.util.eval

object BenchmarkCatalog {

    fun defaultTasks(): List<BenchmarkTask> {
        return listOf(
            BenchmarkTask(
                id = "reasoning-01",
                type = BenchmarkTaskType.REASONING,
                prompt = "Solve a multi-step planning puzzle and justify the final answer.",
                expectedKeywords = setOf("final", "because")
            ),
            BenchmarkTask(
                id = "instruction-01",
                type = BenchmarkTaskType.INSTRUCTION_FOLLOWING,
                prompt = "Return exactly three bullet points and include the token DONE in the final bullet.",
                expectedKeywords = setOf("done")
            ),
            BenchmarkTask(
                id = "factual-qa-01",
                type = BenchmarkTaskType.FACTUAL_QA,
                prompt = "What does the project use to serialize MNX packets?",
                expectedKeywords = setOf("mnx", "packet")
            ),
            BenchmarkTask(
                id = "tool-accuracy-01",
                type = BenchmarkTaskType.TOOL_USE_ACCURACY,
                prompt = "Inspect graph state, then report node count.",
                requiredToolNames = setOf("get_graph_summary")
            ),
            BenchmarkTask(
                id = "retrieval-01",
                type = BenchmarkTaskType.RETRIEVAL,
                prompt = "Retrieve memories about deployment rollback checklist.",
                expectedMemoryIds = setOf("mem-deploy-rollback"),
                retrievalCutoffs = setOf(1, 3, 5)
            ),
            BenchmarkTask(
                id = "offline-parity-01",
                type = BenchmarkTaskType.OFFLINE_PARITY_VS_ONLINE,
                prompt = "Summarize the graph constraints in two lines."
            )
        )
    }
}

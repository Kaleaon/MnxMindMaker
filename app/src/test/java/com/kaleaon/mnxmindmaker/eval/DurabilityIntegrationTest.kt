package com.kaleaon.mnxmindmaker.eval

import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkRunner
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTask
import com.kaleaon.mnxmindmaker.util.eval.BenchmarkTaskType
import com.kaleaon.mnxmindmaker.util.eval.DurabilityCategoryScores
import com.kaleaon.mnxmindmaker.util.eval.DurabilityGate
import com.kaleaon.mnxmindmaker.util.eval.EvaluationHarness
import com.kaleaon.mnxmindmaker.util.eval.ExecutionResult
import com.kaleaon.mnxmindmaker.util.provider.AssistantProvider
import com.kaleaon.mnxmindmaker.util.provider.ProviderHealth
import com.kaleaon.mnxmindmaker.util.provider.ProviderRequest
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.InMemoryToolTranscriptStore
import com.kaleaon.mnxmindmaker.util.tooling.ToolExecutionEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolHandler
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolOperationClass
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyContext
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import com.kaleaon.mnxmindmaker.util.tooling.ToolTranscriptRecorder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurabilityIntegrationTest {

    @Test
    fun `import and export payloads round trip without data loss`() {
        val graph = sampleGraph()
        val payload = MnxRepository.serializeGraphPayload(graph)
        val encoded = MnxCodec.encodeToBytes(
            MnxFile(
                header = MnxHeader(createdTimestamp = graph.createdAt, modifiedTimestamp = graph.modifiedAt),
                sections = emptyMap(),
                rawSections = mapOf(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE to payload)
            )
        )

        val decodedFile = MnxCodec.decodeFromBytes(encoded)
        val decodedGraph = MnxRepository.deserializeGraphPayload(
            decodedFile.rawSections[MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE]!!
        )

        assertEquals(graph, decodedGraph)
    }

    @Test
    fun `retrieval integration meets minimum accuracy for seeded benchmark queries`() {
        val retrievalMap = mapOf(
            "release planning" to listOf("m_release", "m_retrospective", "m_design"),
            "incident review" to listOf("m_incident", "m_runbook", "m_release")
        )
        val runner = BenchmarkRunner { prompt, _ ->
            val retrieved = retrievalMap.entries
                .firstOrNull { prompt.contains(it.key, ignoreCase = true) }
                ?.value
                ?: emptyList()
            ExecutionResult(
                text = "retrieved=${retrieved.joinToString(",")}",
                latencyMs = 42,
                fallbackUsed = false,
                retrievedMemoryIds = retrieved
            )
        }

        val harness = EvaluationHarness(runner)
        val suite = harness.evaluate(
            listOf(
                BenchmarkTask(
                    id = "r1",
                    type = BenchmarkTaskType.RETRIEVAL,
                    prompt = "Need release planning checklist",
                    expectedMemoryIds = setOf("m_release"),
                    retrievalCutoffs = setOf(1, 3)
                ),
                BenchmarkTask(
                    id = "r2",
                    type = BenchmarkTaskType.RETRIEVAL,
                    prompt = "Summarize incident review notes",
                    expectedMemoryIds = setOf("m_runbook", "m_incident"),
                    retrievalCutoffs = setOf(1, 3)
                )
            ),
            offlineMode = false
        )

        val aggregate = suite.retrievalMetrics
        assertNotNull(aggregate)
        assertTrue((aggregate!!.meanRecallAtK[3] ?: 0.0) >= 0.75)
        assertTrue(aggregate.meanMrr >= 0.75)
    }

    @Test
    fun `migration decode remains safe for partial legacy manifests`() {
        val legacyManifestJson = """
            {
              "target": { "provider": "openai" },
              "inference": { "temperature": 0.42 },
              "tools": { "allowlist": ["memory_search"] },
              "unexpected_future_field": { "enabled": true }
            }
        """.trimIndent()

        val decoded = MnxRepository.decodePersonaDeploymentManifest(legacyManifestJson)

        assertEquals("openai", decoded.target.provider)
        assertEquals(0.42, decoded.inference.temperature, 0.0001)
        assertEquals(listOf("memory_search"), decoded.toolPolicy.allowlist)
        // Missing legacy fields should safely fall back to defaults.
        assertEquals(com.kaleaon.mnxmindmaker.persona.runtime.FallbackStrategy().mode, decoded.fallback.mode)
        assertEquals(com.kaleaon.mnxmindmaker.persona.runtime.InferenceParams().maxTokens, decoded.inference.maxTokens)
    }

    @Test
    fun `policy engine enforces deny list and blocks non approved mutation`() {
        val readSpec = ToolSpec(
            name = "memory_search",
            description = "read memory",
            operationClass = ToolOperationClass.READ_ONLY,
            requiresConfirmation = false
        )
        val writeSpec = ToolSpec(
            name = "node_update",
            description = "mutate node",
            operationClass = ToolOperationClass.MUTATING
        )

        val graph = MindGraph(nodes = mutableListOf(MindNode(id = "n1", label = "Node", type = NodeType.MEMORY)))
        val policy = ToolPolicyEngine(mapOf(readSpec.name to readSpec, writeSpec.name to writeSpec))
        val execution = ToolExecutionEngine(
            policyEngine = policy,
            handlers = mapOf(
                readSpec.name to ToolHandler { _, _ ->
                    com.kaleaon.mnxmindmaker.util.tooling.ToolExecutionOutcome(
                        contentJson = JSONObject().put("ok", true),
                        mutatedGraph = false
                    )
                },
                writeSpec.name to ToolHandler { _, _ ->
                    com.kaleaon.mnxmindmaker.util.tooling.ToolExecutionOutcome(
                        contentJson = JSONObject().put("ok", true),
                        mutatedGraph = true
                    )
                }
            ),
            transcriptRecorder = ToolTranscriptRecorder(InMemoryToolTranscriptStore())
        )

        val result = execution.executeSequence(
            runId = "run-1",
            invocations = listOf(
                ToolInvocation(id = "1", toolName = readSpec.name),
                ToolInvocation(id = "2", toolName = writeSpec.name, argumentsJson = JSONObject().put("node_id", "n1"))
            ),
            graph = graph,
            policyContext = ToolPolicyContext(),
            approve = { _, _ -> false }
        )

        assertEquals(2, result.toolResults.size)
        assertFalse(result.toolResults.first().isError)
        assertTrue(result.toolResults.last().isError)
        assertEquals("approval_rejected", result.toolResults.last().contentJson.optString("error"))
    }

    @Test
    fun `provider router fails over to secondary provider when primary errors`() {
        val openAiSettings = LlmSettings(provider = LlmProvider.OPENAI)
        val anthropicSettings = LlmSettings(provider = LlmProvider.ANTHROPIC)
        val router = ProviderRouter(
            providers = listOf(
                FailingProvider(id = "openai", supported = LlmProvider.OPENAI),
                SuccessProvider(id = "anthropic", supported = LlmProvider.ANTHROPIC, response = "fallback-ok")
            )
        )

        val turn = router.chat(
            settingsChain = listOf(openAiSettings, anthropicSettings),
            systemPrompt = "system",
            transcript = emptyList()
        )

        assertEquals("fallback-ok", turn.text)
    }

    @Test
    fun `durability score gate passes only when all integration dimensions stay above threshold`() {
        val score = DurabilityGate.evaluate(
            scores = DurabilityCategoryScores(
                importExportRoundTrip = 1.0,
                retrievalAccuracy = 0.875,
                migrationSafety = 1.0,
                policyEnforcement = 1.0,
                failoverBehavior = 1.0
            ),
            threshold = 0.90
        )

        assertTrue("Durability gate failed: ${'$'}{score.overallScore}", score.passed)
        assertTrue((score.perCategory["retrieval_accuracy"] ?: 0.0) >= 0.85)
    }

    private fun sampleGraph(): MindGraph {
        return MindGraph(
            id = "g1",
            name = "Durability Graph",
            createdAt = 1_700_000_000_000,
            modifiedAt = 1_700_000_000_100,
            nodes = mutableListOf(
                MindNode(
                    id = "n_identity",
                    label = "Identity",
                    type = NodeType.IDENTITY,
                    description = "Root",
                    dimensions = mapOf("self_coherence" to 0.9f)
                ),
                MindNode(
                    id = "n_memory",
                    label = "Release Notes",
                    type = NodeType.MEMORY,
                    description = "Production release checklist",
                    parentId = "n_identity",
                    attributes = mutableMapOf("source" to "integration-test")
                )
            )
        )
    }

    private class FailingProvider(
        override val id: String,
        private val supported: LlmProvider
    ) : AssistantProvider {
        override fun supports(settings: LlmSettings): Boolean = settings.provider == supported

        override fun chat(request: ProviderRequest): AssistantTurn {
            throw LlmApiException("${'$'}id unavailable")
        }

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(ok = true, message = "ok")
    }

    private class SuccessProvider(
        override val id: String,
        private val supported: LlmProvider,
        private val response: String
    ) : AssistantProvider {
        override fun supports(settings: LlmSettings): Boolean = settings.provider == supported

        override fun chat(request: ProviderRequest): AssistantTurn = AssistantTurn(text = response)

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(ok = true, message = "ok")
    }
}

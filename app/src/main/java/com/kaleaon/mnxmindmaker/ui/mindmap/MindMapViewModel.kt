package com.kaleaon.mnxmindmaker.ui.mindmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.continuity.ContinuityManager
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.run_continuity_audit
import com.kaleaon.mnxmindmaker.util.tooling.ToolApprovalRequest
import com.kaleaon.mnxmindmaker.util.tooling.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class MindMapViewModel(application: Application) : AndroidViewModel(application) {

    private val mnxRepository = MnxRepository(application)
    private val llmRepository = LlmSettingsRepository(application)
    private val continuityManager = ContinuityManager(application)
    private val llmClient = LlmApiClient()

    private val _graph = MutableLiveData(newDefaultGraph())
    val graph: LiveData<MindGraph> get() = _graph

    private val _selectedNode = MutableLiveData<MindNode?>()
    val selectedNode: LiveData<MindNode?> get() = _selectedNode

    private val _exportedFile = MutableLiveData<File?>()
    val exportedFile: LiveData<File?> get() = _exportedFile

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _snapshotTimeline = MutableLiveData<List<ContinuityManager.SnapshotRecord>>(emptyList())
    val snapshotTimeline: LiveData<List<ContinuityManager.SnapshotRecord>> get() = _snapshotTimeline

    private val _snapshotActionMessage = MutableLiveData<String?>()
    val snapshotActionMessage: LiveData<String?> get() = _snapshotActionMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _auditResult = MutableLiveData<ContinuityAuditResult?>()
    val auditResult: LiveData<ContinuityAuditResult?> get() = _auditResult

    private val acceptedFindingIds = mutableSetOf<String>()

    private val _toolApprovalRequest = MutableLiveData<ToolApprovalRequest?>()
    val toolApprovalRequest: LiveData<ToolApprovalRequest?> get() = _toolApprovalRequest

    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val _chatMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val chatMessages: LiveData<List<ChatMessage>> get() = _chatMessages

    private val _isPremiumUser = MutableLiveData(true)
    val isPremiumUser: LiveData<Boolean> get() = _isPremiumUser

    private val _compareCandidateMessageId = MutableLiveData<String?>()
    val compareCandidateMessageId: LiveData<String?> get() = _compareCandidateMessageId

    init {
        refreshAudit()
        refreshSnapshotTimeline()
    }

    fun addNode(label: String, type: NodeType, description: String = "") {
        val current = _graph.value ?: return
        val parentId = _selectedNode.value?.id
        val node = MindNode(
            label = label,
            type = type,
            description = description,
            x = (100..700).random().toFloat(),
            y = (100..600).random().toFloat(),
            parentId = parentId,
            dimensions = DimensionMapper.defaultDimensions(type)
        )
        val updatedNodes = current.nodes.toMutableList().also { it.add(node) }
        val updatedEdges = current.edges.toMutableList()
        if (parentId != null) {
            updatedEdges.add(MindEdge(fromNodeId = parentId, toNodeId = node.id))
        }
        _graph.value = current.copy(nodes = updatedNodes, edges = updatedEdges)
        _selectedNode.value = node
        refreshAudit()
    }

    fun removeNode(nodeId: String) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.filter { it.id != nodeId }.toMutableList()
        val updatedEdges = current.edges
            .filter { it.fromNodeId != nodeId && it.toNodeId != nodeId }
            .toMutableList()
        _graph.value = current.copy(nodes = updatedNodes, edges = updatedEdges)
        if (_selectedNode.value?.id == nodeId) _selectedNode.value = null
        refreshAudit()
    }

    fun updateNodePosition(nodeId: String, x: Float, y: Float) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.map { if (it.id == nodeId) it.copy(x = x, y = y) else it }
        _graph.value = current.copy(nodes = updatedNodes.toMutableList())
        refreshAudit()
    }

    fun selectNode(node: MindNode?) {
        _selectedNode.value = node
    }

    fun setGraphName(name: String) {
        _graph.value = _graph.value?.copy(name = name)
    }

    fun loadGraph(graph: MindGraph) {
        _graph.value = graph
        _selectedNode.value = null
        acceptedFindingIds.clear()
        refreshAudit()
    }

    fun runContinuityAudit() {
        refreshAudit()
    }

    fun acceptAuditFinding(findingId: String) {
        acceptedFindingIds += findingId
        refreshAudit()
    }

    fun convertAuditFindingToCorrectiveNode(findingId: String) {
        val current = _graph.value ?: return
        val finding = _auditResult.value?.findings?.firstOrNull { it.id == findingId } ?: return
        val anchorNode = current.nodes.firstOrNull { it.id == finding.nodeIds.firstOrNull() }
        val correctiveNode = MindNode(
            id = UUID.randomUUID().toString(),
            label = "Corrective: ${finding.title}",
            type = NodeType.MEMORY,
            description = finding.suggestedAction,
            x = (anchorNode?.x ?: 320f) + 90f,
            y = (anchorNode?.y ?: 280f) + 90f,
            attributes = mutableMapOf(
                "memory_class" to "repair",
                "semantic_subtype" to "continuity_corrective",
                "source_finding_id" to finding.id,
                "audit_category" to finding.category
            ),
            dimensions = DimensionMapper.defaultDimensions(NodeType.MEMORY)
        )
        val edges = current.edges.toMutableList()
        finding.nodeIds.forEach { nodeId ->
            if (current.nodes.any { it.id == nodeId }) {
                edges += MindEdge(
                    fromNodeId = nodeId,
                    toNodeId = correctiveNode.id,
                    label = "corrects",
                    strength = 0.85f
                )
            }
        }
        val updatedNodes = current.nodes.toMutableList().also { it += correctiveNode }
        _graph.value = current.copy(nodes = updatedNodes, edges = edges)
        acceptedFindingIds += findingId
        refreshAudit()
    }

    fun refreshSnapshotTimeline() {
        _snapshotTimeline.value = continuityManager.listSnapshots()
    }

    fun createSnapshot(reason: String, driftNotes: String = "") {
        val graph = _graph.value ?: return
        val snapshot = continuityManager.createSnapshot(graph, reason, driftNotes)
        refreshSnapshotTimeline()
        _snapshotActionMessage.value = "Snapshot ${snapshot.snapshotId.take(8)} created (${snapshot.reason})"
    }

    fun compareWithSnapshot(snapshotId: String) {
        val graph = _graph.value ?: return
        val drift = continuityManager.compareSnapshotWithGraph(snapshotId, graph)
        _snapshotActionMessage.value = "Drift: ${drift.indicator}"
    }

    fun restoreSnapshot(snapshotId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val restored = withContext(Dispatchers.IO) { continuityManager.restoreSnapshot(snapshotId) }
                _graph.value = restored
                _selectedNode.value = null
                _snapshotActionMessage.value = "Snapshot restored: ${snapshotId.take(8)}"
            } catch (e: Exception) {
                _error.value = "Restore failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSnapshotActionMessage() {
        _snapshotActionMessage.value = null
    }

    fun exportToMnx() {
        val graph = _graph.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val snapshot = continuityManager.createSnapshot(graph, reason = "export_mnx")
                    val metadata = MnxRepository.ContinuityMetadata(
                        snapshotId = snapshot.snapshotId,
                        parentSnapshotId = snapshot.parentSnapshotId,
                        integrityHash = snapshot.integrityHash,
                        reason = snapshot.reason,
                        driftNotes = snapshot.driftNotes
                    )
                    mnxRepository.exportToMnx(graph, metadata)
                }
                _exportedFile.value = file
                refreshSnapshotTimeline()
            } catch (e: Exception) {
                _error.value = "Export failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importFromMnx(stream: InputStream) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val graph = withContext(Dispatchers.IO) { mnxRepository.importFromMnx(stream) }
                _graph.value = graph
                acceptedFindingIds.clear()
                refreshAudit()
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun askLlmForMindDesign(prompt: String, choice: ComposerProviderChoice = ComposerProviderChoice.AUTO) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) { runSingleChat(prompt, choice) }
                if (result == null) return@launch
                val next = _chatMessages.value.orEmpty().toMutableList().apply { add(result) }
                _chatMessages.value = next
            } catch (e: LlmApiException) {
                _error.value = "AI error: ${e.message}"
            } catch (e: Exception) {
                _error.value = "AI request failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retryWithAnotherProvider(messageId: String) {
        val existing = _chatMessages.value.orEmpty().firstOrNull { it.id == messageId } ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val nextProvider = withContext(Dispatchers.IO) { pickAlternativeProvider(existing.provenance.provider) }
                if (nextProvider == null) {
                    _error.value = "No alternate enabled provider available for retry."
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    runSingleChat(existing.prompt, providerToChoice(nextProvider), forcedProvider = nextProvider)
                } ?: return@launch

                val updated = _chatMessages.value.orEmpty().map { msg ->
                    if (msg.id != messageId) {
                        msg
                    } else {
                        msg.copy(
                            compareCandidate = CompareCandidate(
                                provider = result.provenance.provider,
                                model = result.provenance.model,
                                response = result.response,
                                latencyMs = result.provenance.latencyMs,
                                totalTokens = result.provenance.totalTokens
                            )
                        )
                    }
                }
                _chatMessages.value = updated
                if (_isPremiumUser.value == true) {
                    _compareCandidateMessageId.value = messageId
                }
            } catch (e: Exception) {
                _error.value = "Retry failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearCompareCandidateMessage() {
        _compareCandidateMessageId.value = null
    }

    private fun runSingleChat(
        prompt: String,
        choice: ComposerProviderChoice,
        forcedProvider: LlmProvider? = null
    ): ChatMessage? {
        val chain = when {
            forcedProvider != null -> listOfNotNull(loadUsableSettings(forcedProvider))
            choice == ComposerProviderChoice.AUTO -> llmRepository.getInvocationChain()
            else -> listOfNotNull(loadUsableSettings(choice.toProvider()))
        }

        if (chain.isEmpty()) {
            _error.postValue("No usable LLM configured. Add a provider in Settings.")
            return null
        }

        val registry = ToolRegistry(
            getGraph = { _graph.value ?: newDefaultGraph() },
            setGraph = { updated -> _graph.postValue(updated) }
        )

        var lastError: String? = null
        for (settings in chain) {
            val systemPrompt = buildSystemPrompt(settings)
            val start = System.currentTimeMillis()
            try {
                val turn = llmClient.completeAssistantTurn(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    transcript = listOf(JSONObject().put("role", "user").put("content", prompt)),
                    tools = registry.specs()
                )
                val latency = System.currentTimeMillis() - start
                return ChatMessage(
                    id = UUID.randomUUID().toString(),
                    prompt = prompt,
                    response = turn.text,
                    providerChoice = choice,
                    provenance = MessageProvenance(
                        provider = settings.provider,
                        model = turn.raw?.optString("model").takeUnless { it.isNullOrBlank() } ?: settings.model,
                        toolCalls = turn.toolInvocations.map { it.name },
                        latencyMs = latency,
                        promptTokens = extractPromptTokens(turn.raw),
                        completionTokens = extractCompletionTokens(turn.raw),
                        totalTokens = extractTotalTokens(turn.raw)
                    )
                )
            } catch (e: LlmApiException) {
                lastError = "${settings.provider.displayName}: ${e.message}"
            }
        }

        _error.postValue("AI request failed across provider chain. $lastError")
        return null
    }

    private fun buildSystemPrompt(settings: LlmSettings): String {
        val caps = settings.capabilities
        return buildString {
            appendLine("You are an AI mind design assistant helping the user build an AI mind graph in .mnx format.")
            appendLine("The mind graph represents identity, memories, knowledge, emotions, personality, beliefs, values, and relationships.")
            appendLine("Provide concise, structured suggestions for mind nodes and connections.")
            appendLine("Format suggestions as: NodeType: label - description")
            if (!caps.supportsToolPlanning) appendLine("Avoid multi-step tool plans.")
            appendLine("Stay within approximately ${caps.contextWindowTokens / 8} output tokens.")
        }
    }

    private fun loadUsableSettings(provider: LlmProvider): LlmSettings? {
        val settings = llmRepository.loadSettings(provider)
        if (!settings.enabled) return null
        return when {
            provider.requiresApiKey && settings.apiKey.isBlank() -> null
            provider == LlmProvider.LOCAL_ON_DEVICE && settings.localModelPath.isBlank() -> null
            else -> settings
        }
    }

    private fun pickAlternativeProvider(current: LlmProvider): LlmProvider? {
        val prioritized = listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.LOCAL_ON_DEVICE)
        return prioritized.firstOrNull { provider ->
            provider != current && loadUsableSettings(provider) != null
        }
    }

    private fun providerToChoice(provider: LlmProvider): ComposerProviderChoice = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE -> ComposerProviderChoice.LOCAL
        LlmProvider.ANTHROPIC -> ComposerProviderChoice.CLAUDE
        LlmProvider.OPENAI -> ComposerProviderChoice.CHATGPT
        else -> ComposerProviderChoice.AUTO
    }

    private fun ComposerProviderChoice.toProvider(): LlmProvider = when (this) {
        ComposerProviderChoice.AUTO -> LlmProvider.OPENAI
        ComposerProviderChoice.LOCAL -> LlmProvider.LOCAL_ON_DEVICE
        ComposerProviderChoice.CLAUDE -> LlmProvider.ANTHROPIC
        ComposerProviderChoice.CHATGPT -> LlmProvider.OPENAI
    }

    private fun extractPromptTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        return raw.optJSONObject("usage")?.optInt("prompt_tokens").takeIf { it != null && it >= 0 }
            ?: raw.optJSONObject("usage")?.optInt("input_tokens")
            ?: raw.optJSONObject("usageMetadata")?.optInt("promptTokenCount")
    }

    private fun extractCompletionTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        return raw.optJSONObject("usage")?.optInt("completion_tokens").takeIf { it != null && it >= 0 }
            ?: raw.optJSONObject("usage")?.optInt("output_tokens")
            ?: raw.optJSONObject("usageMetadata")?.optInt("candidatesTokenCount")
    }

    private fun extractTotalTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        val openAiTotal = raw.optJSONObject("usage")?.optInt("total_tokens")
        if (openAiTotal != null && openAiTotal >= 0) return openAiTotal
        val usageMeta = raw.optJSONObject("usageMetadata")
        if (usageMeta != null) return usageMeta.optInt("totalTokenCount").takeIf { it >= 0 }
        val prompt = extractPromptTokens(raw)
        val completion = extractCompletionTokens(raw)
        return if (prompt != null && completion != null) prompt + completion else null
    }

    fun resolveToolApproval(requestId: String, approved: Boolean) {
        pendingApprovals.remove(requestId)?.complete(approved)
        _toolApprovalRequest.value = null
    }

    private suspend fun requestToolApproval(request: ToolApprovalRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovals[request.id] = deferred
        _toolApprovalRequest.postValue(request)
        return deferred.await()
    }

    fun clearError() {
        _error.value = null
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    private fun newDefaultGraph(): MindGraph {
        val identity = MindNode(label = "My AI Mind", type = NodeType.IDENTITY, x = 400f, y = 300f)
        return MindGraph(
            name = "My AI Mind",
            nodes = mutableListOf(identity)
        )
    }

    private fun refreshAudit() {
        val current = _graph.value ?: return
        _auditResult.value = run_continuity_audit(current, acceptedFindingIds)
    }
}

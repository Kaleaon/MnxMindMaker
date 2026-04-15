package com.kaleaon.mnxmindmaker.ui.mindmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.continuity.ContinuityManager
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.tooling.ToolApprovalRequest
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.provider.runtime.LocalRuntimeCoordinator
import com.kaleaon.mnxmindmaker.util.provider.runtime.LocalRuntimeState
import com.kaleaon.mnxmindmaker.util.provider.runtime.RuntimeDiagnostic
import com.kaleaon.mnxmindmaker.util.run_continuity_audit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val localRuntimeCoordinator = LocalRuntimeCoordinator(scope = viewModelScope)

    private val _graph = MutableLiveData(newDefaultGraph())
    val graph: LiveData<MindGraph> get() = _graph

    private val _selectedNode = MutableLiveData<MindNode?>()
    val selectedNode: LiveData<MindNode?> get() = _selectedNode

    private val _exportedFile = MutableLiveData<File?>()
    val exportedFile: LiveData<File?> get() = _exportedFile

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _llmStatusBadge = MutableLiveData("REMOTE")
    val llmStatusBadge: LiveData<String> get() = _llmStatusBadge
    private val _localRuntimeState = MutableStateFlow<LocalRuntimeState>(
        LocalRuntimeState.Unreachable(
            RuntimeDiagnostic(
                summary = "Local runtime not ready",
                detail = "No local runtime startup has run.",
                suggestion = "Open Settings and run local runtime preflight."
            )
        )
    )
    val localRuntimeState: StateFlow<LocalRuntimeState> get() = _localRuntimeState.asStateFlow()

    private val _snapshotTimeline = MutableLiveData<List<ContinuityManager.SnapshotRecord>>(emptyList())
    val snapshotTimeline: LiveData<List<ContinuityManager.SnapshotRecord>> get() = _snapshotTimeline

    private val _snapshotActionMessage = MutableLiveData<String?>()
    val snapshotActionMessage: LiveData<String?> get() = _snapshotActionMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _auditResult = MutableLiveData<ContinuityAuditResult?>()
    val auditResult: LiveData<ContinuityAuditResult?> get() = _auditResult

    private val _chatMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val chatMessages: LiveData<List<ChatMessage>> get() = _chatMessages

    private val _isPremiumUser = MutableLiveData(true)
    val isPremiumUser: LiveData<Boolean> get() = _isPremiumUser

    private val _compareCandidateMessageId = MutableLiveData<String?>()
    val compareCandidateMessageId: LiveData<String?> get() = _compareCandidateMessageId

    private val interactionPolicy = MindMapInteractionPolicy()

    private val _pendingToolApprovalRequest = MutableLiveData<ToolApprovalRequest?>()
    val pendingToolApprovalRequest: LiveData<ToolApprovalRequest?> get() = _pendingToolApprovalRequest

    private val _lastToolApprovalResolution = MutableLiveData<ToolApprovalResolution?>()
    val lastToolApprovalResolution: LiveData<ToolApprovalResolution?> get() = _lastToolApprovalResolution

    private val _askAiEntryMode = MutableLiveData(interactionPolicy.askAiEntryMode)
    val askAiEntryMode: LiveData<AskAiEntryMode> get() = _askAiEntryMode

    private val acceptedFindingIds = mutableSetOf<String>()

    init {
        refreshAudit()
        refreshSnapshotTimeline()
        syncLocalRuntimeMonitoring()
        viewModelScope.launch {
            localRuntimeCoordinator.state.collect { state ->
                _localRuntimeState.value = state
            }
        }
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
        if (parentId != null) updatedEdges.add(MindEdge(fromNodeId = parentId, toNodeId = node.id))
        _graph.value = current.copy(nodes = updatedNodes, edges = updatedEdges)
        _selectedNode.value = node
        refreshAudit()
    }

    fun removeNode(nodeId: String) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.filter { it.id != nodeId }.toMutableList()
        val updatedEdges = current.edges.filter { it.fromNodeId != nodeId && it.toNodeId != nodeId }.toMutableList()
        _graph.value = current.copy(nodes = updatedNodes, edges = updatedEdges)
        if (_selectedNode.value?.id == nodeId) _selectedNode.value = null
        refreshAudit()
    }

    fun updateNodePosition(nodeId: String, x: Float, y: Float) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.map { if (it.id == nodeId) it.copy(x = x, y = y) else it }
        _graph.value = current.copy(nodes = updatedNodes.toMutableList())
    }

    fun selectNode(node: MindNode?) {
        _selectedNode.value = node
    }

    fun loadGraph(graph: MindGraph) {
        _graph.value = graph
        _selectedNode.value = null
        acceptedFindingIds.clear()
        refreshAudit()
    }

    fun runContinuityAudit() = refreshAudit()

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
                edges += MindEdge(fromNodeId = nodeId, toNodeId = correctiveNode.id, label = "corrects", strength = 0.85f)
            }
        }
        _graph.value = current.copy(nodes = current.nodes.toMutableList().also { it += correctiveNode }, edges = edges)
        acceptedFindingIds += findingId
        refreshAudit()
    }

    fun refreshSnapshotTimeline() {
        _snapshotTimeline.value = continuityManager.listSnapshots()
    }

    fun createSnapshot(reason: String, driftNotes: String = "") {
        val current = _graph.value ?: return
        val snapshot = continuityManager.createSnapshot(current, reason, driftNotes)
        refreshSnapshotTimeline()
        _snapshotActionMessage.value = "Snapshot ${snapshot.snapshotId.take(8)} created (${snapshot.reason})"
    }

    fun compareWithSnapshot(snapshotId: String) {
        val current = _graph.value ?: return
        val drift = continuityManager.compareSnapshotWithGraph(snapshotId, current)
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
                refreshAudit()
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
        val current = _graph.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val snapshot = continuityManager.createSnapshot(current, reason = "export_mnx")
                    val metadata = MnxRepository.ContinuityMetadata(
                        snapshotId = snapshot.snapshotId,
                        parentSnapshotId = snapshot.parentSnapshotId,
                        integrityHash = snapshot.integrityHash,
                        reason = snapshot.reason,
                        driftNotes = snapshot.driftNotes
                    )
                    mnxRepository.exportToMnx(current, metadata)
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
                val imported = withContext(Dispatchers.IO) { mnxRepository.importFromMnx(stream) }
                _graph.value = imported
                _selectedNode.value = null
                acceptedFindingIds.clear()
                refreshAudit()
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
                runCatching { stream.close() }
            }
        }
    }

    fun askLlmForMindDesign(prompt: String, choice: ComposerProviderChoice = ComposerProviderChoice.AUTO) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) { runSingleChat(prompt, choice) }
                if (result != null) {
                    _chatMessages.value = _chatMessages.value.orEmpty() + result
                }
            } catch (e: LlmApiException) {
                _llmStatusBadge.value = "REMOTE ERROR"
                _error.value = "AI error: ${e.message}"
            } catch (e: Exception) {
                _llmStatusBadge.value = "REMOTE ERROR"
                _error.value = "AI request failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun buildDataUseReport(prompt: String): String {
        val mode = llmRepository.loadPrivacyMode()
        val chain = llmRepository.getInvocationChain()
        val promptBytes = prompt.toByteArray().size
        return buildString {
            appendLine("Privacy mode: ${mode.name}")
            if (mode == PrivacyMode.STRICT_LOCAL_ONLY) appendLine("External calls blocked.")
            appendLine("Prompt bytes: $promptBytes")
            appendLine("---")
            if (chain.isEmpty()) {
                append("No usable provider configured.")
            } else {
                chain.forEachIndexed { idx, settings ->
                    appendLine("${idx + 1}. ${settings.provider.displayName}")
                    appendLine("   classification: ${settings.outboundClassification}")
                    appendLine("   destination: ${settings.baseUrl}")
                    appendLine("   data leaving device: system prompt + user prompt")
                }
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

                _chatMessages.value = _chatMessages.value.orEmpty().map { msg ->
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

    fun requestToolApproval(request: ToolApprovalRequest) {
        interactionPolicy.queueToolApproval(request)
        _pendingToolApprovalRequest.value = interactionPolicy.peekPendingToolApprovalRequest()
    }

    fun resolveToolApproval(requestId: String, approved: Boolean) {
        val resolution = interactionPolicy.resolveToolApproval(requestId, approved) ?: return
        _pendingToolApprovalRequest.value = interactionPolicy.peekPendingToolApprovalRequest()
        _lastToolApprovalResolution.value = resolution
    }

    fun clearToolApprovalResolution() {
        _lastToolApprovalResolution.value = null
    }

    private fun runSingleChat(
        prompt: String,
        choice: ComposerProviderChoice,
        forcedProvider: LlmProvider? = null
    ): ChatMessage? {
        syncLocalRuntimeMonitoring()
        val rawChain = when {
            forcedProvider != null -> listOfNotNull(loadUsableSettings(forcedProvider))
            choice == ComposerProviderChoice.AUTO -> llmRepository.getInvocationChain()
            else -> listOfNotNull(loadUsableSettings(choice.toProvider()))
        }
        val chain = rawChain.filter { isProviderReady(it, choice) }

        if (chain.isEmpty()) {
            val localState = localRuntimeState.value
            val reason = if (choice == ComposerProviderChoice.LOCAL || forcedProvider == LlmProvider.LOCAL_ON_DEVICE) {
                "Local runtime is not ready. ${localState.diagnostic.toUserMessage()}"
            } else {
                "No usable LLM configured. Add a provider in Settings."
            }
            _error.postValue(reason)
            return null
        }

        var lastError: String? = null
        for (settings in chain) {
            val systemPrompt = buildSystemPrompt(settings)
            val start = System.currentTimeMillis()
            try {
                val turn = llmClient.completeAssistantTurn(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    transcript = listOf(JSONObject().put("role", "user").put("content", prompt)),
                    tools = emptyList()
                )
                val latency = System.currentTimeMillis() - start
                _llmStatusBadge.postValue(
                    if (settings.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE) "LOCAL FALLBACK" else "REMOTE"
                )
                return ChatMessage(
                    id = UUID.randomUUID().toString(),
                    prompt = prompt,
                    response = turn.text,
                    providerChoice = choice,
                    provenance = MessageProvenance(
                        provider = settings.provider,
                        model = turn.raw?.optString("model").takeUnless { it.isNullOrBlank() } ?: settings.model,
                        toolCalls = turn.toolInvocations.map { it.toolName },
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

        _llmStatusBadge.postValue("REMOTE ERROR")
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
        return settings.takeIf { it.enabled && isUsable(it) }
    }

    private fun syncLocalRuntimeMonitoring() {
        val localSettings = llmRepository.loadSettings(LlmProvider.LOCAL_ON_DEVICE)
        if (localSettings.enabled && isUsable(localSettings)) {
            localRuntimeCoordinator.beginMonitoring(localSettings, llmRepository.loadPrivacyMode())
        } else {
            localRuntimeCoordinator.stopMonitoring(
                RuntimeDiagnostic(
                    summary = "Local runtime unavailable",
                    detail = "Local On-Device provider is disabled or invalid.",
                    suggestion = "Enable Local On-Device provider and set model path."
                )
            )
        }
    }

    private fun isProviderReady(settings: LlmSettings, choice: ComposerProviderChoice): Boolean {
        if (settings.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE) return true
        return when (val state = localRuntimeState.value) {
            is LocalRuntimeState.Healthy -> true
            is LocalRuntimeState.Initializing,
            is LocalRuntimeState.Degraded,
            is LocalRuntimeState.Unreachable -> {
                if (choice == ComposerProviderChoice.LOCAL) {
                    _error.postValue("Local runtime blocked request. ${state.diagnostic.toUserMessage()}")
                }
                false
            }
        }
    }

    private fun loadAllUsableSettings(): List<LlmSettings> {
        return llmRepository.loadAllSettings().filter { it.enabled && isUsable(it) }
    }

    private fun isUsable(settings: LlmSettings): Boolean {
        return when {
            settings.provider.requiresApiKey && settings.apiKey.isBlank() -> false
            settings.provider == LlmProvider.LOCAL_ON_DEVICE && settings.localModelPath.isBlank() -> false
            else -> true
        }
    }

    private fun pickAlternativeProvider(current: LlmProvider): LlmProvider? {
        val governedChain = llmRepository.getInvocationChain()
            .filter { isUsable(it) }
        val allUsable = loadAllUsableSettings()
        return MindMapProviderSelection.orderRetryCandidates(
            current = current,
            governedChain = governedChain,
            allUsable = allUsable
        ).firstOrNull()?.provider
    }

    private fun providerToChoice(provider: LlmProvider): ComposerProviderChoice = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE -> ComposerProviderChoice.LOCAL
        LlmProvider.ANTHROPIC -> ComposerProviderChoice.CLAUDE
        LlmProvider.OPENAI, LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> ComposerProviderChoice.CHATGPT
        LlmProvider.GEMINI -> ComposerProviderChoice.GEMINI
        LlmProvider.VLLM_GEMMA4 -> ComposerProviderChoice.VLLM
    }

    private fun ComposerProviderChoice.toProvider(): LlmProvider = when (this) {
        ComposerProviderChoice.AUTO -> LlmProvider.OPENAI
        ComposerProviderChoice.LOCAL -> LlmProvider.LOCAL_ON_DEVICE
        ComposerProviderChoice.CLAUDE -> LlmProvider.ANTHROPIC
        ComposerProviderChoice.CHATGPT -> LlmProvider.OPENAI
        ComposerProviderChoice.GEMINI -> LlmProvider.GEMINI
        ComposerProviderChoice.VLLM -> LlmProvider.VLLM_GEMMA4
    }

    private fun extractPromptTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        return raw.optJSONObject("usage")?.optInt("prompt_tokens")?.takeIf { it >= 0 }
            ?: raw.optJSONObject("usage")?.optInt("input_tokens")?.takeIf { it >= 0 }
            ?: raw.optJSONObject("usageMetadata")?.optInt("promptTokenCount")?.takeIf { it >= 0 }
    }

    private fun extractCompletionTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        return raw.optJSONObject("usage")?.optInt("completion_tokens")?.takeIf { it >= 0 }
            ?: raw.optJSONObject("usage")?.optInt("output_tokens")?.takeIf { it >= 0 }
            ?: raw.optJSONObject("usageMetadata")?.optInt("candidatesTokenCount")?.takeIf { it >= 0 }
    }

    private fun extractTotalTokens(raw: JSONObject?): Int? {
        if (raw == null) return null
        return raw.optJSONObject("usage")?.optInt("total_tokens")?.takeIf { it >= 0 }
            ?: raw.optJSONObject("usageMetadata")?.optInt("totalTokenCount")?.takeIf { it >= 0 }
    }

    private fun refreshAudit() {
        val current = _graph.value ?: return
        _auditResult.value = run_continuity_audit(current, acceptedFindingIds)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    private fun newDefaultGraph(): MindGraph {
        val identity = MindNode(
            label = "Core Identity",
            type = NodeType.IDENTITY,
            description = "Central identity anchor for this mind.",
            x = 360f,
            y = 220f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        return MindGraph(name = "New Mind", nodes = mutableListOf(identity), edges = mutableListOf())
    }
}

internal object MindMapProviderSelection {
    fun orderRetryCandidates(
        current: LlmProvider,
        governedChain: List<LlmSettings>,
        allUsable: List<LlmSettings>
    ): List<LlmSettings> {
        val chainIndex = governedChain
            .filter { it.provider != current }
            .mapIndexed { index, settings -> settings.provider to index }
            .toMap()
        val allIndex = allUsable
            .filter { it.provider != current }
            .mapIndexed { index, settings -> settings.provider to index }
            .toMap()

        return allUsable
            .asSequence()
            .filter { it.provider != current }
            .distinctBy { it.provider }
            .sortedWith(
                compareBy<LlmSettings>(
                    { chainIndex[it.provider] ?: Int.MAX_VALUE },
                    { allIndex[it.provider] ?: Int.MAX_VALUE },
                    { fallbackProviderRank(it.provider) }
                ).thenBy { it.provider.name }
            )
            .toList()
    }

    private fun fallbackProviderRank(provider: LlmProvider): Int = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE -> 0
        LlmProvider.VLLM_GEMMA4 -> 1
        LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> 2
        LlmProvider.GEMINI -> 3
        LlmProvider.OPENAI -> 4
        LlmProvider.ANTHROPIC -> 5
    }
}

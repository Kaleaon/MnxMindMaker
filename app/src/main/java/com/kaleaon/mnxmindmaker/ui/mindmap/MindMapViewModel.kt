package com.kaleaon.mnxmindmaker.ui.mindmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.continuity.ContinuityManager
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.run_continuity_audit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _llmResponse = MutableLiveData<String?>()
    val llmResponse: LiveData<String?> get() = _llmResponse

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
        refreshAudit()
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

    fun clearSnapshotActionMessage() { _snapshotActionMessage.value = null }

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
                    val external = settings.provider.baseUrl.startsWith("https://") && settings.provider != com.kaleaon.mnxmindmaker.model.LlmProvider.LOCAL_ON_DEVICE
                    appendLine("${idx + 1}. ${settings.provider.displayName}")
                    appendLine("   classification: ${settings.outboundClassification}")
                    appendLine("   destination: ${settings.baseUrl}")
                    appendLine("   data leaving device: system prompt + user prompt")
                    appendLine("   tls pinning: ${if (settings.tlsPinnedSpkiSha256.isNotBlank() && external) "enabled" else "not pinned"}")
                }
            }
        }
    }

    fun askLlmForMindDesign(prompt: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _llmResponse.value = null
            try {
                val invocationChain = withContext(Dispatchers.IO) { llmRepository.getInvocationChain() }
                if (invocationChain.isEmpty()) {
                    _error.value = "No usable provider configured under current privacy mode."
                    return@launch
                }

                var response: String? = null
                var lastError: String? = null
                for (settings in invocationChain) {
                    val caps = settings.capabilities
                    val systemPrompt = buildString {
                        appendLine("You are an AI mind design assistant helping the user build an AI mind graph in .mnx format.")
                        appendLine("The mind graph represents identity, memories, knowledge, emotions, personality, beliefs, values, and relationships.")
                        appendLine("Provide concise, structured suggestions for mind nodes and connections.")
                        appendLine("Format suggestions as: NodeType: label - description")
                        if (!caps.supportsToolPlanning) appendLine("Do not propose multi-step tool plans; provide direct node suggestions only.")
                        if (!caps.supportsPacketGeneration) appendLine("Keep output short and avoid dense packet-style dumps.")
                        appendLine("Stay within approximately ${caps.contextWindowTokens / 8} output tokens.")
                    }
                    try {
                        response = withContext(Dispatchers.IO) { llmClient.complete(settings, systemPrompt, prompt) }
                        break
                    } catch (inner: LlmApiException) {
                        lastError = "${settings.provider.displayName}: ${inner.message}"
                    }
                }

                if (response == null) {
                    _error.value = "AI request failed across fallback chain. $lastError"
                    return@launch
                }

                _llmResponse.value = response
            } catch (e: LlmApiException) {
                _error.value = "AI error: ${e.message}"
            } catch (e: Exception) {
                _error.value = "AI request failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearExportedFile() { _exportedFile.value = null }
    fun clearLlmResponse() { _llmResponse.value = null }

    private fun newDefaultGraph(): MindGraph {
        val identity = MindNode(label = "My AI Mind", type = NodeType.IDENTITY, x = 400f, y = 300f)
        return MindGraph(name = "My AI Mind", nodes = mutableListOf(identity))
    }

    private fun refreshAudit() {
        val current = _graph.value ?: return
        _auditResult.value = run_continuity_audit(current, acceptedFindingIds)
    }
}

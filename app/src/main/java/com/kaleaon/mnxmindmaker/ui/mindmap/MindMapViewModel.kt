package com.kaleaon.mnxmindmaker.ui.mindmap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.tooling.ToolApprovalRequest
import com.kaleaon.mnxmindmaker.util.tooling.ToolOrchestrator
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult
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

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> get() = _isLoading
    private val _auditResult = MutableLiveData<ContinuityAuditResult?>()
    val auditResult: LiveData<ContinuityAuditResult?> get() = _auditResult
    private val acceptedFindingIds = mutableSetOf<String>()

    init {
        refreshAudit()
    }

    private val _toolApprovalRequest = MutableLiveData<ToolApprovalRequest?>()
    val toolApprovalRequest: LiveData<ToolApprovalRequest?> get() = _toolApprovalRequest

    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()

    // ---- Graph editing -------------------------------------------------------

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

    // ---- MNX Export / Import ------------------------------------------------

    fun exportToMnx() {
        val graph = _graph.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val file = withContext(Dispatchers.IO) { mnxRepository.exportToMnx(graph) }
                _exportedFile.value = file
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

    // ---- LLM AI Assistance --------------------------------------------------

    fun askLlmForMindDesign(prompt: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _llmResponse.value = null
            try {
                val activeSettings = withContext(Dispatchers.IO) {
                    llmRepository.getActiveProvider()?.let { llmRepository.loadSettings(it) }
                }
                if (activeSettings == null) {
                    _error.value = "No LLM API configured. Go to Settings to add an API key."
                    return@launch
                }
                val systemPrompt = """You are an AI mind design assistant helping the user build an AI mind graph
                    |in .mnx format. The mind graph represents an AI's identity, memories, knowledge,
                    |emotions, personality, beliefs, values, and relationships.
                    |Use tools whenever graph state inspection or mutation is needed.
                    |When tool use is unnecessary, provide concise, structured suggestions.
                    |Format suggestions as: NodeType: label - description""".trimMargin()

                val response = withContext(Dispatchers.IO) {
                    val registry = ToolRegistry(
                        getGraph = { _graph.value ?: newDefaultGraph() },
                        setGraph = { updated -> _graph.postValue(updated) }
                    )
                    val orchestrator = ToolOrchestrator(
                        llmApiClient = llmClient,
                        settings = activeSettings,
                        registry = registry,
                        policy = ToolPolicyEngine(registry),
                        requestApproval = { requestToolApproval(it) }
                    )
                    orchestrator.run(systemPrompt, prompt)
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

    fun clearError() { _error.value = null }
    fun clearExportedFile() { _exportedFile.value = null }
    fun clearLlmResponse() { _llmResponse.value = null }

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

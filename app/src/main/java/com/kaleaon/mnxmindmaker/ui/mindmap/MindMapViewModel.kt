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
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import com.kaleaon.mnxmindmaker.util.LlmApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

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

    init {
        refreshSnapshotTimeline()
    }

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
    }

    fun removeNode(nodeId: String) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.filter { it.id != nodeId }.toMutableList()
        val updatedEdges = current.edges
            .filter { it.fromNodeId != nodeId && it.toNodeId != nodeId }
            .toMutableList()
        _graph.value = current.copy(nodes = updatedNodes, edges = updatedEdges)
        if (_selectedNode.value?.id == nodeId) _selectedNode.value = null
    }

    fun updateNodePosition(nodeId: String, x: Float, y: Float) {
        val current = _graph.value ?: return
        val updatedNodes = current.nodes.map { if (it.id == nodeId) it.copy(x = x, y = y) else it }
        _graph.value = current.copy(nodes = updatedNodes.toMutableList())
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


    // ---- MNX Export / Import ------------------------------------------------

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
                    |Provide concise, structured suggestions for mind nodes and connections.
                    |Format suggestions as: NodeType: label - description""".trimMargin()
                val response = withContext(Dispatchers.IO) {
                    llmClient.complete(activeSettings, systemPrompt, prompt)
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
        return MindGraph(
            name = "My AI Mind",
            nodes = mutableListOf(identity)
        )
    }
}

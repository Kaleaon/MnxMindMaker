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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

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
                val invocationChain = withContext(Dispatchers.IO) {
                    llmRepository.getInvocationChain()
                }
                if (invocationChain.isEmpty()) {
                    _error.value = "No usable LLM configured. Add a provider in Settings."
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
                        if (!caps.supportsToolPlanning) {
                            appendLine("Do not propose multi-step tool plans; provide direct node suggestions only.")
                        }
                        if (!caps.supportsPacketGeneration) {
                            appendLine("Keep output short and avoid dense packet-style dumps.")
                        }
                        appendLine("Stay within approximately ${caps.contextWindowTokens / 8} output tokens.")
                    }
                    try {
                        response = withContext(Dispatchers.IO) {
                            llmClient.complete(settings, systemPrompt, prompt)
                        }
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
        return MindGraph(
            name = "My AI Mind",
            nodes = mutableListOf(identity)
        )
    }
}

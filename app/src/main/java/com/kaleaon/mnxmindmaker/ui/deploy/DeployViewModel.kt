package com.kaleaon.mnxmindmaker.ui.deploy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.DeploymentRepository
import com.kaleaon.mnxmindmaker.util.provider.ValidationSeverity
import com.kaleaon.mnxmindmaker.util.provider.validate
import com.kaleaon.mnxmindmaker.util.provider.validateRuntimeEndpoint
import com.kaleaon.mnxmindmaker.util.run_continuity_audit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DeployViewModel(application: Application) : AndroidViewModel(application) {

    private val deploymentRepository = DeploymentRepository(application)
    private val settingsRepository = LlmSettingsRepository(application)
    private val deploymentId = UUID.randomUUID().toString()

    private val _uiState = MutableLiveData(DeployUiState())
    val uiState: LiveData<DeployUiState> = _uiState

    init {
        refreshFromSources()
    }

    fun refreshFromSources() {
        val graph = DeploymentSessionState.currentGraph
        val audit = DeploymentSessionState.currentAudit ?: graph?.let { run_continuity_audit(it) }
        _uiState.value = _uiState.value?.copy(graph = graph, audit = audit)
        updateManifestPreview()
    }

    fun updateRuntimeConfig(environment: String, endpoint: String, releaseChannel: String, notes: String) {
        _uiState.value = _uiState.value?.copy(
            runtimeConfig = DeploymentRuntimeConfig(
                environment = environment,
                endpoint = endpoint,
                releaseChannel = releaseChannel,
                notes = notes
            )
        )
        updateManifestPreview()
    }

    fun goToNextStep() {
        val state = _uiState.value ?: return
        if (state.currentStep == DeployWizardStep.RUNTIME) {
            val runtimeIssues = validateRuntimeEndpoint(state.runtimeConfig.endpoint)
            val providerIssues = settingsRepository
                .loadAllSettings()
                .filter { it.enabled }
                .flatMap { validate(it, settingsRepository.loadPrivacyMode()) }
            val criticalIssues = (runtimeIssues + providerIssues).filter { it.severity == ValidationSeverity.CRITICAL }
            if (criticalIssues.isNotEmpty()) {
                _uiState.value = state.copy(
                    errorMessage = "Cannot continue: ${criticalIssues.first().message}"
                )
                return
            }
        }
        val next = when (state.currentStep) {
            DeployWizardStep.GRAPH -> DeployWizardStep.VALIDATION
            DeployWizardStep.VALIDATION -> DeployWizardStep.RUNTIME
            DeployWizardStep.RUNTIME -> DeployWizardStep.SUMMARY
            DeployWizardStep.SUMMARY -> DeployWizardStep.SUMMARY
        }
        _uiState.value = state.copy(currentStep = next)
    }

    fun goToPreviousStep() {
        val state = _uiState.value ?: return
        val previous = when (state.currentStep) {
            DeployWizardStep.GRAPH -> DeployWizardStep.GRAPH
            DeployWizardStep.VALIDATION -> DeployWizardStep.GRAPH
            DeployWizardStep.RUNTIME -> DeployWizardStep.VALIDATION
            DeployWizardStep.SUMMARY -> DeployWizardStep.RUNTIME
        }
        _uiState.value = state.copy(currentStep = previous)
    }

    fun confirmDeployment() {
        val state = _uiState.value ?: return
        val graph = state.graph ?: run {
            _uiState.value = state.copy(errorMessage = "No graph is currently available from Mind Map state.")
            return
        }

        val audit = state.audit ?: run_continuity_audit(graph)
        val manifest = buildManifest(graph, audit, state.runtimeConfig)

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, errorMessage = null, confirmationMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    deploymentRepository.persistLifecycleState(
                        DeploymentLifecycleState(
                            deploymentId = deploymentId,
                            stage = "confirm",
                            status = "persisted",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    deploymentRepository.persistManifest(manifest)
                }
                _uiState.value = _uiState.value?.copy(
                    isSaving = false,
                    manifestPreview = manifest,
                    confirmationMessage = "Deployment manifest and lifecycle state were persisted."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value?.copy(
                    isSaving = false,
                    errorMessage = "Failed to persist deployment: ${e.message}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value?.copy(errorMessage = null, confirmationMessage = null)
    }

    private fun updateManifestPreview() {
        val state = _uiState.value ?: return
        val graph = state.graph ?: return
        val audit = state.audit ?: run_continuity_audit(graph)
        _uiState.value = state.copy(manifestPreview = buildManifest(graph, audit, state.runtimeConfig), audit = audit)
    }

    private fun buildManifest(
        graph: com.kaleaon.mnxmindmaker.model.MindGraph,
        audit: com.kaleaon.mnxmindmaker.util.ContinuityAuditResult,
        runtimeConfig: DeploymentRuntimeConfig
    ): DeploymentManifest {
        val summary = "${graph.name}: ${graph.nodes.size} nodes / ${graph.edges.size} edges, ${audit.summary.totalFindings} findings"
        return DeploymentManifest(
            deploymentId = deploymentId,
            createdAt = System.currentTimeMillis(),
            graphId = graph.id,
            graphName = graph.name,
            nodeCount = graph.nodes.size,
            edgeCount = graph.edges.size,
            findingCount = audit.summary.totalFindings,
            criticalFindingCount = audit.summary.criticalCount,
            runtimeConfig = runtimeConfig,
            summary = summary
        )
    }
}

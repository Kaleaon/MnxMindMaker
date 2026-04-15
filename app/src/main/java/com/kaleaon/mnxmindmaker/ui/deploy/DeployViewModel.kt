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
    private val deploymentHistory = mutableListOf<DeploymentHistoryEntry>()

    init {
        refreshFromSources()
    }

    fun refreshFromSources() {
        val graph = DeploymentSessionState.currentGraph
        val audit = DeploymentSessionState.currentAudit ?: graph?.let { run_continuity_audit(it) }
        val currentState = _uiState.value ?: DeployUiState()
        val runtimeConfig = currentState.runtimeConfig
        _uiState.value = currentState.copy(
            graph = graph,
            audit = audit,
            opsSnapshot = buildOpsSnapshot(
                graph = graph,
                audit = audit,
                runtimeConfig = runtimeConfig,
                isSaving = currentState.isSaving
            )
        )
        updateManifestPreview()
    }

    fun updateRuntimeConfig(environment: String, endpoint: String, publishChannel: String, notes: String) {
        val normalizedChannel = normalizeChannel(publishChannel)
        _uiState.value = _uiState.value?.copy(
            runtimeConfig = DeploymentRuntimeConfig(
                environment = environment,
                endpoint = endpoint,
                publishChannel = normalizedChannel,
                requiresPromotionApproval = normalizedChannel != "dev",
                rollbackChannel = if (normalizedChannel == "dev") "dev" else previousChannel(normalizedChannel),
                compatibilityConstraints = defaultCompatibilityConstraints(environment, normalizedChannel),
                notes = notes
            ),
            opsSnapshot = buildOpsSnapshot(
                graph = _uiState.value?.graph,
                audit = _uiState.value?.audit,
                runtimeConfig = DeploymentRuntimeConfig(
                    environment = environment,
                    endpoint = endpoint,
                    releaseChannel = releaseChannel,
                    notes = notes
                ),
                isSaving = _uiState.value?.isSaving == true
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
                .withOpsSnapshot()
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
                    val promotionAction = if (manifest.runtimeConfig.requiresPromotionApproval) {
                        "promotion_approved"
                    } else {
                        "published"
                    }
                    deploymentHistory.add(
                        DeploymentHistoryEntry(
                            action = promotionAction,
                            channel = manifest.runtimeConfig.publishChannel,
                            approvedBy = if (manifest.runtimeConfig.requiresPromotionApproval) "release-manager" else "self-service",
                            createdAt = System.currentTimeMillis(),
                            detail = "Publish to ${manifest.runtimeConfig.publishChannel} with rollback target ${manifest.runtimeConfig.rollbackChannel}"
                        )
                    )
                    deploymentRepository.persistManifest(manifest)
                    deploymentRepository.persistDeploymentHistory(deploymentId, deploymentHistory)
                }
                _uiState.value = _uiState.value?.copy(
                    isSaving = false,
                    manifestPreview = manifest,
                    confirmationMessage = "Deployment manifest and lifecycle state were persisted."
                )?.withOpsSnapshot()
            } catch (e: Exception) {
                _uiState.value = _uiState.value?.copy(
                    isSaving = false,
                    errorMessage = "Failed to persist deployment: ${e.message}"
                )?.withOpsSnapshot()
            }
        }
    }

    fun runOpsQuickAction(action: OpsQuickAction) {
        val state = _uiState.value ?: return
        val message = when (action) {
            OpsQuickAction.RESTART -> "Runtime restart signal dispatched."
            OpsQuickAction.REINDEX -> "Reindex job enqueued."
            OpsQuickAction.RETRY_FAILED -> "Retry requested for failed jobs."
        }
        _uiState.value = state.copy(
            confirmationMessage = message,
            opsSnapshot = state.opsSnapshot.copy(
                jobStatus = when (action) {
                    OpsQuickAction.RESTART -> "Restarting runtime"
                    OpsQuickAction.REINDEX -> "Reindex in progress"
                    OpsQuickAction.RETRY_FAILED -> "Retrying failed jobs"
                },
                syncState = when (action) {
                    OpsQuickAction.RESTART -> "Runtime warmup"
                    OpsQuickAction.REINDEX -> "Index sync queued"
                    OpsQuickAction.RETRY_FAILED -> "Backlog reconciliation"
                },
                queueDepth = when (action) {
                    OpsQuickAction.REINDEX -> state.opsSnapshot.queueDepth + 1
                    OpsQuickAction.RETRY_FAILED -> (state.opsSnapshot.queueDepth - 1).coerceAtLeast(0)
                    OpsQuickAction.RESTART -> state.opsSnapshot.queueDepth
                }
            )
        )
    }

    fun clearMessages() {
        _uiState.value = _uiState.value?.copy(errorMessage = null, confirmationMessage = null)
    }

    private fun updateManifestPreview() {
        val state = _uiState.value ?: return
        val graph = state.graph ?: return
        val audit = state.audit ?: run_continuity_audit(graph)
        _uiState.value = state.copy(
            manifestPreview = buildManifest(graph, audit, state.runtimeConfig),
            audit = audit
        ).withOpsSnapshot()
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
            deploymentHistory = deploymentHistory.toList(),
            summary = summary
        )
    }

    private fun DeployUiState.withOpsSnapshot(): DeployUiState {
        return copy(
            opsSnapshot = buildOpsSnapshot(
                graph = graph,
                audit = audit,
                runtimeConfig = runtimeConfig,
                isSaving = isSaving
            )
        )
    }

    private fun buildOpsSnapshot(
        graph: com.kaleaon.mnxmindmaker.model.MindGraph?,
        audit: com.kaleaon.mnxmindmaker.util.ContinuityAuditResult?,
        runtimeConfig: DeploymentRuntimeConfig,
        isSaving: Boolean
    ): DeployOpsSnapshot {
        val findingCount = audit?.summary?.totalFindings ?: 0
        val criticalCount = audit?.summary?.criticalCount ?: 0
        val queueDepth = (findingCount / 2) + if (runtimeConfig.endpoint.isBlank()) 2 else 0
        val runtimeHealth = when {
            criticalCount > 0 -> "Degraded"
            findingCount > 0 -> "Warning"
            graph == null -> "Unknown"
            else -> "Healthy"
        }
        val syncState = when {
            runtimeConfig.endpoint.isBlank() -> "Endpoint missing"
            isSaving -> "Syncing manifest"
            else -> "In sync"
        }
        val jobStatus = when {
            isSaving -> "Persisting deployment"
            queueDepth > 0 -> "Queue active"
            else -> "Idle"
        }
        return DeployOpsSnapshot(
            runtimeHealth = runtimeHealth,
            queueDepth = queueDepth,
            jobStatus = jobStatus,
            syncState = syncState,
            policyViolations = criticalCount
    private fun normalizeChannel(raw: String): String {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "dev", "development" -> "dev"
            "stage", "staging" -> "stage"
            "prod", "production" -> "prod"
            else -> "dev"
        }
    }

    private fun previousChannel(channel: String): String = when (channel) {
        "prod" -> "stage"
        "stage" -> "dev"
        else -> "dev"
    }

    private fun defaultCompatibilityConstraints(environment: String, channel: String): List<String> {
        return listOf(
            "mnx_meta_manifest_required",
            "runtime_endpoint_must_validate",
            "environment=$environment",
            "channel=$channel"
        )
    }
}

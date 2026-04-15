package com.kaleaon.mnxmindmaker.ui.deploy

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult

enum class DeployWizardStep {
    GRAPH,
    VALIDATION,
    RUNTIME,
    SUMMARY
}

data class DeploymentRuntimeConfig(
    val environment: String = "staging",
    val endpoint: String = "",
    val releaseChannel: String = "canary",
    val notes: String = ""
)

data class DeploymentLifecycleState(
    val deploymentId: String,
    val stage: String,
    val status: String,
    val updatedAt: Long
)

data class DeploymentManifest(
    val deploymentId: String,
    val createdAt: Long,
    val graphId: String,
    val graphName: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val findingCount: Int,
    val criticalFindingCount: Int,
    val runtimeConfig: DeploymentRuntimeConfig,
    val summary: String
)

enum class OpsQuickAction {
    RESTART,
    REINDEX,
    RETRY_FAILED
}

data class DeployOpsSnapshot(
    val runtimeHealth: String = "Unknown",
    val queueDepth: Int = 0,
    val jobStatus: String = "Idle",
    val syncState: String = "Not started",
    val policyViolations: Int = 0
)

data class DeployUiState(
    val currentStep: DeployWizardStep = DeployWizardStep.GRAPH,
    val graph: MindGraph? = null,
    val audit: ContinuityAuditResult? = null,
    val runtimeConfig: DeploymentRuntimeConfig = DeploymentRuntimeConfig(),
    val opsSnapshot: DeployOpsSnapshot = DeployOpsSnapshot(),
    val manifestPreview: DeploymentManifest? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val confirmationMessage: String? = null
)

object DeploymentSessionState {
    var currentGraph: MindGraph? = null
    var currentAudit: ContinuityAuditResult? = null
}

package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.ui.deploy.DeploymentLifecycleState
import com.kaleaon.mnxmindmaker.ui.deploy.DeploymentHistoryEntry
import com.kaleaon.mnxmindmaker.ui.deploy.DeploymentManifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DeploymentRepository(context: Context) {

    private val deployDir = File(context.filesDir, "deployments").also { it.mkdirs() }

    fun persistLifecycleState(state: DeploymentLifecycleState): File {
        val file = File(deployDir, "${state.deploymentId}_lifecycle.json")
        val payload = JSONObject()
            .put("deployment_id", state.deploymentId)
            .put("stage", state.stage)
            .put("status", state.status)
            .put("updated_at", state.updatedAt)
        file.writeText(payload.toString(2))
        return file
    }

    fun persistManifest(manifest: DeploymentManifest): File {
        val runtime = JSONObject()
            .put("environment", manifest.runtimeConfig.environment)
            .put("endpoint", manifest.runtimeConfig.endpoint)
            .put("publish_channel", manifest.runtimeConfig.publishChannel)
            .put("requires_promotion_approval", manifest.runtimeConfig.requiresPromotionApproval)
            .put("rollback_channel", manifest.runtimeConfig.rollbackChannel)
            .put("compatibility_constraints", JSONArray(manifest.runtimeConfig.compatibilityConstraints))
            .put("notes", manifest.runtimeConfig.notes)

        val history = JSONArray().apply {
            manifest.deploymentHistory.forEach { entry ->
                put(
                    JSONObject()
                        .put("action", entry.action)
                        .put("channel", entry.channel)
                        .put("approved_by", entry.approvedBy)
                        .put("created_at", entry.createdAt)
                        .put("detail", entry.detail)
                )
            }
        }

        val payload = JSONObject()
            .put("deployment_id", manifest.deploymentId)
            .put("created_at", manifest.createdAt)
            .put("graph_id", manifest.graphId)
            .put("graph_name", manifest.graphName)
            .put("node_count", manifest.nodeCount)
            .put("edge_count", manifest.edgeCount)
            .put("finding_count", manifest.findingCount)
            .put("critical_finding_count", manifest.criticalFindingCount)
            .put("summary", manifest.summary)
            .put("deployment_history", history)
            .put("runtime_config", runtime)

        val file = File(deployDir, "${manifest.deploymentId}_manifest.json")
        file.writeText(payload.toString(2))
        return file
    }

    fun persistDeploymentHistory(deploymentId: String, historyEntries: List<DeploymentHistoryEntry>): File {
        val payload = JSONArray().apply {
            historyEntries.forEach { entry ->
                put(
                    JSONObject()
                        .put("deployment_id", deploymentId)
                        .put("action", entry.action)
                        .put("channel", entry.channel)
                        .put("approved_by", entry.approvedBy)
                        .put("created_at", entry.createdAt)
                        .put("detail", entry.detail)
                )
            }
        }

        val file = File(deployDir, "${deploymentId}_history.json")
        file.writeText(payload.toString(2))
        return file
    }
}

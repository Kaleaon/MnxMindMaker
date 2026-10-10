package com.kaleaon.mnxmindmaker.util.drive

import com.kaleaon.mnxmindmaker.model.MindGraph
import java.io.PrintWriter
import java.io.StringWriter

enum class DriveSyncErrorKind {
    SESSION_EXPIRED,   // HTTP 401
    CONFLICT,          // HTTP 412 Precondition Failed / remote file modified
    NETWORK_ERROR,     // IO / Network transport exception
    GENERIC_SYNC_ERROR // Other HTTP codes
}

data class DriveSyncError(
    val kind: DriveSyncErrorKind,
    val statusCode: Int?,
    val title: String,
    val description: String,
    val troubleshootingTip: String,
    val rawTrace: String
)

sealed class DriveSyncResult {
    data class Success(val remoteVersionToken: String, val syncedGraph: MindGraph) : DriveSyncResult()
    data class Error(val syncError: DriveSyncError) : DriveSyncResult()
}

class DriveSyncClient {
    private var simulatedRemoteVersionToken: String = "v1.0"
    private var simulatedRemoteGraph: MindGraph? = null
    private var simulatedSessionValid: Boolean = true
    private var simulatedNetworkHealthy: Boolean = true
    private var simulatedConflictState: Boolean = false

    fun setSimulatedState(
        sessionValid: Boolean = true,
        networkHealthy: Boolean = true,
        conflictState: Boolean = false,
        remoteToken: String = "v1.0",
        remoteGraph: MindGraph? = null
    ) {
        simulatedSessionValid = sessionValid
        simulatedNetworkHealthy = networkHealthy
        simulatedConflictState = conflictState
        simulatedRemoteVersionToken = remoteToken
        if (remoteGraph != null) {
            simulatedRemoteGraph = remoteGraph
        }
    }

    fun syncGraph(localGraph: MindGraph, clientVersionToken: String?): DriveSyncResult {
        if (!simulatedSessionValid) {
            return DriveSyncResult.Error(classifyError(statusCode = 401, exception = null, rawDetail = "401 Unauthorized: Drive session token expired"))
        }
        if (!simulatedNetworkHealthy) {
            return DriveSyncResult.Error(classifyError(statusCode = null, exception = java.net.ConnectException("Unable to connect to Google Drive API"), rawDetail = null))
        }
        if (simulatedConflictState || (clientVersionToken != null && clientVersionToken != simulatedRemoteVersionToken)) {
            return DriveSyncResult.Error(classifyError(statusCode = 412, exception = null, rawDetail = "412 Precondition Failed: Remote file has version token '$simulatedRemoteVersionToken' but client provided '${clientVersionToken ?: "none"}'"))
        }

        val newVersionToken = "v" + System.currentTimeMillis()
        simulatedRemoteVersionToken = newVersionToken
        simulatedRemoteGraph = localGraph
        return DriveSyncResult.Success(remoteVersionToken = newVersionToken, syncedGraph = localGraph)
    }

    fun forceOverwrite(localGraph: MindGraph): DriveSyncResult {
        simulatedConflictState = false
        val newVersionToken = "v" + System.currentTimeMillis()
        simulatedRemoteVersionToken = newVersionToken
        simulatedRemoteGraph = localGraph
        return DriveSyncResult.Success(remoteVersionToken = newVersionToken, syncedGraph = localGraph)
    }

    fun reloadRemote(): DriveSyncResult {
        simulatedConflictState = false
        val remote = simulatedRemoteGraph ?: MindGraph(name = "Remote Drive Mind")
        return DriveSyncResult.Success(remoteVersionToken = simulatedRemoteVersionToken, syncedGraph = remote)
    }

    fun reauthenticate(): Boolean {
        simulatedSessionValid = true
        return true
    }

    companion object {
        fun classifyError(statusCode: Int?, exception: Exception?, rawDetail: String? = null): DriveSyncError {
            val sw = StringWriter()
            exception?.printStackTrace(PrintWriter(sw))
            val trace = if (exception != null) sw.toString() else rawDetail.orEmpty()

            return when {
                statusCode == 401 -> DriveSyncError(
                    kind = DriveSyncErrorKind.SESSION_EXPIRED,
                    statusCode = 401,
                    title = "Drive Session Expired (401)",
                    description = "Your Google Drive authorization token has expired or is invalid.",
                    troubleshootingTip = "Re-authenticate to restore cloud storage sync.",
                    rawTrace = trace.ifBlank { "HTTP 401 Unauthorized" }
                )
                statusCode == 412 -> DriveSyncError(
                    kind = DriveSyncErrorKind.CONFLICT,
                    statusCode = 412,
                    title = "Cloud Storage Conflict (412)",
                    description = "The remote mind map file in Google Drive was modified in another session or device.",
                    troubleshootingTip = "Choose a recovery action below to resolve the sync divergence.",
                    rawTrace = trace.ifBlank { "HTTP 412 Precondition Failed" }
                )
                exception != null -> DriveSyncError(
                    kind = DriveSyncErrorKind.NETWORK_ERROR,
                    statusCode = statusCode,
                    title = "Drive Sync Network Failure",
                    description = "Unable to reach Google Drive servers due to network disruption.",
                    troubleshootingTip = "Check your internet connection and tap Retry Sync.",
                    rawTrace = trace
                )
                else -> DriveSyncError(
                    kind = DriveSyncErrorKind.GENERIC_SYNC_ERROR,
                    statusCode = statusCode,
                    title = "Drive Sync Failure (${statusCode ?: "n/a"})",
                    description = "An unexpected cloud storage sync error occurred.",
                    troubleshootingTip = "Review technical details or retry the sync operation.",
                    rawTrace = trace.ifBlank { "Status code: $statusCode" }
                )
            }
        }
    }
}

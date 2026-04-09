package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.util.tooling.ToolApprovalRequest

enum class AskAiEntryMode {
    CHAT_PANEL,
    DATA_USE_PANEL
}

data class ToolApprovalResolution(
    val requestId: String,
    val approved: Boolean
)

class MindMapInteractionPolicy(
    val askAiEntryMode: AskAiEntryMode = AskAiEntryMode.DATA_USE_PANEL
) {

    private var pendingToolApprovalRequest: ToolApprovalRequest? = null

    fun queueToolApproval(request: ToolApprovalRequest) {
        pendingToolApprovalRequest = request
    }

    fun peekPendingToolApprovalRequest(): ToolApprovalRequest? = pendingToolApprovalRequest

    fun resolveToolApproval(requestId: String, approved: Boolean): ToolApprovalResolution? {
        val current = pendingToolApprovalRequest ?: return null
        if (current.id != requestId) return null
        pendingToolApprovalRequest = null
        return ToolApprovalResolution(requestId = requestId, approved = approved)
    }
}

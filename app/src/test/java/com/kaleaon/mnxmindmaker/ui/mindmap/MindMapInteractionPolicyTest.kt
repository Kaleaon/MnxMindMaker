package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.util.tooling.ToolApprovalRequest
import com.kaleaon.mnxmindmaker.util.tooling.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapInteractionPolicyTest {

    @Test
    fun defaultAskAiEntryMode_isDataUsePanel() {
        val policy = MindMapInteractionPolicy()

        assertEquals(AskAiEntryMode.DATA_USE_PANEL, policy.askAiEntryMode)
    }

    @Test
    fun queueAndResolveApproval_clearsPendingAndReturnsResolution() {
        val policy = MindMapInteractionPolicy()
        val request = createRequest(id = "req-1")

        policy.queueToolApproval(request)
        val resolution = policy.resolveToolApproval(requestId = "req-1", approved = true)

        assertNull(policy.peekPendingToolApprovalRequest())
        assertEquals("req-1", resolution?.requestId)
        assertTrue(resolution?.approved == true)
    }

    @Test
    fun resolveApproval_withDifferentId_keepsPendingRequest() {
        val policy = MindMapInteractionPolicy()
        val request = createRequest(id = "req-1")
        policy.queueToolApproval(request)

        val resolution = policy.resolveToolApproval(requestId = "req-2", approved = false)

        assertNull(resolution)
        assertEquals(request, policy.peekPendingToolApprovalRequest())
    }

    private fun createRequest(id: String): ToolApprovalRequest = ToolApprovalRequest(
        id = id,
        toolName = "notes.create",
        arguments = "{\"title\":\"Review PR\"}",
        reason = "Create a project task",
        riskLevel = ToolRiskLevel.MEDIUM,
        requiresConfirmation = true,
        explicitActionType = null
    )
}

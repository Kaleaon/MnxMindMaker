package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import org.json.JSONObject

/**
 * Executes tool invocations with policy checks, audit transcript recording,
 * and transaction-style rollback when a mutation sequence fails.
 */
class ToolExecutionEngine(
    private val policyEngine: ToolPolicyEngine,
    private val handlers: Map<String, ToolHandler>,
    private val transcriptRecorder: ToolTranscriptRecorder
) {

    fun executeSequence(
        runId: String,
        invocations: List<ToolInvocation>,
        graph: MindGraph,
        policyContext: ToolPolicyContext = ToolPolicyContext(),
        approve: (ToolInvocation, PolicyDecision) -> Boolean
    ): MutationSequenceResult {
        var workingGraph = graph.deepCopy()
        val preMutationSnapshot = workingGraph.deepCopy()

        val results = mutableListOf<ToolResult>()
        var sawMutation = false
        var rolledBack = false

        for (invocation in invocations) {
            transcriptRecorder.recordRequest(runId, invocation)

            val decision = policyEngine.evaluate(invocation, workingGraph, policyContext)
            transcriptRecorder.recordDecision(runId, invocation, decision)

            if (decision.type == PolicyDecisionType.DENY) {
                val denied = ToolResult(
                    toolUseId = invocation.id,
                    isError = true,
                    contentJson = JSONObject()
                        .put("error", "policy_denied")
                        .put("message", decision.reason)
                )
                val withRollback = denied.copy(rolledBack = sawMutation)
                transcriptRecorder.recordResult(runId, invocation, withRollback)
                results += withRollback
                if (sawMutation) {
                    workingGraph = preMutationSnapshot.deepCopy()
                    rolledBack = true
                }
                break
            }

            if (decision.type == PolicyDecisionType.REQUIRE_USER_APPROVAL && !approve(invocation, decision)) {
                val rejected = ToolResult(
                    toolUseId = invocation.id,
                    isError = true,
                    contentJson = JSONObject()
                        .put("error", "approval_rejected")
                        .put("message", "User rejected sensitive operation")
                )
                val withRollback = rejected.copy(rolledBack = sawMutation)
                transcriptRecorder.recordResult(runId, invocation, withRollback)
                results += withRollback
                if (sawMutation) {
                    workingGraph = preMutationSnapshot.deepCopy()
                    rolledBack = true
                }
                break
            }

            val handler = handlers[invocation.toolName]
            if (handler == null) {
                val unknown = ToolResult(
                    toolUseId = invocation.id,
                    isError = true,
                    contentJson = JSONObject()
                        .put("error", "handler_not_found")
                        .put("message", "No handler registered for ${invocation.toolName}")
                )
                val withRollback = unknown.copy(rolledBack = sawMutation)
                transcriptRecorder.recordResult(runId, invocation, withRollback)
                results += withRollback
                if (sawMutation) {
                    workingGraph = preMutationSnapshot.deepCopy()
                    rolledBack = true
                }
                break
            }

            try {
                val outcome = handler.execute(invocation, workingGraph)
                if (outcome.mutatedGraph) sawMutation = true

                val ok = ToolResult(
                    toolUseId = invocation.id,
                    isError = false,
                    contentJson = outcome.contentJson
                )
                transcriptRecorder.recordResult(runId, invocation, ok)
                results += ok
            } catch (e: Exception) {
                val error = ToolResult(
                    toolUseId = invocation.id,
                    isError = true,
                    contentJson = JSONObject()
                        .put("error", "tool_execution_failed")
                        .put("message", e.message ?: "Unknown execution error"),
                    rolledBack = sawMutation
                )
                transcriptRecorder.recordResult(runId, invocation, error)
                results += error
                if (sawMutation) {
                    workingGraph = preMutationSnapshot.deepCopy()
                    rolledBack = true
                }
                break
            }
        }

        return MutationSequenceResult(
            graph = workingGraph,
            toolResults = results,
            rolledBack = rolledBack
        )
    }
}

private fun MindGraph.deepCopy(): MindGraph {
    val copiedNodes = nodes.map { node ->
        node.copy(
            attributes = node.attributes.toMutableMap(),
            dimensions = node.dimensions.toMap()
        )
    }.toMutableList()

    val copiedEdges = edges.map { edge: MindEdge -> edge.copy() }.toMutableList()

    return copy(
        nodes = copiedNodes,
        edges = copiedEdges
    )
}

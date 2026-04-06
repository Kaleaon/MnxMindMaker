package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.tooling.PolicyDecisionType
import com.kaleaon.mnxmindmaker.util.tooling.DeploymentManifestPolicy
import com.kaleaon.mnxmindmaker.util.tooling.PersonaPolicy
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolOperationClass
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyContext
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyEngineTest {

    private val engine = ToolPolicyEngine(
        specsByName = mapOf(
            "get_node" to ToolSpec("get_node", "Read", ToolOperationClass.READ_ONLY),
            "set_node_attribute" to ToolSpec("set_node_attribute", "Mutate", ToolOperationClass.MUTATING),
            "delete_all" to ToolSpec("delete_all", "Danger", ToolOperationClass.HIGH_RISK)
        )
    )

    @Test
    fun `unknown tool is denied by default`() {
        val decision = engine.evaluate(
            ToolInvocation(id = "1", toolName = "unknown"),
            MindGraph()
        )
        assertEquals(PolicyDecisionType.DENY, decision.type)
    }

    @Test
    fun `read only tool is auto-allowed`() {
        val decision = engine.evaluate(
            ToolInvocation(id = "1", toolName = "get_node"),
            MindGraph()
        )
        assertEquals(PolicyDecisionType.ALLOW, decision.type)
    }

    @Test
    fun `mutating tool on invariant node is denied`() {
        val invariant = MindNode(
            id = "n1",
            label = "Core",
            type = NodeType.IDENTITY,
            attributes = mutableMapOf("protection_level" to "invariant")
        )
        val graph = MindGraph(nodes = mutableListOf(invariant))

        val decision = engine.evaluate(
            ToolInvocation(
                id = "1",
                toolName = "set_node_attribute",
                argumentsJson = JSONObject().put("node_id", "n1")
            ),
            graph
        )

        assertEquals(PolicyDecisionType.DENY, decision.type)
        assertTrue(decision.reason.contains("invariant", ignoreCase = true))
    }

    @Test
    fun `high risk always requires approval`() {
        val decision = engine.evaluate(
            ToolInvocation(id = "1", toolName = "delete_all"),
            MindGraph()
        )

        assertEquals(PolicyDecisionType.REQUIRE_USER_APPROVAL, decision.type)
    }

    @Test
    fun `delete style tool name triggers explicit high risk approval`() {
        val engine = ToolPolicyEngine(
            specsByName = mapOf(
                "calendar_delete_event" to ToolSpec(
                    "calendar_delete_event",
                    "Delete event",
                    ToolOperationClass.MUTATING
                )
            )
        )

        val decision = engine.evaluate(ToolInvocation(id = "1", toolName = "calendar_delete_event"), MindGraph())

        assertEquals(PolicyDecisionType.REQUIRE_USER_APPROVAL, decision.type)
        assertEquals("delete", decision.explicitActionType)
    }

    @Test
    fun `deployment persona allowlist denies tools outside allowed set`() {
        val decision = engine.evaluate(
            invocation = ToolInvocation(id = "1", toolName = "delete_all"),
            graph = MindGraph(),
            context = ToolPolicyContext(
                personaId = "researcher",
                deploymentPolicy = DeploymentManifestPolicy(
                    personaPolicies = mapOf(
                        "researcher" to PersonaPolicy(
                            allowToolNames = setOf("get_node")
                        )
                    )
                )
            )
        )

        assertEquals(PolicyDecisionType.DENY, decision.type)
        assertTrue(decision.reason.contains("allowlist", ignoreCase = true))
    }

    @Test
    fun `deployment persona denylist overrides base tool class`() {
        val decision = engine.evaluate(
            invocation = ToolInvocation(id = "1", toolName = "get_node"),
            graph = MindGraph(),
            context = ToolPolicyContext(
                personaId = "auditor",
                deploymentPolicy = DeploymentManifestPolicy(
                    personaPolicies = mapOf(
                        "auditor" to PersonaPolicy(
                            denyToolNames = setOf("get_node")
                        )
                    )
                )
            )
        )

        assertEquals(PolicyDecisionType.DENY, decision.type)
        assertTrue(decision.reason.contains("denied by persona", ignoreCase = true))
    }

    @Test
    fun `deployment allowlist does not bypass approval for high risk tools`() {
        val decision = engine.evaluate(
            invocation = ToolInvocation(id = "1", toolName = "delete_all"),
            graph = MindGraph(),
            context = ToolPolicyContext(
                personaId = "ops",
                deploymentPolicy = DeploymentManifestPolicy(
                    personaPolicies = mapOf(
                        "ops" to PersonaPolicy(
                            allowToolNames = setOf("delete_all")
                        )
                    )
                )
            )
        )

        assertEquals(PolicyDecisionType.REQUIRE_USER_APPROVAL, decision.type)
    }

}

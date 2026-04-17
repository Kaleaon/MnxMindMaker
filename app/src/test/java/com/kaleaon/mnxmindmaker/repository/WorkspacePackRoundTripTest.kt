package com.kaleaon.mnxmindmaker.repository

import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePackRoundTripTest {

    @Test
    fun `workspace pack serialization round-trips multiple named minds and ownership boundaries`() {
        val personalMind = MindGraph(
            id = "mind-personal",
            name = "Personal Operating Mind",
            nodes = mutableListOf(
                MindNode(id = "n1", label = "Self", type = NodeType.IDENTITY),
                MindNode(id = "n2", label = "Journal Memory", type = NodeType.MEMORY, parentId = "n1")
            ),
            edges = mutableListOf(MindEdge(fromNodeId = "n1", toNodeId = "n2", label = "contains"))
        )

        val teamMind = MindGraph(
            id = "mind-team",
            name = "Team Product Mind",
            nodes = mutableListOf(
                MindNode(id = "t1", label = "Roadmap", type = NodeType.KNOWLEDGE),
                MindNode(id = "t2", label = "Decision", type = NodeType.BELIEF, parentId = "t1")
            )
        )

        val deviceMind = MindGraph(
            id = "mind-device",
            name = "On-device Assistant",
            nodes = mutableListOf(MindNode(id = "d1", label = "Edge Persona", type = NodeType.IDENTITY))
        )

        val pack = MnxRepository.MindWorkspacePack(
            version = 1,
            exportedAt = 1_715_555_000_000L,
            workspaces = listOf(
                MnxRepository.MindWorkspace(
                    id = "ws-personal",
                    name = "Personal",
                    ownership = MnxRepository.OwnershipBoundary.PERSONAL,
                    permissions = listOf(
                        MnxRepository.WorkspacePermission(
                            principalId = "user:me",
                            scopes = setOf(
                                MnxRepository.WorkspaceScope.READ_MIND,
                                MnxRepository.WorkspaceScope.WRITE_MIND,
                                MnxRepository.WorkspaceScope.EXPORT_PACK
                            )
                        )
                    ),
                    mindIds = listOf("mind-personal")
                ),
                MnxRepository.MindWorkspace(
                    id = "ws-team",
                    name = "Product Team",
                    ownership = MnxRepository.OwnershipBoundary.TEAM,
                    permissions = listOf(
                        MnxRepository.WorkspacePermission(
                            principalId = "team:product",
                            scopes = setOf(MnxRepository.WorkspaceScope.READ_MIND)
                        ),
                        MnxRepository.WorkspacePermission(
                            principalId = "role:lead",
                            scopes = setOf(
                                MnxRepository.WorkspaceScope.READ_MIND,
                                MnxRepository.WorkspaceScope.WRITE_MIND,
                                MnxRepository.WorkspaceScope.IMPORT_PACK,
                                MnxRepository.WorkspaceScope.EXPORT_PACK
                            )
                        )
                    ),
                    mindIds = listOf("mind-team")
                ),
                MnxRepository.MindWorkspace(
                    id = "ws-device",
                    name = "Pixel 11",
                    ownership = MnxRepository.OwnershipBoundary.DEVICE,
                    permissions = listOf(
                        MnxRepository.WorkspacePermission(
                            principalId = "device:pixel11",
                            scopes = setOf(MnxRepository.WorkspaceScope.READ_MIND)
                        )
                    ),
                    mindIds = listOf("mind-device")
                )
            ),
            minds = listOf(
                MnxRepository.NamedMind("mind-personal", "Personal Operating Mind", "ws-personal", personalMind),
                MnxRepository.NamedMind("mind-team", "Team Product Mind", "ws-team", teamMind),
                MnxRepository.NamedMind("mind-device", "On-device Assistant", "ws-device", deviceMind)
            )
        )

        val bytes = MnxRepository.serializeWorkspacePack(pack)
        val restored = MnxRepository.deserializeWorkspacePack(bytes)

        assertEquals(pack.version, restored.version)
        assertEquals(pack.exportedAt, restored.exportedAt)
        assertEquals(3, restored.workspaces.size)
        assertEquals(3, restored.minds.size)

        val restoredTeam = restored.workspaces.first { it.id == "ws-team" }
        assertEquals(MnxRepository.OwnershipBoundary.TEAM, restoredTeam.ownership)
        assertTrue(restoredTeam.permissions.any { permission ->
            permission.principalId == "role:lead" &&
                permission.scopes.contains(MnxRepository.WorkspaceScope.IMPORT_PACK)
        })

        val restoredDeviceMind = restored.minds.first { it.id == "mind-device" }
        assertEquals("ws-device", restoredDeviceMind.workspaceId)
        assertEquals("On-device Assistant", restoredDeviceMind.graph.name)
    }

    @Test
    fun `workspace pack payload survives full MNX encode and decode in raw section`() {
        val graph = MindGraph(
            id = "mind-01",
            name = "Workspace Bound Mind",
            nodes = mutableListOf(MindNode(id = "root", label = "Root", type = NodeType.IDENTITY))
        )
        val pack = MnxRepository.MindWorkspacePack(
            workspaces = listOf(
                MnxRepository.MindWorkspace(
                    id = "ws1",
                    name = "Default",
                    ownership = MnxRepository.OwnershipBoundary.PERSONAL,
                    permissions = listOf(
                        MnxRepository.WorkspacePermission(
                            principalId = "user:owner",
                            scopes = setOf(MnxRepository.WorkspaceScope.ADMIN)
                        )
                    ),
                    mindIds = listOf(graph.id)
                )
            ),
            minds = listOf(MnxRepository.NamedMind(graph.id, graph.name, "ws1", graph))
        )

        val rawPayload = MnxRepository.serializeWorkspacePack(pack)
        val mnxFile = MnxFile(
            header = MnxHeader(),
            sections = emptyMap(),
            rawSections = mapOf(MnxRepository.WORKSPACE_PACK_SECTION_TYPE to rawPayload)
        )

        val encoded = MnxCodec.encodeToBytes(mnxFile)
        val decoded = MnxCodec.decodeFromBytes(encoded)

        val restored = MnxRepository.deserializeWorkspacePack(
            decoded.rawSections[MnxRepository.WORKSPACE_PACK_SECTION_TYPE]!!
        )

        assertEquals(1, restored.workspaces.size)
        assertEquals(1, restored.minds.size)
        assertEquals(MnxRepository.WorkspaceScope.ADMIN, restored.workspaces.first().permissions.first().scopes.first())
        assertEquals("Workspace Bound Mind", restored.minds.first().name)
    }
}

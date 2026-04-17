package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMutationTransactionManagerTest {

    @Test
    fun `transaction commits and undo redo is persisted across sessions`() {
        val persistence = InMemoryPersistence()
        val managerA = createManager(persistence)

        val commit = managerA.executeTransaction(
            mutationName = "rename_root",
            expectedVersion = managerA.currentVersion(),
            expectedHash = managerA.currentHash()
        ) { graph ->
            graph.copy(nodes = graph.nodes.map { it.copy(label = "Renamed") }.toMutableList())
        }

        assertTrue(commit is TransactionResult.Committed)
        assertEquals("Renamed", managerA.currentGraph().nodes.first().label)

        val managerB = createManager(persistence)
        assertEquals("Renamed", managerB.currentGraph().nodes.first().label)

        val undo = managerB.undo()
        assertTrue(undo is UndoRedoResult.Applied)
        assertEquals("Root", managerB.currentGraph().nodes.first().label)

        val managerC = createManager(persistence)
        val redo = managerC.redo()
        assertTrue(redo is UndoRedoResult.Applied)
        assertEquals("Renamed", managerC.currentGraph().nodes.first().label)
    }

    @Test
    fun `conflict detection rejects stale version or hash`() {
        val manager = createManager(InMemoryPersistence())

        val first = manager.executeTransaction(
            mutationName = "first",
            expectedVersion = manager.currentVersion(),
            expectedHash = manager.currentHash()
        ) { graph ->
            graph.copy(nodes = graph.nodes.map { it.copy(description = "updated") }.toMutableList())
        }
        assertTrue(first is TransactionResult.Committed)

        val stale = manager.executeTransaction(
            mutationName = "stale",
            expectedVersion = 0L,
            expectedHash = "deadbeef"
        ) { graph -> graph }

        assertTrue(stale is TransactionResult.Conflict)
    }

    private fun createManager(persistence: InMemoryPersistence): MindMutationTransactionManager {
        return MindMutationTransactionManager(
            persistence = persistence,
            initialGraphFactory = {
                MindGraph(
                    name = "Mind",
                    nodes = mutableListOf(MindNode(id = "root", label = "Root", type = NodeType.IDENTITY))
                )
            }
        )
    }

    private class InMemoryPersistence : MindMutationTransactionManager.Persistence {
        private var payload: String? = null
        override fun load(): String? = payload
        override fun save(payload: String) {
            this.payload = payload
        }
    }
}

package com.kaleaon.mnxmindmaker.observability

import com.kaleaon.mnxmindmaker.util.observability.BackgroundOperationManager
import com.kaleaon.mnxmindmaker.util.observability.BackgroundOperationStatus
import com.kaleaon.mnxmindmaker.util.observability.BackgroundOperationType
import com.kaleaon.mnxmindmaker.util.observability.InMemoryBackgroundOperationStore
import com.kaleaon.mnxmindmaker.util.observability.OperatorActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundOperationTrackerTest {

    @Test
    fun `tracks progress retries failure reason and operator actions`() {
        var now = 1_000L
        val manager = BackgroundOperationManager(
            store = InMemoryBackgroundOperationStore(),
            nowMs = { now++ }
        )

        val started = manager.start(
            type = BackgroundOperationType.LARGE_IMPORT,
            operatorId = "alice",
            operationId = "op-import-1"
        )

        manager.updateProgress(
            operationId = started.operationId,
            processedUnits = 300,
            totalUnits = 1_000,
            checkpoint = "chunk-3"
        )
        manager.pause(started.operationId, operatorId = "alice", reason = "scheduled maintenance")
        manager.resume(started.operationId, operatorId = "bob")
        manager.markFailed(started.operationId, reason = "network timeout", retryable = true)
        manager.retry(started.operationId, operatorId = "bob", reason = "retry after timeout")
        manager.addOperatorNote(started.operationId, operatorId = "ops-oncall", note = "watching backlog")

        val latest = manager.get(started.operationId) ?: error("operation not found")

        assertEquals(BackgroundOperationStatus.RUNNING, latest.status)
        assertEquals("network timeout", latest.lastFailureReason)
        assertEquals(1, latest.failures.size)
        assertEquals(1, latest.retryAttempts.size)
        assertEquals("chunk-3", latest.progress.checkpoint)
        assertEquals(0.3, latest.progress.completionRatio, 0.0001)
        assertTrue(latest.operatorActions.any { it.type == OperatorActionType.PAUSE })
        assertTrue(latest.operatorActions.any { it.type == OperatorActionType.RESUME })
        assertTrue(latest.operatorActions.any { it.type == OperatorActionType.FAIL })
        assertTrue(latest.operatorActions.any { it.type == OperatorActionType.RETRY })
        assertTrue(latest.operatorActions.any { it.type == OperatorActionType.NOTE })
    }

    @Test
    fun `supports resumable migration and preserves resume token`() {
        var now = 10_000L
        val manager = BackgroundOperationManager(
            store = InMemoryBackgroundOperationStore(),
            nowMs = { now += 10L; now }
        )

        manager.start(
            type = BackgroundOperationType.MIGRATION,
            operatorId = "migration-bot",
            operationId = "op-migrate-1",
            resumeToken = "phase-a"
        )
        manager.updateProgress("op-migrate-1", processedUnits = 2_000, totalUnits = 8_000, checkpoint = "phase-b")
        manager.pause("op-migrate-1", operatorId = "migration-bot", reason = "window closed")
        manager.resume("op-migrate-1", operatorId = "migration-bot")
        manager.updateProgress("op-migrate-1", processedUnits = 8_000, totalUnits = 8_000, checkpoint = "phase-done")
        manager.markSucceeded("op-migrate-1", operatorId = "migration-bot")

        val completed = manager.get("op-migrate-1") ?: error("operation not found")
        assertEquals(BackgroundOperationStatus.SUCCEEDED, completed.status)
        assertEquals("phase-done", completed.resumeToken)
        assertEquals(1.0, completed.progress.completionRatio, 0.0001)
    }

    @Test
    fun `tracks replication and re-embedding as background operation categories`() {
        val manager = BackgroundOperationManager(InMemoryBackgroundOperationStore())

        manager.start(BackgroundOperationType.REPLICATION, operatorId = "replicator", operationId = "op-repl")
        manager.start(BackgroundOperationType.RE_EMBEDDING, operatorId = "embedder", operationId = "op-embed")

        val all = manager.list().associateBy { it.operationId }
        assertEquals(BackgroundOperationType.REPLICATION, all["op-repl"]?.type)
        assertEquals(BackgroundOperationType.RE_EMBEDDING, all["op-embed"]?.type)
    }
}

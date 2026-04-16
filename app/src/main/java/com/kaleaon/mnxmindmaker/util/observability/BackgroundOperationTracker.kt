package com.kaleaon.mnxmindmaker.util.observability

import java.util.UUID

enum class BackgroundOperationType {
    LARGE_IMPORT,
    MIGRATION,
    RE_EMBEDDING,
    REPLICATION
}

enum class BackgroundOperationStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

enum class OperatorActionType {
    START,
    PAUSE,
    RESUME,
    RETRY,
    CANCEL,
    NOTE,
    COMPLETE,
    FAIL
}

data class BackgroundProgress(
    val processedUnits: Long = 0L,
    val totalUnits: Long? = null,
    val checkpoint: String = ""
) {
    val completionRatio: Double = when {
        totalUnits == null || totalUnits <= 0L -> 0.0
        processedUnits <= 0L -> 0.0
        else -> (processedUnits.toDouble() / totalUnits.toDouble()).coerceIn(0.0, 1.0)
    }
}

data class RetryAttempt(
    val atEpochMs: Long,
    val reason: String
)

data class FailureRecord(
    val atEpochMs: Long,
    val reason: String,
    val retryable: Boolean
)

data class OperatorAction(
    val atEpochMs: Long,
    val operatorId: String,
    val type: OperatorActionType,
    val note: String
)

data class BackgroundOperationRecord(
    val operationId: String,
    val type: BackgroundOperationType,
    val status: BackgroundOperationStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val progress: BackgroundProgress = BackgroundProgress(),
    val retryAttempts: List<RetryAttempt> = emptyList(),
    val failures: List<FailureRecord> = emptyList(),
    val operatorActions: List<OperatorAction> = emptyList(),
    val resumeToken: String = ""
) {
    val lastFailureReason: String get() = failures.lastOrNull()?.reason.orEmpty()
}

interface BackgroundOperationStore {
    fun upsert(record: BackgroundOperationRecord)
    fun get(operationId: String): BackgroundOperationRecord?
    fun list(): List<BackgroundOperationRecord>
}

class InMemoryBackgroundOperationStore : BackgroundOperationStore {
    private val records = linkedMapOf<String, BackgroundOperationRecord>()

    override fun upsert(record: BackgroundOperationRecord) {
        records[record.operationId] = record
    }

    override fun get(operationId: String): BackgroundOperationRecord? = records[operationId]

    override fun list(): List<BackgroundOperationRecord> = records.values.toList()
}

class BackgroundOperationManager(
    private val store: BackgroundOperationStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun start(
        type: BackgroundOperationType,
        operatorId: String,
        operationId: String = UUID.randomUUID().toString(),
        resumeToken: String = ""
    ): BackgroundOperationRecord {
        val now = nowMs()
        val record = BackgroundOperationRecord(
            operationId = operationId,
            type = type,
            status = BackgroundOperationStatus.RUNNING,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            resumeToken = resumeToken,
            operatorActions = listOf(
                OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = OperatorActionType.START,
                    note = "operation started"
                )
            )
        )
        store.upsert(record)
        return record
    }

    fun updateProgress(
        operationId: String,
        processedUnits: Long,
        totalUnits: Long? = null,
        checkpoint: String = ""
    ): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val next = existing.copy(
            updatedAtEpochMs = nowMs(),
            progress = BackgroundProgress(
                processedUnits = processedUnits.coerceAtLeast(0L),
                totalUnits = totalUnits,
                checkpoint = checkpoint
            ),
            resumeToken = checkpoint.ifBlank { existing.resumeToken }
        )
        store.upsert(next)
        return next
    }

    fun pause(operationId: String, operatorId: String, reason: String): BackgroundOperationRecord {
        return updateStatusWithAction(
            operationId = operationId,
            status = BackgroundOperationStatus.PAUSED,
            operatorId = operatorId,
            actionType = OperatorActionType.PAUSE,
            note = reason
        )
    }

    fun resume(operationId: String, operatorId: String, resumeToken: String = ""): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val now = nowMs()
        return persist(
            existing.copy(
                status = BackgroundOperationStatus.RUNNING,
                updatedAtEpochMs = now,
                resumeToken = resumeToken.ifBlank { existing.resumeToken },
                operatorActions = existing.operatorActions + OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = OperatorActionType.RESUME,
                    note = "operation resumed"
                )
            )
        )
    }

    fun markFailed(
        operationId: String,
        reason: String,
        retryable: Boolean,
        operatorId: String = "system"
    ): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val now = nowMs()
        return persist(
            existing.copy(
                status = BackgroundOperationStatus.FAILED,
                updatedAtEpochMs = now,
                failures = existing.failures + FailureRecord(now, reason, retryable),
                operatorActions = existing.operatorActions + OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = OperatorActionType.FAIL,
                    note = reason
                )
            )
        )
    }

    fun retry(operationId: String, operatorId: String, reason: String): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val now = nowMs()
        return persist(
            existing.copy(
                status = BackgroundOperationStatus.RUNNING,
                updatedAtEpochMs = now,
                retryAttempts = existing.retryAttempts + RetryAttempt(atEpochMs = now, reason = reason),
                operatorActions = existing.operatorActions + OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = OperatorActionType.RETRY,
                    note = reason
                )
            )
        )
    }

    fun markSucceeded(operationId: String, operatorId: String, note: String = "completed"): BackgroundOperationRecord {
        return updateStatusWithAction(
            operationId = operationId,
            status = BackgroundOperationStatus.SUCCEEDED,
            operatorId = operatorId,
            actionType = OperatorActionType.COMPLETE,
            note = note
        )
    }

    fun cancel(operationId: String, operatorId: String, reason: String): BackgroundOperationRecord {
        return updateStatusWithAction(
            operationId = operationId,
            status = BackgroundOperationStatus.CANCELLED,
            operatorId = operatorId,
            actionType = OperatorActionType.CANCEL,
            note = reason
        )
    }

    fun addOperatorNote(operationId: String, operatorId: String, note: String): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val now = nowMs()
        return persist(
            existing.copy(
                updatedAtEpochMs = now,
                operatorActions = existing.operatorActions + OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = OperatorActionType.NOTE,
                    note = note
                )
            )
        )
    }

    fun get(operationId: String): BackgroundOperationRecord? = store.get(operationId)

    fun list(): List<BackgroundOperationRecord> = store.list()

    private fun updateStatusWithAction(
        operationId: String,
        status: BackgroundOperationStatus,
        operatorId: String,
        actionType: OperatorActionType,
        note: String
    ): BackgroundOperationRecord {
        val existing = requireOperation(operationId)
        val now = nowMs()
        return persist(
            existing.copy(
                status = status,
                updatedAtEpochMs = now,
                operatorActions = existing.operatorActions + OperatorAction(
                    atEpochMs = now,
                    operatorId = operatorId,
                    type = actionType,
                    note = note
                )
            )
        )
    }

    private fun requireOperation(operationId: String): BackgroundOperationRecord {
        return requireNotNull(store.get(operationId)) { "Unknown operation id: $operationId" }
    }

    private fun persist(record: BackgroundOperationRecord): BackgroundOperationRecord {
        store.upsert(record)
        return record
    }
}

package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory queue for outbound operations that cannot be executed while offline.
 *
 * Each operation can later be reconciled once connectivity returns.
 */
class OutboundOperationQueue {
    private val lock = Any()
    private val idCounter = AtomicLong(0L)
    private val entries = mutableListOf<QueuedOutboundOperation>()

    fun enqueue(kind: String, payload: JSONObject): String = synchronized(lock) {
        val id = "op_${idCounter.incrementAndGet()}"
        entries += QueuedOutboundOperation(
            id = id,
            kind = kind,
            payload = JSONObject(payload.toString())
        )
        id
    }

    fun pending(kind: String? = null): List<QueuedOutboundOperation> = synchronized(lock) {
        entries
            .filter { it.status == OutboundOperationStatus.PENDING }
            .filter { kind == null || it.kind == kind }
            .map { it.copy(payload = JSONObject(it.payload.toString()), result = it.result?.let { json -> JSONObject(json.toString()) }) }
    }

    fun reconcile(
        kind: String? = null,
        executor: (QueuedOutboundOperation) -> JSONObject
    ): List<QueuedOutboundOperation> = synchronized(lock) {
        val reconciled = mutableListOf<QueuedOutboundOperation>()
        entries.forEachIndexed { index, operation ->
            if (operation.status != OutboundOperationStatus.PENDING) return@forEachIndexed
            if (kind != null && operation.kind != kind) return@forEachIndexed

            try {
                val result = executor(operation)
                val updated = operation.copy(
                    status = OutboundOperationStatus.RECONCILED,
                    attempts = operation.attempts + 1,
                    result = JSONObject(result.toString()),
                    lastError = null
                )
                entries[index] = updated
                reconciled += updated
            } catch (e: Exception) {
                val updated = operation.copy(
                    attempts = operation.attempts + 1,
                    lastError = e.message ?: "Unknown reconciliation failure"
                )
                entries[index] = updated
            }
        }
        reconciled
    }
}

enum class OutboundOperationStatus {
    PENDING,
    RECONCILED
}

data class QueuedOutboundOperation(
    val id: String,
    val kind: String,
    val payload: JSONObject,
    val status: OutboundOperationStatus = OutboundOperationStatus.PENDING,
    val attempts: Int = 0,
    val result: JSONObject? = null,
    val lastError: String? = null
)

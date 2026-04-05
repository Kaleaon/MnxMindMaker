package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One persisted event in a tool execution timeline.
 */
data class ToolTranscriptEntry(
    val runId: String,
    val timestampMs: Long,
    val eventType: String,
    val toolInvocationId: String,
    val toolName: String,
    val payload: JSONObject
)

interface ToolTranscriptStore {
    fun persist(entry: ToolTranscriptEntry)
}

class InMemoryToolTranscriptStore : ToolTranscriptStore {
    private val _entries = mutableListOf<ToolTranscriptEntry>()
    val entries: List<ToolTranscriptEntry> get() = _entries.toList()

    override fun persist(entry: ToolTranscriptEntry) {
        _entries += entry
    }
}

class FileToolTranscriptStore(private val file: File) : ToolTranscriptStore {
    override fun persist(entry: ToolTranscriptEntry) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.appendText(entry.toJson().toString() + "\n")
    }

    private fun ToolTranscriptEntry.toJson(): JSONObject = JSONObject()
        .put("run_id", runId)
        .put("timestamp_ms", timestampMs)
        .put("event_type", eventType)
        .put("tool_invocation_id", toolInvocationId)
        .put("tool_name", toolName)
        .put("payload", payload)
}

class ToolTranscriptRecorder(
    private val store: ToolTranscriptStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    fun recordRequest(runId: String, invocation: ToolInvocation) {
        persist(
            runId = runId,
            eventType = "request",
            invocation = invocation,
            payload = RedactionUtils.redact(invocation.argumentsJson)
        )
    }

    fun recordDecision(runId: String, invocation: ToolInvocation, decision: PolicyDecision) {
        persist(
            runId = runId,
            eventType = "policy_decision",
            invocation = invocation,
            payload = JSONObject()
                .put("decision", decision.type.name)
                .put("reason", decision.reason)
        )
    }

    fun recordResult(runId: String, invocation: ToolInvocation, result: ToolResult) {
        persist(
            runId = runId,
            eventType = "result",
            invocation = invocation,
            payload = RedactionUtils.redact(result.contentJson)
                .put("is_error", result.isError)
                .put("rolled_back", result.rolledBack)
        )
    }

    private fun persist(runId: String, eventType: String, invocation: ToolInvocation, payload: JSONObject) {
        store.persist(
            ToolTranscriptEntry(
                runId = runId,
                timestampMs = nowMs(),
                eventType = eventType,
                toolInvocationId = invocation.id,
                toolName = invocation.toolName,
                payload = payload
            )
        )
    }
}

object RedactionUtils {
    private val defaultSensitiveKeys = setOf(
        "api_key", "apikey", "token", "access_token", "refresh_token",
        "secret", "password", "authorization", "auth"
    )

    fun redact(source: JSONObject, sensitiveKeys: Set<String> = defaultSensitiveKeys): JSONObject {
        val copy = JSONObject()
        val iterator = source.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = source.get(key)
            val lower = key.lowercase()
            copy.put(
                key,
                when {
                    lower in sensitiveKeys || lower.endsWith("_key") || lower.contains("secret") -> REDACTED_VALUE
                    value is JSONObject -> redact(value, sensitiveKeys)
                    value is JSONArray -> redactArray(value, sensitiveKeys)
                    else -> value
                }
            )
        }
        return copy
    }

    private fun redactArray(array: JSONArray, sensitiveKeys: Set<String>): JSONArray {
        val redacted = JSONArray()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            when (value) {
                is JSONObject -> redacted.put(redact(value, sensitiveKeys))
                is JSONArray -> redacted.put(redactArray(value, sensitiveKeys))
                else -> redacted.put(value)
            }
        }
        return redacted
    }

    const val REDACTED_VALUE = "***REDACTED***"
}

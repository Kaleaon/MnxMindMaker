package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Foundational non-graph tools for agent workflows.
 *
 * Includes:
 * - file read/write (scoped directories)
 * - notes/tasks database actions
 * - calendar integration (local event store)
 * - web fetch/search abstraction
 * - optional terminal executor (sandboxed allowlist)
 */
class FoundationalTools(
    private val appRoot: File,
    private val scopedDirectories: List<File>,
    private val memoryManager: MemoryManager = MemoryManager(),
    private val allowTerminal: Boolean = false,
    private val allowedCommandPrefixes: List<String> = listOf("echo", "pwd", "date"),
    private val outboundOperationQueue: OutboundOperationQueue? = null,
    private val isNetworkAvailable: () -> Boolean = { true }
) {

    private val dbFile = File(appRoot, "tooling/notes_tasks_db.json")
    private val calendarFile = File(appRoot, "tooling/calendar_events.json")

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "memory_search",
            description = "Search memory entries by query text. Safety: read-only; does not modify memory.",
            operationClass = ToolOperationClass.READ_ONLY,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("query"))
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string").put("minLength", 1))
                    .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50))),
            execute = ToolHandler { invocation, graph -> executeMemorySearch(invocation, graph) }
        ),
        ToolSpec(
            name = "memory_upsert",
            description = "Upsert memory by id and category (profile|semantic). Safety: mutating operation; restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("memory_id").put("category").put("value").put("sensitivity"))
                .put("properties", JSONObject()
                    .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                    .put("category", JSONObject().put("type", "string").put("enum", JSONArray().put("profile").put("semantic")))
                    .put("value", JSONObject().put("type", "string").put("minLength", 1))
                    .put("label", JSONObject().put("type", "string"))
                    .put("tags", JSONObject().put("type", "string"))
                    .put("writing_style", JSONObject().put("type", "string"))
                    .put("sensitivity", JSONObject().put("type", "string").put("enum", JSONArray().put("low").put("medium").put("high").put("restricted")))
                    .put("allow_high_sensitivity", JSONObject().put("type", "boolean"))),
            execute = ToolHandler { invocation, graph -> executeMemoryUpsert(invocation, graph) }
        ),
        ToolSpec(
            name = "memory_edit",
            description = "Edit an existing memory by id. Safety: mutating operation; restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("memory_id"))
                .put("properties", JSONObject()
                    .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                    .put("value", JSONObject().put("type", "string"))
                    .put("label", JSONObject().put("type", "string"))
                    .put("tags", JSONObject().put("type", "string"))
                    .put("writing_style", JSONObject().put("type", "string"))
                    .put("sensitivity", JSONObject().put("type", "string").put("enum", JSONArray().put("low").put("medium").put("high").put("restricted")))
                    .put("allow_high_sensitivity", JSONObject().put("type", "boolean"))),
            execute = ToolHandler { invocation, graph -> executeMemoryEdit(invocation, graph) }
        ),
        ToolSpec(
            name = "memory_delete",
            description = "Delete memory by id. Safety: mutating operation; restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("memory_id"))
                .put("properties", JSONObject()
                    .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                    .put("allow_high_sensitivity", JSONObject().put("type", "boolean"))),
            execute = ToolHandler { invocation, graph -> executeMemoryDelete(invocation, graph) }
        ),
        ToolSpec(
            name = "memory_status",
            description = "Return current memory policy mode and memory counts. Safety: read-only.",
            operationClass = ToolOperationClass.READ_ONLY,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false),
            execute = ToolHandler { invocation, graph -> executeMemoryStatus(invocation, graph) }
        ),
        ToolSpec(
            name = "file_read",
            description = "Read a UTF-8 file under allowed scoped directories.",
            operationClass = ToolOperationClass.READ_ONLY,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("required", JSONArray().put("path"))
                .put("properties", JSONObject().put("path", JSONObject().put("type", "string"))),
            execute = ToolHandler { invocation, graph -> executeFileRead(invocation, graph) }
        ),
        ToolSpec(
            name = "file_write",
            description = "Write UTF-8 content to a file under allowed scoped directories.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject()
                .put("type", "object")
                .put("required", JSONArray().put("path").put("content"))
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string"))),
            execute = ToolHandler { invocation, graph -> executeFileWrite(invocation, graph) }
        ),
        ToolSpec(
            name = "notes_tasks_db",
            description = "Create/read/update/delete notes and tasks in local JSON DB.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject().put("type", "object"),
            execute = ToolHandler { invocation, graph -> executeNotesTasks(invocation, graph) }
        ),
        ToolSpec(
            name = "calendar_integration",
            description = "List/create/delete local calendar events.",
            operationClass = ToolOperationClass.MUTATING,
            inputSchema = JSONObject().put("type", "object"),
            execute = ToolHandler { invocation, graph -> executeCalendar(invocation, graph) }
        ),
        ToolSpec(
            name = "web_fetch_search",
            description = "Fetch URL content or lightweight web search snippets.",
            operationClass = ToolOperationClass.READ_ONLY,
            inputSchema = JSONObject().put("type", "object"),
            execute = ToolHandler { invocation, graph -> executeWeb(invocation, graph) }
        ),
        ToolSpec(
            name = "terminal_execute",
            description = "Run sandboxed command from explicit allowlist.",
            operationClass = ToolOperationClass.HIGH_RISK,
            requiresConfirmation = true,
            inputSchema = JSONObject().put("type", "object"),
            execute = ToolHandler { invocation, graph -> executeTerminal(invocation, graph) }
        )
    )

    fun handlers(): Map<String, ToolHandler> = specs().associate { it.name to (it.execute ?: ToolHandler { _, _ ->
        ToolExecutionOutcome(JSONObject().put("error", "no_handler"), mutatedGraph = false)
    }) }

    private fun executeFileRead(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val path = invocation.argumentsJson.optString("path")
        val file = resolveScoped(path)
        val payload = JSONObject().put("path", file.absolutePath).put("content", file.readText())
        return ToolExecutionOutcome(payload, mutatedGraph = false)
    }

    private fun executeMemorySearch(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val query = invocation.argumentsJson.optString("query").trim()
        val limit = invocation.argumentsJson.optInt("limit", 10).coerceIn(1, 50)
        val results = memoryManager.searchMemories(query, limit)
        val payload = JSONObject()
            .put("query", query)
            .put("limit", limit)
            .put("results", JSONArray().apply { results.forEach { put(memoryToJson(it)) } })
        return ToolExecutionOutcome(payload, mutatedGraph = false)
    }

    private fun executeMemoryUpsert(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val args = invocation.argumentsJson
        val memoryId = args.optString("memory_id").trim()
        val category = args.optString("category").trim().lowercase()
        val value = args.optString("value")
        val label = args.optString("label").ifBlank { memoryId }
        val sensitivity = args.optString("sensitivity", "low").lowercase()
        val sensitivityCheck = enforceSensitivityPolicy(
            sensitivity = sensitivity,
            allowHighSensitivity = args.optBoolean("allow_high_sensitivity", false)
        )
        if (sensitivityCheck != null) return ToolExecutionOutcome(sensitivityCheck, mutatedGraph = false)

        when (category) {
            "profile" -> memoryManager.upsertProfileMemory(
                key = memoryId,
                value = value,
                writingStyle = args.optString("writing_style").ifBlank { null },
                sensitivity = sensitivity
            )
            "semantic" -> memoryManager.upsertSemanticMemory(
                MindNode(
                    id = memoryId,
                    label = label,
                    type = NodeType.MEMORY,
                    description = value,
                    attributes = mutableMapOf(
                        "semantic_subtype" to "semantic",
                        "memory_category" to "semantic",
                        "sensitivity" to sensitivity,
                        "timestamp" to System.currentTimeMillis().toString(),
                        "tags" to args.optString("tags")
                    )
                )
            )
            else -> {
                return ToolExecutionOutcome(
                    JSONObject().put("error", "unsupported_memory_category").put("category", category),
                    mutatedGraph = false
                )
            }
        }

        return ToolExecutionOutcome(
            JSONObject()
                .put("status", "upserted")
                .put("memory_id", memoryId)
                .put("category", category)
                .put("sensitivity", sensitivity),
            mutatedGraph = false
        )
    }

    private fun executeMemoryEdit(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val args = invocation.argumentsJson
        val memoryId = args.optString("memory_id").trim()
        val existing = memoryManager.getMemory(memoryId)
            ?: return ToolExecutionOutcome(JSONObject().put("error", "memory_not_found").put("memory_id", memoryId), mutatedGraph = false)

        val targetSensitivity = args.optString("sensitivity").ifBlank {
            existing.attributes["sensitivity"] ?: "low"
        }.lowercase()
        val sensitivityCheck = enforceSensitivityPolicy(
            sensitivity = targetSensitivity,
            allowHighSensitivity = args.optBoolean("allow_high_sensitivity", false)
        )
        if (sensitivityCheck != null) return ToolExecutionOutcome(sensitivityCheck, mutatedGraph = false)

        val edited = memoryManager.editMemory(memoryId) { node ->
            node.copy(
                label = args.optString("label").ifBlank { node.label },
                description = args.optString("value").ifBlank { node.description },
                attributes = node.attributes.toMutableMap().apply {
                    if (args.has("sensitivity")) put("sensitivity", targetSensitivity)
                    if (args.has("tags")) put("tags", args.optString("tags"))
                    if (args.has("writing_style")) put("writing_style", args.optString("writing_style"))
                    put("timestamp", System.currentTimeMillis().toString())
                }
            )
        }

        return ToolExecutionOutcome(
            JSONObject().put("status", if (edited) "edited" else "memory_not_found").put("memory_id", memoryId),
            mutatedGraph = false
        )
    }

    private fun executeMemoryDelete(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val args = invocation.argumentsJson
        val memoryId = args.optString("memory_id").trim()
        val existing = memoryManager.getMemory(memoryId)
            ?: return ToolExecutionOutcome(JSONObject().put("error", "memory_not_found").put("memory_id", memoryId), mutatedGraph = false)
        val sensitivity = existing.attributes["sensitivity"]?.lowercase() ?: "low"
        val sensitivityCheck = enforceSensitivityPolicy(
            sensitivity = sensitivity,
            allowHighSensitivity = args.optBoolean("allow_high_sensitivity", false)
        )
        if (sensitivityCheck != null) return ToolExecutionOutcome(sensitivityCheck, mutatedGraph = false)

        val deleted = memoryManager.deleteMemory(memoryId)
        return ToolExecutionOutcome(JSONObject().put("status", if (deleted) "deleted" else "memory_not_found").put("memory_id", memoryId), mutatedGraph = false)
    }

    private fun executeMemoryStatus(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val status = memoryManager.status()
        val payload = JSONObject()
            .put("mode", status.mode.name.lowercase())
            .put("session_turn_count", status.sessionTurnCount)
            .put("profile_memory_count", status.profileMemoryCount)
            .put("semantic_memory_count", status.semanticMemoryCount)
            .put("expiry_by_category_ms", JSONObject().apply {
                status.expiryByCategoryMs.forEach { (category, millis) -> put(category.name.lowercase(), millis) }
            })
        return ToolExecutionOutcome(payload, mutatedGraph = false)
    }

    private fun enforceSensitivityPolicy(sensitivity: String, allowHighSensitivity: Boolean): JSONObject? {
        return when (sensitivity.lowercase()) {
            "restricted" -> JSONObject()
                .put("error", "sensitivity_policy_violation")
                .put("message", "Mutating restricted memory is denied")
                .put("sensitivity", "restricted")
            "high" -> if (!allowHighSensitivity) {
                JSONObject()
                    .put("error", "sensitivity_policy_violation")
                    .put("message", "Mutating high-sensitivity memory requires allow_high_sensitivity=true")
                    .put("sensitivity", "high")
            } else null
            else -> null
        }
    }

    private fun memoryToJson(memory: MindNode): JSONObject {
        return JSONObject()
            .put("id", memory.id)
            .put("label", memory.label)
            .put("description", memory.description)
            .put("attributes", JSONObject().apply {
                memory.attributes.forEach { (key, value) -> put(key, value) }
            })
    }

    private fun executeFileWrite(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val path = invocation.argumentsJson.optString("path")
        val content = invocation.argumentsJson.optString("content")
        val file = resolveScoped(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        val payload = JSONObject().put("path", file.absolutePath).put("bytes_written", content.toByteArray().size)
        return ToolExecutionOutcome(payload, mutatedGraph = false)
    }

    private fun executeNotesTasks(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val action = invocation.argumentsJson.optString("action", "list").lowercase()
        val kind = invocation.argumentsJson.optString("kind", "notes").lowercase()
        val recordId = invocation.argumentsJson.optString("id").ifBlank { "${kind}_${System.currentTimeMillis()}" }

        val db = readDb()
        val bucket = db.optJSONArray(kind) ?: JSONArray().also { db.put(kind, it) }

        when (action) {
            "create", "upsert" -> {
                val entry = JSONObject()
                    .put("id", recordId)
                    .put("title", invocation.argumentsJson.optString("title"))
                    .put("body", invocation.argumentsJson.optString("body"))
                    .put("status", invocation.argumentsJson.optString("status", "open"))
                    .put("updated_at", Instant.now().toString())
                upsert(bucket, recordId, entry)
            }
            "delete" -> remove(bucket, recordId)
            "update" -> {
                val current = find(bucket, recordId) ?: JSONObject().put("id", recordId)
                invocation.argumentsJson.keys().forEach { key ->
                    if (key != "action" && key != "kind") current.put(key, invocation.argumentsJson.get(key))
                }
                current.put("updated_at", Instant.now().toString())
                upsert(bucket, recordId, current)
            }
            "list" -> Unit
            else -> {
                return ToolExecutionOutcome(
                    JSONObject()
                        .put("error", "unsupported_action")
                        .put("action", action)
                        .put("kind", kind),
                    mutatedGraph = false
                )
            }
        }

        writeDb(db)
        return ToolExecutionOutcome(JSONObject().put("kind", kind).put("items", bucket), mutatedGraph = false)
    }

    private fun executeCalendar(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val action = invocation.argumentsJson.optString("action", "list").lowercase()
        val store = readCalendar()
        val events = store.optJSONArray("events") ?: JSONArray().also { store.put("events", it) }
        when (action) {
            "list" -> Unit
            "create" -> {
                val id = invocation.argumentsJson.optString("id").ifBlank { "evt_${System.currentTimeMillis()}" }
                val event = JSONObject()
                    .put("id", id)
                    .put("title", invocation.argumentsJson.optString("title"))
                    .put("starts_at", invocation.argumentsJson.optString("starts_at"))
                    .put("ends_at", invocation.argumentsJson.optString("ends_at"))
                    .put("location", invocation.argumentsJson.optString("location"))
                upsert(events, id, event)
            }
            "delete" -> remove(events, invocation.argumentsJson.optString("id"))
            else -> {
                return ToolExecutionOutcome(
                    JSONObject()
                        .put("error", "unsupported_calendar_action")
                        .put("action", action),
                    mutatedGraph = false
                )
            }
        }
        writeCalendar(store)
        return ToolExecutionOutcome(JSONObject().put("events", events), mutatedGraph = false)
    }

    private fun executeWeb(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        val mode = invocation.argumentsJson.optString("mode", "fetch")
        if (isNetworkAvailable()) {
            outboundOperationQueue?.reconcile(kind = "web_fetch_search") { queued ->
                runWebMode(
                    mode = queued.payload.optString("mode", "fetch"),
                    url = queued.payload.optString("url"),
                    query = queued.payload.optString("query")
                )
            }
            val payload = runWebMode(
                mode = mode,
                url = invocation.argumentsJson.optString("url"),
                query = invocation.argumentsJson.optString("query")
            )
            return ToolExecutionOutcome(payload, mutatedGraph = false)
        }

        val queueId = outboundOperationQueue?.enqueue(
            kind = "web_fetch_search",
            payload = JSONObject()
                .put("mode", mode)
                .put("url", invocation.argumentsJson.optString("url"))
                .put("query", invocation.argumentsJson.optString("query"))
        )

        return ToolExecutionOutcome(
            JSONObject()
                .put("queued", true)
                .put("queue_id", queueId)
                .put("message", "Offline mode active. Web operation queued for reconciliation."),
            mutatedGraph = false
        )
    }

    private fun runWebMode(mode: String, url: String, query: String): JSONObject {
        return when (mode) {
            "fetch" -> {
                val text = httpGet(url)
                JSONObject().put("url", url).put("body", text.take(5000))
            }
            "search" -> {
                val ddgUrl = "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                val html = httpGet(ddgUrl)
                JSONObject().put("query", query).put("results_html", html.take(5000))
            }
            else -> JSONObject().put("error", "Unsupported web mode")
        }
    }

    private fun executeTerminal(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome {
        if (!allowTerminal) {
            return ToolExecutionOutcome(JSONObject().put("error", "terminal executor disabled"), mutatedGraph = false)
        }
        val command = invocation.argumentsJson.optString("command").trim()
        val prefix = command.substringBefore(" ")
        if (allowedCommandPrefixes.none { it == prefix }) {
            return ToolExecutionOutcome(JSONObject().put("error", "command_not_allowed").put("command", command), mutatedGraph = false)
        }

        val process = ProcessBuilder("bash", "-lc", command)
            .directory(appRoot)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ToolExecutionOutcome(
                JSONObject()
                    .put("command", command)
                    .put("error", "command_timeout")
                    .put("timeout_seconds", 15),
                mutatedGraph = false
            )
        }
        val output = process.inputStream.bufferedReader().readText()
        return ToolExecutionOutcome(
            JSONObject()
                .put("command", command)
                .put("exit_code", process.exitValue())
                .put("output", output.take(8000)),
            mutatedGraph = false
        )
    }

    private fun resolveScoped(path: String): File {
        val candidate = File(appRoot, path).canonicalFile
        val inScope = scopedDirectories.map { it.canonicalFile }.any { scope ->
            candidate.path == scope.path || candidate.path.startsWith(scope.path + File.separator)
        }
        check(inScope) { "Path is outside scoped directories: $path" }
        return candidate
    }

    private fun readDb(): JSONObject {
        if (!dbFile.exists()) return JSONObject().put("notes", JSONArray()).put("tasks", JSONArray())
        return JSONObject(dbFile.readText())
    }

    private fun writeDb(db: JSONObject) {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText(db.toString())
    }

    private fun readCalendar(): JSONObject {
        if (!calendarFile.exists()) return JSONObject().put("events", JSONArray())
        return JSONObject(calendarFile.readText())
    }

    private fun writeCalendar(db: JSONObject) {
        calendarFile.parentFile?.mkdirs()
        calendarFile.writeText(db.toString())
    }

    private fun find(array: JSONArray, id: String): JSONObject? {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") == id) return item
        }
        return null
    }

    private fun upsert(array: JSONArray, id: String, value: JSONObject) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                array.put(i, value)
                return
            }
        }
        array.put(value)
    }

    private fun remove(array: JSONArray, id: String) {
        for (i in array.length() - 1 downTo 0) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("id") == id) array.remove(i)
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
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
    private val allowTerminal: Boolean = false,
    private val allowedCommandPrefixes: List<String> = listOf("echo", "pwd", "date")
) {

    private val dbFile = File(appRoot, "tooling/notes_tasks_db.json")
    private val calendarFile = File(appRoot, "tooling/calendar_events.json")

    fun specs(): List<ToolSpec> = listOf(
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
        return when (mode) {
            "fetch" -> {
                val url = invocation.argumentsJson.optString("url")
                val text = httpGet(url)
                ToolExecutionOutcome(JSONObject().put("url", url).put("body", text.take(5000)), mutatedGraph = false)
            }
            "search" -> {
                val query = invocation.argumentsJson.optString("query")
                val ddgUrl = "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                val html = httpGet(ddgUrl)
                ToolExecutionOutcome(JSONObject().put("query", query).put("results_html", html.take(5000)), mutatedGraph = false)
            }
            else -> ToolExecutionOutcome(JSONObject().put("error", "Unsupported web mode"), mutatedGraph = false)
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

package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import com.kaleaon.mnxmindmaker.util.tooling.FoundationalTools
import com.kaleaon.mnxmindmaker.util.tooling.OutboundOperationQueue
import com.kaleaon.mnxmindmaker.util.tooling.PolicyDecisionType
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoundationalToolsTest {

    @Test
    fun `tool specs expose input schema and executable handlers with policy enforcement`() {
        val root = createTempDir(prefix = "tooling-test")
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root), allowTerminal = false)
        val specs = tools.specs()
        val handlers = tools.handlers()
        val policy = ToolPolicyEngine(specs.associateBy { it.name })

        specs.forEach { spec ->
            assertTrue(spec.inputSchema.optString("type", "object") == "object")
            assertNotNull(handlers[spec.name])
        }

        val readDecision = policy.evaluate(ToolInvocation("r", "file_read"), MindGraph())
        val writeDecision = policy.evaluate(ToolInvocation("w", "file_write"), MindGraph())
        val terminalDecision = policy.evaluate(ToolInvocation("t", "terminal_execute"), MindGraph())

        assertEquals(PolicyDecisionType.ALLOW, readDecision.type)
        assertEquals(PolicyDecisionType.REQUIRE_USER_APPROVAL, writeDecision.type)
        assertEquals(PolicyDecisionType.REQUIRE_USER_APPROVAL, terminalDecision.type)
    }

    @Test
    fun `file write then read works within scoped directory`() {
        val root = createTempDir(prefix = "tooling-test")
        val workspace = File(root, "workspace").also { it.mkdirs() }
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(workspace))
        val handlers = tools.handlers()

        handlers.getValue("file_write").execute(
            ToolInvocation("1", "file_write", JSONObject().put("path", "workspace/notes/a.txt").put("content", "hello")),
            MindGraph()
        )

        val read = handlers.getValue("file_read").execute(
            ToolInvocation("2", "file_read", JSONObject().put("path", "workspace/notes/a.txt")),
            MindGraph()
        )

        assertEquals("hello", read.contentJson.getString("content"))
    }

    @Test
    fun `notes task db creates entries`() {
        val root = createTempDir(prefix = "tooling-test")
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root))
        val result = tools.handlers().getValue("notes_tasks_db").execute(
            ToolInvocation(
                "1",
                "notes_tasks_db",
                JSONObject()
                    .put("action", "create")
                    .put("kind", "tasks")
                    .put("id", "t1")
                    .put("title", "Ship tooling")
            ),
            MindGraph()
        )

        val items = result.contentJson.getJSONArray("items")
        assertTrue(items.length() >= 1)
        assertEquals("t1", items.getJSONObject(0).getString("id"))
    }

    @Test
    fun `unsupported notes action returns structured error`() {
        val root = createTempDir(prefix = "tooling-test")
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root))
        val result = tools.handlers().getValue("notes_tasks_db").execute(
            ToolInvocation(
                "1",
                "notes_tasks_db",
                JSONObject()
                    .put("action", "not_real")
                    .put("kind", "tasks")
            ),
            MindGraph()
        )

        assertEquals("unsupported_action", result.contentJson.getString("error"))
        assertEquals("not_real", result.contentJson.getString("action"))
    }

    @Test
    fun `terminal command timeout returns error payload`() {
        val root = createTempDir(prefix = "tooling-test")
        val tools = FoundationalTools(
            appRoot = root,
            scopedDirectories = listOf(root),
            allowTerminal = true,
            allowedCommandPrefixes = listOf("sleep")
        )
        val result = tools.handlers().getValue("terminal_execute").execute(
            ToolInvocation("1", "terminal_execute", JSONObject().put("command", "sleep 20")),
            MindGraph()
        )

        assertEquals("command_timeout", result.contentJson.getString("error"))
        assertEquals(15, result.contentJson.getInt("timeout_seconds"))
    }

    @Test
    fun `memory upsert and search expose memory tools`() {
        val root = createTempDir(prefix = "tooling-test")
        val memoryManager = MemoryManager().apply {
            setPolicy(
                MemoryManager.MemoryPolicySettings(
                    mode = MemoryManager.MemoryPolicyMode.PERSISTENT
                )
            )
        }
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root), memoryManager = memoryManager)
        val handlers = tools.handlers()

        val upsert = handlers.getValue("memory_upsert").execute(
            ToolInvocation(
                "1",
                "memory_upsert",
                JSONObject()
                    .put("memory_id", "m1")
                    .put("category", "semantic")
                    .put("value", "Loves Kotlin and typed APIs")
                    .put("sensitivity", "low")
            ),
            MindGraph()
        )
        assertEquals("upserted", upsert.contentJson.getString("status"))

        val search = handlers.getValue("memory_search").execute(
            ToolInvocation("2", "memory_search", JSONObject().put("query", "Kotlin").put("limit", 5)),
            MindGraph()
        )
        assertTrue(search.contentJson.getJSONArray("results").length() >= 1)
    }

    @Test
    fun `memory mutating operations enforce sensitivity policy`() {
        val root = createTempDir(prefix = "tooling-test")
        val memoryManager = MemoryManager().apply {
            setPolicy(
                MemoryManager.MemoryPolicySettings(
                    mode = MemoryManager.MemoryPolicyMode.PERSISTENT
                )
            )
        }
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root), memoryManager = memoryManager)
        val handlers = tools.handlers()

        val denied = handlers.getValue("memory_upsert").execute(
            ToolInvocation(
                "1",
                "memory_upsert",
                JSONObject()
                    .put("memory_id", "m2")
                    .put("category", "profile")
                    .put("value", "medical details")
                    .put("sensitivity", "restricted")
            ),
            MindGraph()
        )

        assertEquals("sensitivity_policy_violation", denied.contentJson.getString("error"))
    }


    @Test
    fun `memory upsert masks sensitive entities using moderation pipeline`() {
        val root = createTempDir(prefix = "tooling-test")
        val memoryManager = MemoryManager().apply {
            setPolicy(MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT))
        }
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root), memoryManager = memoryManager)

        val upsert = tools.handlers().getValue("memory_upsert").execute(
            ToolInvocation(
                "1",
                "memory_upsert",
                JSONObject()
                    .put("memory_id", "profile-contact")
                    .put("category", "profile")
                    .put("value", "Reach me at jane.doe@example.com")
                    .put("sensitivity", "low")
    @Test
    fun `web fetch queues outbound operation when offline`() {
        val root = createTempDir(prefix = "tooling-test")
        val queue = OutboundOperationQueue()
        val tools = FoundationalTools(
            appRoot = root,
            scopedDirectories = listOf(root),
            outboundOperationQueue = queue,
            isNetworkAvailable = { false }
        )

        val result = tools.handlers().getValue("web_fetch_search").execute(
            ToolInvocation(
                "1",
                "web_fetch_search",
                JSONObject().put("mode", "fetch").put("url", "https://example.com")
            ),
            MindGraph()
        )

        assertEquals("upserted", upsert.contentJson.getString("status"))
        val stored = memoryManager.getMemory("profile-contact")
        assertTrue(stored?.description?.contains("[REDACTED:EMAIL]") == true)
        assertFalse(stored?.description?.contains("jane.doe@example.com") == true)
    }

    @Test
    fun `memory upsert denies disallowed sensitive entities through moderation`() {
        val root = createTempDir(prefix = "tooling-test")
        val memoryManager = MemoryManager().apply {
            setPolicy(MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT))
        }
        val tools = FoundationalTools(appRoot = root, scopedDirectories = listOf(root), memoryManager = memoryManager)

        val denied = tools.handlers().getValue("memory_upsert").execute(
            ToolInvocation(
                "1",
                "memory_upsert",
                JSONObject()
                    .put("memory_id", "bad-memory")
                    .put("category", "semantic")
                    .put("value", "SSN 123-45-6789")
                    .put("sensitivity", "low")
            ),
            MindGraph()
        )

        assertEquals("moderation_denied", denied.contentJson.getString("error"))
        assertEquals(null, memoryManager.getMemory("bad-memory"))
        assertTrue(result.contentJson.getBoolean("queued"))
        assertEquals(1, queue.pending("web_fetch_search").size)
    }

    @Test
    fun `outbound operation queue reconciles successfully`() {
        val queue = OutboundOperationQueue()
        queue.enqueue("web_fetch_search", JSONObject().put("mode", "fetch").put("url", "https://example.com"))

        val reconciled = queue.reconcile("web_fetch_search") { queued ->
            JSONObject().put("id", queued.id).put("status", "done")
        }

        assertEquals(1, reconciled.size)
        assertEquals(0, queue.pending("web_fetch_search").size)
        assertEquals("done", reconciled.first().result?.optString("status"))
    }

}

package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.util.tooling.FoundationalTools
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoundationalToolsTest {

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
}

package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.util.tooling.SkillManifestValidator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenClawSkillPackManifestTest {

    @Test
    fun `openclaw extension pack validates and exposes read only tool aliases`() {
        val manifestFile = File("app/src/main/assets/skills/openclaw_assistant_extension_pack.json")
        val root = JSONObject(manifestFile.readText())

        val validator = SkillManifestValidator(
            approvedHandlerIds = setOf(
                "graph.read.get_summary",
                "graph.read.list_nodes",
                "memory.read.search"
            )
        )

        val (manifest, issues) = validator.validate("skills/openclaw_assistant_extension_pack.json", root)

        assertTrue("Expected no validation issues: $issues", issues.isEmpty())
        assertNotNull(manifest)
        assertEquals("openclaw-assistant-extension", manifest!!.packId)
        assertEquals(3, manifest.tools.size)
        assertTrue(manifest.tools.all { it.playbook?.source?.contains("openclaw-android-assistant") == true })
    }
}

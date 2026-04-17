package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.util.tooling.SkillManifestValidator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestValidatorTest {

    private val validator = SkillManifestValidator(
        approvedHandlerIds = setOf("graph.read.get_summary")
    )

    @Test
    fun `accepts optional playbook metadata when valid`() {
        val json = JSONObject(
            """
            {
              "pack_id": "playbook-pack",
              "version": "1.0.0",
              "enabled": true,
              "tools": [
                {
                  "name": "graph_overview",
                  "description": "Get graph summary",
                  "handler_id": "graph.read.get_summary",
                  "input_schema": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": false
                  },
                  "playbook": {
                    "summary": "Deterministic read path",
                    "steps": [
                      "Read graph summary",
                      "Return concise response"
                    ],
                    "source": "https://github.com/agents-io/PokeClaw"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val (manifest, issues) = validator.validate("skills/valid.json", json)

        assertTrue(issues.isEmpty())
        assertNotNull(manifest)
        assertNotNull(manifest!!.tools.first().playbook)
        assertEquals(2, manifest.tools.first().playbook!!.steps.size)
    }

    @Test
    fun `rejects playbook with blank or missing steps`() {
        val json = JSONObject(
            """
            {
              "pack_id": "playbook-pack",
              "version": "1.0.0",
              "enabled": true,
              "tools": [
                {
                  "name": "graph_overview",
                  "description": "Get graph summary",
                  "handler_id": "graph.read.get_summary",
                  "input_schema": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": false
                  },
                  "playbook": {
                    "summary": "Broken",
                    "steps": ["   "]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val (manifest, issues) = validator.validate("skills/invalid.json", json)

        assertEquals(null, manifest)
        assertFalse(issues.isEmpty())
        assertTrue(issues.any { it.message.contains("playbook.steps") })
    }
}

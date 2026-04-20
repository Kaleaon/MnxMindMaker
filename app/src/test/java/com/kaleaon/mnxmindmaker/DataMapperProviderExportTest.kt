package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DataMapper
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMapperProviderExportTest {

    @Test
    fun `provider style chat export is clustered into voices and viewpoints`() {
        val exportJson = """
            {
              "messages": [
                {"role":"user","content":"I want a planner for launch readiness"},
                {"role":"assistant","model":"gpt-4o","content":"Let's create a milestone-based checklist."},
                {"role":"assistant","model":"gpt-4o","content":"We should include rollback and comms steps."},
                {"role":"assistant","model":"claude-3","content":"I suggest adding risk scoring and stakeholder updates."}
              ]
            }
        """.trimIndent()

        val graph = DataMapper.fromJson(exportJson, "Imported Export")

        assertTrue(graph.nodes.any { it.attributes["generated_by_importer"] == "provider_export" })
        assertTrue(graph.nodes.any { it.type == NodeType.PERSONALITY && it.attributes["semantic_subtype"] == "imported_voice_profile" })
        assertTrue(graph.nodes.any { it.type == NodeType.BELIEF && it.attributes["semantic_subtype"] == "imported_viewpoint" })
        assertTrue(graph.nodes.any { it.type == NodeType.MEMORY && it.attributes["semantic_subtype"] == "imported_turn" })
    }
}

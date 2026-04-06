package com.kaleaon.mnxmindmaker.persona.validation

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaReadinessValidatorTest {

    @Test
    fun `passes when required persona components exist`() {
        val validator = PersonaReadinessValidator()
        val graph = MindGraph(
            name = "Valid Persona",
            nodes = mutableListOf(
                node("Identity", NodeType.IDENTITY),
                node("Care", NodeType.VALUE),
                node("Truth-seeking", NodeType.BELIEF),
                node("Origin memory", NodeType.MEMORY)
            )
        )

        val result = validator.validate(graph)

        assertTrue(result.passed)
        assertTrue(result.blockingErrors.isEmpty())
    }

    @Test
    fun `fails with blocking errors when required structures are missing`() {
        val validator = PersonaReadinessValidator()
        val graph = MindGraph(
            name = "Incomplete",
            nodes = mutableListOf(node("", NodeType.IDENTITY))
        )

        val result = validator.validate(graph)

        assertFalse(result.passed)
        assertTrue(result.blockingErrors.any { it.code == PersonaIssueCode.IDENTITY_MISSING })
        assertTrue(result.blockingErrors.any { it.code == PersonaIssueCode.VALUES_MISSING })
        assertTrue(result.blockingErrors.any { it.code == PersonaIssueCode.BELIEFS_MISSING })
        assertTrue(result.blockingErrors.any { it.code == PersonaIssueCode.MEMORY_COVERAGE_LOW })
        assertTrue(result.suggestedFixes.isNotEmpty())
    }

    @Test
    fun `warns when memory ratio is below threshold even if minimum nodes met`() {
        val validator = PersonaReadinessValidator(
            PersonaReadinessPolicy(minMemoryNodes = 1, minMemoryNodeShare = 0.4f)
        )
        val graph = MindGraph(
            name = "Sparse Memory",
            nodes = mutableListOf(
                node("Identity", NodeType.IDENTITY),
                node("Integrity", NodeType.VALUE),
                node("Learning is possible", NodeType.BELIEF),
                node("Episode one", NodeType.MEMORY),
                node("Trait", NodeType.PERSONALITY)
            )
        )

        val result = validator.validate(graph)

        assertTrue(result.passed)
        assertTrue(result.warnings.any { it.code == PersonaIssueCode.MEMORY_COVERAGE_LOW })
    }

    @Test
    fun `applies optional dimensional quality threshold warnings`() {
        val validator = PersonaReadinessValidator(
            PersonaReadinessPolicy(
                dimensionalQualityThresholds = mapOf(NodeType.BELIEF to 0.8f),
                minDimensionsPerQualifiedNode = 1
            )
        )
        val graph = MindGraph(
            name = "Dimension Check",
            nodes = mutableListOf(
                node("Identity", NodeType.IDENTITY),
                node("Compassion", NodeType.VALUE),
                node("Belief", NodeType.BELIEF, dimensions = mapOf("confidence" to 0.2f)),
                node("Episode", NodeType.MEMORY)
            )
        )

        val result = validator.validate(graph)

        assertTrue(result.passed)
        assertTrue(result.warnings.any { it.code == PersonaIssueCode.DIMENSIONAL_QUALITY_LOW })
    }

    @Test
    fun `ui projection exposes viewmodel friendly summary`() {
        val validator = PersonaReadinessValidator()
        val graph = MindGraph(
            name = "Broken",
            nodes = mutableListOf(node("", NodeType.IDENTITY))
        )

        val uiState = validator.validateForUi(graph)

        assertEquals("BLOCKED", uiState.status)
        assertFalse(uiState.passed)
        assertTrue(uiState.blockingErrors.isNotEmpty())
        assertTrue(uiState.suggestedFixes.isNotEmpty())
    }

    @Test
    fun `handles empty graph edge case`() {
        val validator = PersonaReadinessValidator()
        val graph = MindGraph(name = "Empty", nodes = mutableListOf())

        val result = validator.validate(graph)

        assertFalse(result.passed)
        assertTrue(result.blockingErrors.map { it.code }.containsAll(
            listOf(
                PersonaIssueCode.IDENTITY_MISSING,
                PersonaIssueCode.VALUES_MISSING,
                PersonaIssueCode.BELIEFS_MISSING,
                PersonaIssueCode.MEMORY_COVERAGE_LOW
            )
        ))
    }

    private fun node(
        label: String,
        type: NodeType,
        description: String = "",
        dimensions: Map<String, Float> = emptyMap()
    ): MindNode = MindNode(label = label, type = type, description = description, dimensions = dimensions)
}

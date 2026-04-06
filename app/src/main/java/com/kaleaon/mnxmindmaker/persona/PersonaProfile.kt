package com.kaleaon.mnxmindmaker.persona

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindNode
import java.util.UUID

/**
 * Persona profile projection of an in-app mind graph plus deployment metadata.
 */
data class PersonaProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lifecycleState: PersonaLifecycleState = PersonaLifecycleState.DRAFT,
    val metadata: PersonaMetadata,
    val identity: PersonaIdentitySection,
    val values: List<PersonaValueSection>,
    val beliefs: List<PersonaBeliefSection>,
    val memories: List<PersonaMemorySection>,
    val dimensionalRefs: List<PersonaDimensionalRef>,
    val graphSnapshot: PersonaGraphSnapshot
)

data class PersonaMetadata(
    val owner: String,
    val version: String,
    val createdAt: Long,
    val updatedAt: Long,
    val validationSummary: PersonaValidationSummary
)

data class PersonaValidationSummary(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val validatedAt: Long = System.currentTimeMillis()
)

data class PersonaIdentitySection(
    val nodeId: String,
    val name: String,
    val biography: String,
    val attributes: Map<String, String>
)

data class PersonaValueSection(
    val nodeId: String,
    val name: String,
    val description: String,
    val weight: Float,
    val attributes: Map<String, String>
)

data class PersonaBeliefSection(
    val nodeId: String,
    val statement: String,
    val confidence: Float,
    val evidence: List<String>,
    val attributes: Map<String, String>
)

data class PersonaMemorySection(
    val nodeId: String,
    val content: String,
    val source: String,
    val timestamp: String,
    val attributes: Map<String, String>
)

data class PersonaDimensionalRef(
    val nodeId: String,
    val dimension: String,
    val value: Float
)

data class PersonaGraphSnapshot(
    val graphId: String,
    val graphName: String,
    val nodes: List<MindNode>,
    val edges: List<MindEdge>
)

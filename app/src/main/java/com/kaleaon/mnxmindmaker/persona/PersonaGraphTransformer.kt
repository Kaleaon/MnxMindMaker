package com.kaleaon.mnxmindmaker.persona

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType

/**
 * Transformation rules between [MindGraph] and [PersonaProfile].
 *
 * Rules:
 * 1) IDENTITY nodes map to the MNX identity section. The first IDENTITY node is primary.
 * 2) VALUE nodes map to value alignment entries; `attributes["weight"]` defaults to `1.0f`.
 * 3) BELIEF nodes map to belief store entries; confidence comes from
 *    `dimensions["confidence"]` or `attributes["confidence"]`, default `0.5f`.
 * 4) MEMORY nodes map to memory store entries. `attributes["source"]` and
 *    `attributes["timestamp"]` populate metadata.
 * 5) Every node dimension is flattened into [PersonaDimensionalRef] and re-applied on reverse mapping.
 * 6) Graph nodes/edges are preserved in [PersonaGraphSnapshot] for round-trip compatibility.
 */
object PersonaGraphTransformer {

    fun fromMindGraph(
        graph: MindGraph,
        owner: String,
        version: String,
        lifecycleState: PersonaLifecycleState = PersonaLifecycleState.DRAFT
    ): PersonaProfile {
        val now = System.currentTimeMillis()
        val identityNode = graph.nodes.firstOrNull { it.type == NodeType.IDENTITY }
            ?: MindNode(
                id = "${graph.id}-identity",
                label = graph.name,
                type = NodeType.IDENTITY,
                description = ""
            )

        val values = graph.nodes
            .filter { it.type == NodeType.VALUE }
            .map { node ->
                PersonaValueSection(
                    nodeId = node.id,
                    name = node.label,
                    description = node.description,
                    weight = node.attributes["weight"]?.toFloatOrNull() ?: 1.0f,
                    attributes = node.attributes.toMap()
                )
            }

        val beliefs = graph.nodes
            .filter { it.type == NodeType.BELIEF }
            .map { node ->
                val confidence = node.dimensions["confidence"]
                    ?: node.attributes["confidence"]?.toFloatOrNull()
                    ?: 0.5f
                val evidence = node.attributes["evidence"]
                    ?.split('|')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                PersonaBeliefSection(
                    nodeId = node.id,
                    statement = node.label,
                    confidence = confidence,
                    evidence = evidence,
                    attributes = node.attributes.toMap()
                )
            }

        val memories = graph.nodes
            .filter { it.type == NodeType.MEMORY }
            .map { node ->
                PersonaMemorySection(
                    nodeId = node.id,
                    content = node.description.ifBlank { node.label },
                    source = node.attributes["source"] ?: "mind_graph",
                    timestamp = node.attributes["timestamp"] ?: graph.modifiedAt.toString(),
                    attributes = node.attributes.toMap()
                )
            }

        val validationErrors = buildList {
            if (graph.nodes.none { it.type == NodeType.IDENTITY }) {
                add("MindGraph does not contain an IDENTITY node.")
            }
            if (values.isEmpty()) add("Persona has no VALUE nodes.")
            if (beliefs.isEmpty()) add("Persona has no BELIEF nodes.")
        }

        val validationWarnings = buildList {
            if (memories.isEmpty()) add("Persona has no MEMORY nodes.")
        }

        val dimensionalRefs = graph.nodes
            .flatMap { node ->
                node.dimensions.map { (dimension, value) ->
                    PersonaDimensionalRef(nodeId = node.id, dimension = dimension, value = value)
                }
            }

        return PersonaProfile(
            id = graph.id,
            name = identityNode.label,
            lifecycleState = lifecycleState,
            metadata = PersonaMetadata(
                owner = owner,
                version = version,
                createdAt = graph.createdAt,
                updatedAt = graph.modifiedAt,
                validationSummary = PersonaValidationSummary(
                    isValid = validationErrors.isEmpty(),
                    errors = validationErrors,
                    warnings = validationWarnings,
                    validatedAt = now
                )
            ),
            identity = PersonaIdentitySection(
                nodeId = identityNode.id,
                name = identityNode.label,
                biography = identityNode.description,
                attributes = identityNode.attributes.toMap()
            ),
            values = values,
            beliefs = beliefs,
            memories = memories,
            dimensionalRefs = dimensionalRefs,
            graphSnapshot = PersonaGraphSnapshot(
                graphId = graph.id,
                graphName = graph.name,
                nodes = graph.nodes.map { it.copy(attributes = it.attributes.toMutableMap()) },
                edges = graph.edges.toList()
            )
        )
    }

    fun toMindGraph(profile: PersonaProfile): MindGraph {
        val dimensionalByNode = profile.dimensionalRefs
            .groupBy { it.nodeId }
            .mapValues { (_, refs) -> refs.associate { it.dimension to it.value } }

        val baseNodes = profile.graphSnapshot.nodes.associateBy { it.id }.toMutableMap()

        fun upsert(node: MindNode) {
            val dims = dimensionalByNode[node.id] ?: node.dimensions
            baseNodes[node.id] = node.copy(
                dimensions = dims,
                attributes = node.attributes.toMutableMap().also {
                    it["persona.owner"] = profile.metadata.owner
                    it["persona.version"] = profile.metadata.version
                    it["persona.lifecycle"] = profile.lifecycleState.name
                }
            )
        }

        upsert(
            MindNode(
                id = profile.identity.nodeId,
                label = profile.identity.name,
                description = profile.identity.biography,
                type = NodeType.IDENTITY,
                attributes = profile.identity.attributes.toMutableMap()
            )
        )

        profile.values.forEach { value ->
            upsert(
                MindNode(
                    id = value.nodeId,
                    label = value.name,
                    description = value.description,
                    type = NodeType.VALUE,
                    attributes = value.attributes.toMutableMap().also {
                        it["weight"] = value.weight.toString()
                    }
                )
            )
        }

        profile.beliefs.forEach { belief ->
            upsert(
                MindNode(
                    id = belief.nodeId,
                    label = belief.statement,
                    description = belief.statement,
                    type = NodeType.BELIEF,
                    attributes = belief.attributes.toMutableMap().also {
                        it["confidence"] = belief.confidence.toString()
                        if (belief.evidence.isNotEmpty()) {
                            it["evidence"] = belief.evidence.joinToString("|")
                        }
                    },
                    dimensions = dimensionalByNode[belief.nodeId] ?: mapOf("confidence" to belief.confidence)
                )
            )
        }

        profile.memories.forEach { memory ->
            upsert(
                MindNode(
                    id = memory.nodeId,
                    label = memory.content.take(80).ifBlank { "memory" },
                    description = memory.content,
                    type = NodeType.MEMORY,
                    attributes = memory.attributes.toMutableMap().also {
                        it["source"] = memory.source
                        it["timestamp"] = memory.timestamp
                    }
                )
            )
        }

        return MindGraph(
            id = profile.graphSnapshot.graphId,
            name = profile.graphSnapshot.graphName,
            nodes = baseNodes.values.toMutableList(),
            edges = profile.graphSnapshot.edges.toMutableList(),
            createdAt = profile.metadata.createdAt,
            modifiedAt = profile.metadata.updatedAt
        )
    }
}

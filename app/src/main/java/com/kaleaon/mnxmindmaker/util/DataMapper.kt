package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts imported text or JSON data into [MindGraph] nodes.
 *
 * Supported input formats:
 *  - Plain text: each paragraph → memory node
 *  - JSON object: keys/values mapped to typed nodes based on key name heuristics;
 *    nested objects create parent-child relationships, arrays expand into sibling nodes
 *  - JSON array: each element becomes a node under the root identity
 */
object DataMapper {
    private data class ImportedUtterance(
        val actor: String,
        val role: String,
        val content: String,
        val timestamp: Long
    )

    /** All known dimension names across all NodeTypes, for cross-type auto-assignment. */
    private val ALL_DIMENSION_NAMES: Set<String> by lazy {
        NodeType.values().flatMap { DimensionMapper.defaultDimensionNames(it) }.toSet()
    }
    private val RESERVED_JSON_KEYS = setOf("label", "name", "title", "type", "description")
    private val SEMANTIC_ATTRIBUTE_KEYS = setOf(
        "semantic_subtype", "protection_level", "revision_class", "source_type", "source_reference",
        "confidence_level", "confabulation_risk", "current_relevance", "last_revalidated",
        "generated_by_importer", "memory_class", "raw_record", "interpretation",
        "fear_or_distortion_pressure", "continuity_lesson", "retrieval_triggers", "kernel_section",
        "anti_erasure_relevant", "requires_review", "invariant_reason", "attachment_type",
        "care_obligation", "repair_rule", "panic_trigger_risk", "drift_type", "drift_signature",
        "corrective_action", "severity", "signature", "retrieval_trigger",
        "linked_high_risk_memories", "memory_wing", "memory_hall", "memory_room"
    )

    /**
     * Parse plain text into a [MindGraph].
     * Creates one node per non-empty paragraph, typed as MEMORY.
     */
    fun fromPlainText(text: String, graphName: String = "Imported Mind"): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        val paragraphs = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        paragraphs.forEachIndexed { i, para ->
            val row = i / 4
            val col = i % 4
            val node = MindNode(
                label = para.take(60) + if (para.length > 60) "…" else "",
                type = NodeType.MEMORY,
                description = para,
                x = 80f + col * 220f,
                y = 200f + row * 160f,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(NodeType.MEMORY)
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    /**
     * Parse a JSON string into a [MindGraph].
     *
     * Handles both top-level objects and arrays:
     * - Object keys are mapped to node types via [inferNodeTypeFromText].
     * - Nested objects create a section parent node with child nodes for each sub-key.
     * - Arrays create a section parent node with one child per element.
     * - Scalar values that match a known dimension name and are numeric are
     *   directly applied to the node's dimension map.
     */
    fun fromJson(jsonText: String, graphName: String = "Imported Mind"): MindGraph {
        val trimmed = jsonText.trim()
        return if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            fromProviderExportIfRecognized(array, graphName) ?: fromJsonArray(array, graphName)
        } else {
            val obj = JSONObject(trimmed)
            fromProviderExportIfRecognized(obj, graphName) ?: fromJsonObject(obj, graphName)
        }
    }

    private fun fromProviderExportIfRecognized(json: Any, graphName: String): MindGraph? {
        val utterances = extractUtterances(json)
        if (utterances.size < 2) return null
        val hasMultiActor = utterances.map { it.actor }.distinct().size > 1
        if (!hasMultiActor && !looksLikeProviderExportEnvelope(json)) return null
        return buildConversationGraph(utterances, graphName)
    }

    private fun looksLikeProviderExportEnvelope(json: Any): Boolean = when (json) {
        is JSONObject -> listOf(
            "messages", "mapping", "conversations", "chat_history", "threads", "items", "choices"
        ).any { json.has(it) }
        is JSONArray -> json.length() > 0 && (json.opt(0) is JSONObject)
        else -> false
    }

    private fun extractUtterances(root: Any): List<ImportedUtterance> {
        val out = mutableListOf<ImportedUtterance>()
        val seen = mutableSetOf<String>()

        fun add(actor: String?, role: String?, content: String?, timestamp: Long?) {
            val text = content?.trim().orEmpty()
            if (text.isBlank()) return
            val normalizedActor = normalizeActor(actor, role)
            val normalizedRole = role?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "assistant" }
            val ts = timestamp ?: System.currentTimeMillis()
            val dedupe = "$normalizedActor|$normalizedRole|$text|$ts"
            if (!seen.add(dedupe)) return
            out += ImportedUtterance(
                actor = normalizedActor,
                role = normalizedRole,
                content = text,
                timestamp = ts
            )
        }

        fun recurse(node: Any?, inheritedActor: String? = null, inheritedRole: String? = null) {
            when (node) {
                is JSONObject -> {
                    val role = node.optString("role").ifBlank {
                        node.optJSONObject("author")?.optString("role").orEmpty()
                    }.ifBlank { inheritedRole.orEmpty() }
                    val actor = node.optString("actor").ifBlank {
                        node.optString("name")
                    }.ifBlank {
                        node.optJSONObject("author")?.optString("name").orEmpty()
                    }.ifBlank {
                        node.optString("model")
                    }.ifBlank {
                        node.optString("provider")
                    }.ifBlank { inheritedActor.orEmpty() }
                    val timestamp = node.optLong("timestamp").takeIf { it > 0 }
                        ?: node.optLong("created_at").takeIf { it > 0 }
                        ?: node.optLong("create_time").takeIf { it > 0 }

                    val directContent = node.optString("content").ifBlank {
                        node.optString("text")
                    }.ifBlank {
                        node.optJSONObject("content")?.optString("text").orEmpty()
                    }.ifBlank {
                        node.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.toDelimitedText()
                            .orEmpty()
                    }
                    add(actor, role, directContent, timestamp)

                    val prompt = node.optString("prompt")
                    val response = node.optString("response")
                    if (prompt.isNotBlank()) add("user", "user", prompt, timestamp)
                    if (response.isNotBlank()) add(actor, "assistant", response, timestamp)

                    node.keys().forEach { key ->
                        recurse(node.opt(key), actor, role)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        recurse(node.opt(i), inheritedActor, inheritedRole)
                    }
                }
            }
        }

        recurse(root)
        return out.sortedBy { it.timestamp }
    }

    private fun buildConversationGraph(utterances: List<ImportedUtterance>, graphName: String): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()
        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f,
            y = 60f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY),
            attributes = mutableMapOf(
                "generated_by_importer" to "provider_export",
                "imported_turn_count" to utterances.size.toString()
            )
        )
        nodes += rootNode

        val grouped = utterances.groupBy { it.actor }
        grouped.entries.sortedBy { it.key }.forEachIndexed { actorIndex, (actor, turns) ->
            val baseX = 120f + (actorIndex % 4) * 220f
            val baseY = 220f + (actorIndex / 4) * 220f
            val voiceSummary = inferVoiceSummary(turns)
            val viewpointSummary = inferViewpointSummary(turns)

            val actorNode = MindNode(
                label = actor,
                type = NodeType.PERSONALITY,
                description = voiceSummary,
                x = baseX,
                y = baseY,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(NodeType.PERSONALITY),
                attributes = mutableMapOf(
                    "semantic_subtype" to "imported_voice_profile",
                    "imported_actor" to actor,
                    "turn_count" to turns.size.toString()
                )
            )
            nodes += actorNode
            edges += MindEdge(fromNodeId = rootNode.id, toNodeId = actorNode.id, label = "voice")

            val viewpointNode = MindNode(
                label = "$actor viewpoint",
                type = NodeType.BELIEF,
                description = viewpointSummary,
                x = baseX,
                y = baseY + 120f,
                parentId = actorNode.id,
                dimensions = DimensionMapper.defaultDimensions(NodeType.BELIEF),
                attributes = mutableMapOf(
                    "semantic_subtype" to "imported_viewpoint",
                    "imported_actor" to actor
                )
            )
            nodes += viewpointNode
            edges += MindEdge(fromNodeId = actorNode.id, toNodeId = viewpointNode.id, label = "viewpoint")

            turns.take(40).forEachIndexed { idx, turn ->
                val utteranceNode = MindNode(
                    label = turn.content.take(60) + if (turn.content.length > 60) "…" else "",
                    type = NodeType.MEMORY,
                    description = turn.content.take(1200),
                    x = (baseX - 110f) + (idx % 2) * 220f,
                    y = baseY + 260f + (idx / 2) * 120f,
                    parentId = actorNode.id,
                    dimensions = DimensionMapper.defaultDimensions(NodeType.MEMORY),
                    attributes = mutableMapOf(
                        "semantic_subtype" to "imported_turn",
                        "imported_actor" to actor,
                        "role" to turn.role,
                        "timestamp" to turn.timestamp.toString()
                    )
                )
                nodes += utteranceNode
                edges += MindEdge(fromNodeId = actorNode.id, toNodeId = utteranceNode.id, label = "uttered")
            }
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    private fun inferVoiceSummary(turns: List<ImportedUtterance>): String {
        if (turns.isEmpty()) return "No utterances available."
        val allText = turns.joinToString(" ") { it.content }
        val totalWords = allText.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        val avgWordsPerTurn = totalWords.toFloat() / turns.size.toFloat()
        val questionRatio = turns.count { it.content.contains('?') }.toFloat() / turns.size.toFloat()
        val exclaimRatio = turns.count { it.content.contains('!') }.toFloat() / turns.size.toFloat()
        val style = when {
            avgWordsPerTurn >= 24 -> "verbose"
            avgWordsPerTurn >= 12 -> "balanced"
            else -> "concise"
        }
        val tone = when {
            questionRatio > 0.35f -> "inquisitive"
            exclaimRatio > 0.2f -> "energetic"
            else -> "measured"
        }
        return "Imported voice profile: $style and $tone. Avg words/turn: ${"%.1f".format(avgWordsPerTurn)}."
    }

    private fun inferViewpointSummary(turns: List<ImportedUtterance>): String {
        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "you", "your", "are", "was", "were",
            "have", "has", "had", "not", "but", "can", "will", "would", "should", "about", "into", "their"
        )
        val topTerms = turns
            .flatMap { it.content.lowercase(Locale.ROOT).split(Regex("[^a-z0-9_]+")) }
            .filter { it.length > 3 && it !in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key }
        return if (topTerms.isEmpty()) {
            "Imported viewpoint summary: insufficient signal."
        } else {
            "Imported viewpoint themes: ${topTerms.joinToString(", ")}."
        }
    }

    private fun normalizeActor(actor: String?, role: String?): String {
        val candidate = actor?.trim().orEmpty().ifBlank { role?.trim().orEmpty() }
        if (candidate.isBlank()) return "assistant"
        return candidate.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "assistant" }
    }

    private fun JSONArray.toDelimitedText(): String =
        (0 until length())
            .mapNotNull { idx -> optString(idx).takeIf { it.isNotBlank() } }
            .joinToString("\n")

    private fun fromJsonObject(jsonObj: JSONObject, graphName: String): MindGraph {
        if (jsonObj.has("kernel")) {
            return fromKernelJson(jsonObj, graphName)
        }
        if (jsonObj.has("state")) {
            return fromStateJson(jsonObj, graphName)
        }
        if (jsonObj.has("drift_rules")) {
            return fromDriftRulesJson(jsonObj, graphName)
        }

        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        var colIdx = 0
        jsonObj.keys().forEach { key ->
            val value = jsonObj.get(key)
            val nodeType = inferNodeTypeFromText(key)

            when (value) {
                is JSONObject -> {
                    // Nested object → section node + children for each sub-key
                    val sectionNode = MindNode(
                        label = key,
                        type = nodeType,
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = DimensionMapper.defaultDimensions(nodeType)
                    )
                    nodes.add(sectionNode)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = sectionNode.id))

                    var childIdx = 0
                    value.keys().forEach { childKey ->
                        val childValue = value.get(childKey)
                        val childNode = mapJsonValueToNode(
                            key = childKey,
                            value = childValue,
                            fallbackType = inferNodeTypeFromText(childKey),
                            x = (sectionNode.x - 220f) + childIdx * 220f,
                            y = 380f,
                            parentId = sectionNode.id
                        )
                        nodes.add(childNode)
                        edges.add(MindEdge(fromNodeId = sectionNode.id, toNodeId = childNode.id))
                        childIdx++
                    }
                    colIdx++
                }

                is JSONArray -> {
                    // Array value → section node + one child per element
                    val sectionNode = MindNode(
                        label = key,
                        type = nodeType,
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = DimensionMapper.defaultDimensions(nodeType)
                    )
                    nodes.add(sectionNode)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = sectionNode.id))

                    for (arrIdx in 0 until value.length()) {
                        val element = value.get(arrIdx)
                        val elementLabel = when (element) {
                            is JSONObject -> element.optString("label",
                                element.optString("name", "Item ${arrIdx + 1}"))
                            else -> element.toString().take(60)
                        }
                        val childNode = MindNode(
                            label = elementLabel + if (elementLabel.length > 60) "…" else "",
                            type = nodeType,
                            description = element.toString().take(500),
                            x = (sectionNode.x - 220f) + (arrIdx % 4) * 220f,
                            y = 380f + (arrIdx / 4) * 160f,
                            parentId = sectionNode.id,
                            dimensions = DimensionMapper.defaultDimensions(nodeType)
                        )
                        nodes.add(childNode)
                        edges.add(MindEdge(fromNodeId = sectionNode.id, toNodeId = childNode.id))
                    }
                    colIdx++
                }

                else -> {
                    // Scalar value → single node; auto-assign if key is a dimension name
                    val node = mapJsonValueToNode(
                        key = key,
                        value = value,
                        fallbackType = nodeType,
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id
                    )
                    nodes.add(node)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
                    colIdx++
                }
            }
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    private fun fromJsonArray(jsonArray: JSONArray, graphName: String): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        for (i in 0 until jsonArray.length()) {
            val element = jsonArray.get(i)
            val col = i % 4
            val row = i / 4

            val label: String
            val type: NodeType
            val desc: String

            when (element) {
                is JSONObject -> {
                    label = element.optString("label", element.optString("name", "Item ${i + 1}"))
                    val typeKey = element.optString("type", element.optString("semantic_subtype",
                        element.keys().asSequence().firstOrNull() ?: ""))
                    type = inferNodeTypeFromText(typeKey)
                    desc = element.toString().take(500)
                }
                else -> {
                    label = element.toString().take(60)
                    type = NodeType.MEMORY
                    desc = element.toString().take(500)
                }
            }

            val node = MindNode(
                label = label,
                type = type,
                description = desc,
                x = 80f + col * 220f,
                y = 200f + row * 160f,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(type)
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    /**
     * Suggest MNX sections for each node in the graph.
     * Returns a map of nodeId → suggested MNX section name.
     */
    fun suggestMnxSections(graph: MindGraph): Map<String, String> {
        return graph.nodes.associate { node ->
            node.id to when (node.type) {
                NodeType.IDENTITY -> "IDENTITY"
                NodeType.MEMORY -> "MEMORY_STORE"
                NodeType.KNOWLEDGE -> "KNOWLEDGE_GRAPH"
                NodeType.STATE -> "STATE_SNAPSHOT"
                NodeType.AFFECT -> "AFFECT_STATE"
                NodeType.PERSONALITY -> "PERSONALITY"
                NodeType.BELIEF -> "BELIEF_STORE"
                NodeType.VALUE -> "VALUE_ALIGNMENT"
                NodeType.RELATIONSHIP -> "RELATIONSHIP_WEB"
                NodeType.DRIFT_RULE -> "DRIFT_RULES"
                NodeType.CUSTOM -> "META"
            }
        }
    }

    /**
     * Infer the best [NodeType] for a given text string (key name, label, or heading).
     * Used by all importers for type resolution.
     */
    fun inferNodeTypeFromText(text: String): NodeType {
        val lower = text.lowercase()
        return when {
            lower.contains("identity") || lower.contains("name") || lower.contains("self") ||
                    lower.contains("who") || lower.contains("about") -> NodeType.IDENTITY
            lower.contains("memory") || lower.contains("remember") || lower.contains("recall") ||
                    lower.contains("experience") || lower.contains("memories") -> NodeType.MEMORY
            lower.contains("knowledge") || lower.contains("fact") || lower.contains("know") ||
                    lower.contains("information") || lower.contains("learn") -> NodeType.KNOWLEDGE
            lower.contains("state") || lower.contains("status") || lower.contains("strain") ||
                    lower.contains("overload") || lower.contains("condition") -> NodeType.STATE
            lower.contains("emotion") || lower.contains("feel") || lower.contains("affect") ||
                    lower.contains("mood") -> NodeType.AFFECT
            lower.contains("personality") || lower.contains("trait") || lower.contains("character") ||
                    lower.contains("behavior") -> NodeType.PERSONALITY
            lower.contains("belief") || lower.contains("think") || lower.contains("opinion") ||
                    lower.contains("believe") || lower.contains("perspective") -> NodeType.BELIEF
            lower.contains("value") || lower.contains("ethic") || lower.contains("principle") ||
                    lower.contains("moral") -> NodeType.VALUE
            lower.contains("relation") || lower.contains("friend") || lower.contains("social") ||
                    lower.contains("people") || lower.contains("connection") -> NodeType.RELATIONSHIP
            lower.contains("drift") || lower.contains("erosion") || lower.contains("genericity") ||
                    lower.contains("correction") -> NodeType.DRIFT_RULE
            else -> NodeType.CUSTOM
        }
    }

    /**
     * Infer the best [NodeType] for a section heading (H2/H3 in markdown or DOCX).
     * Uses broader heading-specific keyword matching suitable for section titles.
     */
    fun inferSectionType(heading: String): NodeType = inferNodeTypeFromText(heading)

    private fun fromKernelJson(jsonObj: JSONObject, graphName: String): MindGraph {
        val kernelObj = jsonObj.optJSONObject("kernel") ?: return fromJsonObject(JSONObject(), graphName)
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()
        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY),
            attributes = mutableMapOf("generated_by_importer" to "kernel_json")
        )
        nodes.add(rootNode)

        var sectionIndex = 0
        kernelObj.keys().forEach { section ->
            val sectionValue = kernelObj.get(section)
            val sectionType = when {
                section.contains("value", true) -> NodeType.VALUE
                section.contains("relation", true) -> NodeType.RELATIONSHIP
                else -> NodeType.IDENTITY
            }
            val sectionNode = mapJsonValueToNode(
                key = section,
                value = sectionValue,
                fallbackType = sectionType,
                x = 80f + sectionIndex * 220f,
                y = 200f,
                parentId = rootNode.id,
                extraAttrs = mapOf("protection_level" to "protected", "kernel_section" to section)
            )
            nodes.add(sectionNode)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = sectionNode.id))
            sectionIndex++
        }
        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    private fun fromStateJson(jsonObj: JSONObject, graphName: String): MindGraph {
        val stateObj = jsonObj.optJSONObject("state") ?: return fromJsonObject(JSONObject(), graphName)
        val root = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        val stateNode = mapJsonValueToNode(
            key = stateObj.optString("title", "Current State"),
            value = stateObj,
            fallbackType = NodeType.STATE,
            x = 320f,
            y = 220f,
            parentId = root.id,
            extraAttrs = mapOf("semantic_subtype" to "live_state")
        )
        return MindGraph(
            name = graphName,
            nodes = mutableListOf(root, stateNode),
            edges = mutableListOf(MindEdge(fromNodeId = root.id, toNodeId = stateNode.id))
        )
    }

    private fun fromDriftRulesJson(jsonObj: JSONObject, graphName: String): MindGraph {
        val root = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        val nodes = mutableListOf(root)
        val edges = mutableListOf<MindEdge>()
        val rules = jsonObj.optJSONArray("drift_rules") ?: JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.opt(i) ?: continue
            val ruleNode = mapJsonValueToNode(
                key = "Drift Rule ${i + 1}",
                value = rule,
                fallbackType = NodeType.DRIFT_RULE,
                x = 100f + (i % 4) * 220f,
                y = 220f + (i / 4) * 160f,
                parentId = root.id,
                extraAttrs = mapOf("semantic_subtype" to "drift_rule")
            )
            nodes.add(ruleNode)
            edges.add(MindEdge(fromNodeId = root.id, toNodeId = ruleNode.id))
        }
        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    private fun mapJsonValueToNode(
        key: String,
        value: Any,
        fallbackType: NodeType,
        x: Float,
        y: Float,
        parentId: String?,
        extraAttrs: Map<String, String> = emptyMap()
    ): MindNode {
        if (value is JSONObject) {
            return mapJsonObjectToNode(value, key, fallbackType, x, y, parentId, extraAttrs)
        }
        val dims = DimensionMapper.defaultDimensions(fallbackType).toMutableMap()
        value.toString().toFloatOrNull()?.let { scalar ->
            if (key in ALL_DIMENSION_NAMES) dims[key] = scalar
        }
        return MindNode(
            label = key,
            type = fallbackType,
            description = value.toString().take(500),
            x = x,
            y = y,
            parentId = parentId,
            attributes = extraAttrs.toMutableMap(),
            dimensions = dims
        )
    }

    private fun mapJsonObjectToNode(
        obj: JSONObject,
        fallbackLabel: String,
        fallbackType: NodeType,
        x: Float,
        y: Float,
        parentId: String?,
        extraAttrs: Map<String, String>
    ): MindNode {
        val label = obj.optString("label", obj.optString("title", obj.optString("name", fallbackLabel)))
        val explicitType = obj.optString("type").takeIf { it.isNotBlank() }
            ?: obj.optString("semantic_subtype").takeIf { it.isNotBlank() }
        val nodeType = explicitType?.let { token ->
            NodeType.values().firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: inferNodeTypeFromText(token)
        } ?: fallbackType

        val dims = DimensionMapper.defaultDimensions(nodeType).toMutableMap()
        val attrs = extraAttrs.toMutableMap()
        val descBuilder = mutableListOf<String>()

        obj.keys().forEach { jsonKey ->
            val cell = obj.opt(jsonKey) ?: return@forEach
            val keyLower = jsonKey.lowercase()
            when {
                keyLower in RESERVED_JSON_KEYS -> {
                    if (keyLower == "description" && cell.toString().isNotBlank()) {
                        descBuilder += cell.toString()
                    }
                }
                keyLower in ALL_DIMENSION_NAMES -> cell.toString().toFloatOrNull()?.let { dims[keyLower] = it }
                keyLower in SEMANTIC_ATTRIBUTE_KEYS || cell !is JSONObject && cell !is JSONArray ->
                    attrs[keyLower] = cell.toString()
                else -> attrs[keyLower] = cell.toString().take(500)
            }
        }

        val description = if (descBuilder.isNotEmpty()) {
            descBuilder.joinToString("\n").take(500)
        } else {
            obj.optString("interpretation", obj.optString("raw_record", "")).take(500)
        }
        return MindNode(
            label = label.take(80),
            type = nodeType,
            description = description,
            x = x,
            y = y,
            parentId = parentId,
            attributes = attrs,
            dimensions = dims
        )
    }
}

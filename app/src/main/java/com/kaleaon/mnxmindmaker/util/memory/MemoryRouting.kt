package com.kaleaon.mnxmindmaker.util.memory

import com.kaleaon.mnxmindmaker.model.MindNode

object MemoryRouting {

    const val KEY_MEMORY_WING = "memory_wing"
    const val KEY_MEMORY_HALL = "memory_hall"
    const val KEY_MEMORY_ROOM = "memory_room"

    enum class CandidateTier {
        PRIMARY,
        SECONDARY,
        FALLBACK
    }

    data class RouteInference(
        val intentTokens: Set<String>,
        val memoryWing: String,
        val memoryHall: String,
        val memoryRoom: String,
        val alternates: Set<String> = emptySet()
    )

    data class TieredCandidates(
        val primary: List<MindNode>,
        val secondary: List<MindNode>,
        val fallback: List<MindNode>
    ) {
        fun flattened(): List<MindNode> = primary + secondary + fallback
    }

    fun tokenizeIntent(prompt: String, task: String = ""): Set<String> =
        "$prompt $task"
            .lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length > 2 }
            .toSet()

    fun inferRoute(prompt: String, task: String = ""): RouteInference = inferRoute(tokenizeIntent(prompt, task))

    fun inferRoute(intentTokens: Set<String>): RouteInference {
        if (intentTokens.isEmpty()) {
            return RouteInference(
                intentTokens = emptySet(),
                memoryWing = "core",
                memoryHall = "general",
                memoryRoom = "context"
            )
        }

        return when {
            intentTokens.any { it in PROFILE_TOKENS } -> RouteInference(
                intentTokens = intentTokens,
                memoryWing = "self",
                memoryHall = "preferences",
                memoryRoom = "voice_style",
                alternates = setOf("profile", "identity")
            )

            intentTokens.any { it in SAFETY_TOKENS } -> RouteInference(
                intentTokens = intentTokens,
                memoryWing = "governance",
                memoryHall = "risk",
                memoryRoom = "guardrails",
                alternates = setOf("policy", "compliance")
            )

            intentTokens.any { it in PROJECT_TOKENS } -> RouteInference(
                intentTokens = intentTokens,
                memoryWing = "execution",
                memoryHall = "project",
                memoryRoom = "runbook",
                alternates = setOf("planning", "tasks")
            )

            intentTokens.any { it in TIMELINE_TOKENS } -> RouteInference(
                intentTokens = intentTokens,
                memoryWing = "history",
                memoryHall = "events",
                memoryRoom = "timeline",
                alternates = setOf("recap", "session")
            )

            else -> RouteInference(
                intentTokens = intentTokens,
                memoryWing = "core",
                memoryHall = "knowledge",
                memoryRoom = "reference",
                alternates = setOf("general")
            )
        }
    }

    fun applyCanonicalRoute(
        node: MindNode,
        fallbackRoute: RouteInference
    ): MindNode {
        val attrs = node.attributes.toMutableMap()
        val inferredFromNode = inferRoute(
            tokenizeIntent(
                prompt = listOf(node.label, node.description, node.attributes["semantic_subtype"], node.attributes["tags"])
                    .filterNotNull()
                    .joinToString(" ")
            )
        )

        attrs.putIfAbsent(KEY_MEMORY_WING, inferredFromNode.memoryWing.ifBlank { fallbackRoute.memoryWing })
        attrs.putIfAbsent(KEY_MEMORY_HALL, inferredFromNode.memoryHall.ifBlank { fallbackRoute.memoryHall })
        attrs.putIfAbsent(KEY_MEMORY_ROOM, inferredFromNode.memoryRoom.ifBlank { fallbackRoute.memoryRoom })

        return node.copy(attributes = attrs)
    }

    fun tierCandidates(candidates: List<MindNode>, route: RouteInference): TieredCandidates {
        val primary = mutableListOf<MindNode>()
        val secondary = mutableListOf<MindNode>()
        val fallback = mutableListOf<MindNode>()

        candidates.forEach { node ->
            when (classifyTier(node, route)) {
                CandidateTier.PRIMARY -> primary += node
                CandidateTier.SECONDARY -> secondary += node
                CandidateTier.FALLBACK -> fallback += node
            }
        }

        return TieredCandidates(primary = primary, secondary = secondary, fallback = fallback)
    }

    private fun classifyTier(node: MindNode, route: RouteInference): CandidateTier {
        val wing = node.attributes[KEY_MEMORY_WING].orEmpty()
        val hall = node.attributes[KEY_MEMORY_HALL].orEmpty()
        val room = node.attributes[KEY_MEMORY_ROOM].orEmpty()

        val directWing = wing.equals(route.memoryWing, ignoreCase = true)
        val directHall = hall.equals(route.memoryHall, ignoreCase = true)
        val directRoom = room.equals(route.memoryRoom, ignoreCase = true)

        if (directWing && (directHall || directRoom)) return CandidateTier.PRIMARY

        val alternateMatch = sequenceOf(wing, hall, room)
            .any { candidate -> route.alternates.any { it.equals(candidate, ignoreCase = true) } }
        if (directWing || directHall || directRoom || alternateMatch) return CandidateTier.SECONDARY

        return CandidateTier.FALLBACK
    }

    private val PROFILE_TOKENS = setOf(
        "style", "tone", "voice", "preference", "persona", "identity", "bio", "about", "profile"
    )
    private val SAFETY_TOKENS = setOf(
        "safe", "safety", "risk", "policy", "compliance", "private", "sensitive", "security", "guardrail"
    )
    private val PROJECT_TOKENS = setOf(
        "project", "plan", "task", "todo", "milestone", "delivery", "deploy", "launch", "runbook"
    )
    private val TIMELINE_TOKENS = setOf(
        "when", "before", "after", "previous", "history", "timeline", "recent", "session", "recap"
    )
}

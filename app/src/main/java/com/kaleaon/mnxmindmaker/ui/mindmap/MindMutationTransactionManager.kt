package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class MindMutationTransactionManager(
    private val persistence: Persistence,
    initialGraphFactory: () -> MindGraph
) {

    private val state: PersistedState = persistence.load()?.let { PersistedState.fromJson(it) }
        ?: PersistedState.initial(initialGraphFactory())

    fun currentGraph(): MindGraph = state.currentGraph.deepCopy()

    fun currentVersion(): Long = state.version

    fun currentHash(): String = hashGraph(state.currentGraph)

    fun canUndo(): Boolean = state.undoStack.isNotEmpty()

    fun canRedo(): Boolean = state.redoStack.isNotEmpty()

    fun executeTransaction(
        mutationName: String,
        expectedVersion: Long,
        expectedHash: String,
        mutate: (MindGraph) -> MindGraph
    ): TransactionResult {
        if (expectedVersion != state.version || expectedHash != hashGraph(state.currentGraph)) {
            return TransactionResult.Conflict(
                message = "Mutation conflict detected for $mutationName. Graph changed from v$expectedVersion to v${state.version}."
            )
        }

        val before = state.currentGraph.deepCopy()
        return runCatching {
            val mutated = mutate(before.deepCopy()).withModifiedAtNow()
            state.currentGraph = mutated.deepCopy()
            state.version += 1
            state.undoStack += LoggedOperation(
                id = UUID.randomUUID().toString(),
                mutationName = mutationName,
                beforeGraph = before,
                afterGraph = mutated,
                baseVersion = expectedVersion,
                committedVersion = state.version,
                timestampMs = System.currentTimeMillis()
            )
            state.redoStack.clear()
            persist()
            TransactionResult.Committed(mutated.deepCopy(), state.version)
        }.getOrElse { ex ->
            state.currentGraph = before
            TransactionResult.RolledBack(ex.message ?: "Unknown transaction failure")
        }
    }

    fun undo(): UndoRedoResult {
        val operation = state.undoStack.removeLastOrNull() ?: return UndoRedoResult.NoOp
        state.currentGraph = operation.beforeGraph.deepCopy().withModifiedAtNow()
        state.version += 1
        state.redoStack += operation
        persist()
        return UndoRedoResult.Applied(state.currentGraph.deepCopy(), state.version)
    }

    fun redo(): UndoRedoResult {
        val operation = state.redoStack.removeLastOrNull() ?: return UndoRedoResult.NoOp
        state.currentGraph = operation.afterGraph.deepCopy().withModifiedAtNow()
        state.version += 1
        state.undoStack += operation
        persist()
        return UndoRedoResult.Applied(state.currentGraph.deepCopy(), state.version)
    }

    private fun persist() {
        persistence.save(state.toJson().toString())
    }

    interface Persistence {
        fun load(): String?
        fun save(payload: String)
    }

    class FilePersistence(private val file: File) : Persistence {
        override fun load(): String? = runCatching {
            if (file.exists()) file.readText() else null
        }.getOrNull()

        override fun save(payload: String) {
            file.parentFile?.mkdirs()
            file.writeText(payload)
        }
    }
}

internal sealed interface TransactionResult {
    data class Committed(val graph: MindGraph, val version: Long) : TransactionResult
    data class RolledBack(val message: String) : TransactionResult
    data class Conflict(val message: String) : TransactionResult
}

internal sealed interface UndoRedoResult {
    data object NoOp : UndoRedoResult
    data class Applied(val graph: MindGraph, val version: Long) : UndoRedoResult
}

private data class PersistedState(
    var currentGraph: MindGraph,
    var version: Long,
    val undoStack: MutableList<LoggedOperation>,
    val redoStack: MutableList<LoggedOperation>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("current_graph", graphToJson(currentGraph))
        put("undo_stack", JSONArray(undoStack.map { it.toJson() }))
        put("redo_stack", JSONArray(redoStack.map { it.toJson() }))
    }

    companion object {
        fun initial(graph: MindGraph): PersistedState = PersistedState(
            currentGraph = graph,
            version = 0,
            undoStack = mutableListOf(),
            redoStack = mutableListOf()
        )

        fun fromJson(raw: String): PersistedState {
            val root = JSONObject(raw)
            return PersistedState(
                currentGraph = graphFromJson(root.getJSONObject("current_graph")),
                version = root.optLong("version", 0L),
                undoStack = root.optJSONArray("undo_stack").toLoggedOperations(),
                redoStack = root.optJSONArray("redo_stack").toLoggedOperations()
            )
        }
    }
}

private data class LoggedOperation(
    val id: String,
    val mutationName: String,
    val beforeGraph: MindGraph,
    val afterGraph: MindGraph,
    val baseVersion: Long,
    val committedVersion: Long,
    val timestampMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("mutation_name", mutationName)
        put("before_graph", graphToJson(beforeGraph))
        put("after_graph", graphToJson(afterGraph))
        put("base_version", baseVersion)
        put("committed_version", committedVersion)
        put("timestamp_ms", timestampMs)
    }

    companion object {
        fun fromJson(json: JSONObject): LoggedOperation = LoggedOperation(
            id = json.getString("id"),
            mutationName = json.getString("mutation_name"),
            beforeGraph = graphFromJson(json.getJSONObject("before_graph")),
            afterGraph = graphFromJson(json.getJSONObject("after_graph")),
            baseVersion = json.optLong("base_version", 0L),
            committedVersion = json.optLong("committed_version", 0L),
            timestampMs = json.optLong("timestamp_ms", 0L)
        )
    }
}

private fun JSONArray?.toLoggedOperations(): MutableList<LoggedOperation> {
    if (this == null) return mutableListOf()
    return MutableList(length()) { idx -> LoggedOperation.fromJson(getJSONObject(idx)) }
}

private fun MindGraph.withModifiedAtNow(): MindGraph = copy(modifiedAt = System.currentTimeMillis())

private fun MindGraph.deepCopy(): MindGraph = copy(
    nodes = nodes.map { it.copy(attributes = it.attributes.toMutableMap(), dimensions = it.dimensions.toMap()) }.toMutableList(),
    edges = edges.map(MindEdge::copy).toMutableList()
)

private fun hashGraph(graph: MindGraph): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(graphToJson(graph).toString().toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun graphToJson(graph: MindGraph): JSONObject = JSONObject().apply {
    put("id", graph.id)
    put("name", graph.name)
    put("created_at", graph.createdAt)
    put("modified_at", graph.modifiedAt)
    put("nodes", JSONArray(graph.nodes.map { node ->
        JSONObject().apply {
            put("id", node.id)
            put("label", node.label)
            put("type", node.type.name)
            put("description", node.description)
            put("x", node.x.toDouble())
            put("y", node.y.toDouble())
            put("parent_id", node.parentId)
            put("attributes", JSONObject(node.attributes.toMap()))
            put("is_expanded", node.isExpanded)
            put("dimensions", JSONObject(node.dimensions.mapValues { it.value.toDouble() }))
        }
    }))
    put("edges", JSONArray(graph.edges.map { edge ->
        JSONObject().apply {
            put("id", edge.id)
            put("from", edge.fromNodeId)
            put("to", edge.toNodeId)
            put("label", edge.label)
            put("strength", edge.strength.toDouble())
        }
    }))
}

private fun graphFromJson(json: JSONObject): MindGraph {
    val nodesJson = json.getJSONArray("nodes")
    val edgesJson = json.getJSONArray("edges")
    val nodes = MutableList(nodesJson.length()) { i ->
        val node = nodesJson.getJSONObject(i)
        val dimensionsJson = node.optJSONObject("dimensions") ?: JSONObject()
        val dimensions = dimensionsJson.keys().asSequence().associateWith { key ->
            dimensionsJson.optDouble(key, 0.0).toFloat()
        }
        com.kaleaon.mnxmindmaker.model.MindNode(
            id = node.getString("id"),
            label = node.getString("label"),
            type = com.kaleaon.mnxmindmaker.model.NodeType.valueOf(node.getString("type")),
            description = node.optString("description"),
            x = node.optDouble("x", 0.0).toFloat(),
            y = node.optDouble("y", 0.0).toFloat(),
            parentId = node.optString("parent_id").ifBlank { null },
            attributes = node.optJSONObject("attributes")?.let { attrs ->
                attrs.keys().asSequence().associateWith { k -> attrs.optString(k) }.toMutableMap()
            } ?: mutableMapOf(),
            isExpanded = node.optBoolean("is_expanded", true),
            dimensions = dimensions
        )
    }
    val edges = MutableList(edgesJson.length()) { i ->
        val edge = edgesJson.getJSONObject(i)
        MindEdge(
            id = edge.getString("id"),
            fromNodeId = edge.getString("from"),
            toNodeId = edge.getString("to"),
            label = edge.optString("label"),
            strength = edge.optDouble("strength", 1.0).toFloat()
        )
    }
    return MindGraph(
        id = json.getString("id"),
        name = json.getString("name"),
        nodes = nodes,
        edges = edges,
        createdAt = json.optLong("created_at", System.currentTimeMillis()),
        modifiedAt = json.optLong("modified_at", System.currentTimeMillis())
    )
}

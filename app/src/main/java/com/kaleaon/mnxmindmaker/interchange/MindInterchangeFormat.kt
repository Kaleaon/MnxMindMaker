package com.kaleaon.mnxmindmaker.interchange

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-neutral import/export format for MindGraph artifacts.
 *
 * Supports:
 * - canonical JSON document (`.mnxj`-style payload)
 * - binary ZIP bundle (`.mnxb`) containing manifest + graph + optional blobs
 *
 * Compatibility model:
 * - MAJOR breaks compatibility and is rejected by older readers.
 * - MINOR is additive and ignored if unknown.
 * - `compatibility.forward_compat_extensions` lists extension namespaces that
 *   newer writers may include and older readers can ignore.
 */
object MindInterchangeFormat {

    const val SCHEMA_FAMILY = "mnx.interchange"
    const val SCHEMA_MAJOR = 1
    const val SCHEMA_MINOR = 0
    const val BUNDLE_MANIFEST_PATH = "manifest.json"
    const val BUNDLE_GRAPH_PATH = "payload/graph.json"

    data class SchemaVersion(val major: Int, val minor: Int) {
        override fun toString(): String = "$major.$minor"
    }

    data class CompatibilityHooks(
        val minReaderVersion: SchemaVersion = SchemaVersion(1, 0),
        val forwardCompatExtensions: List<String> = emptyList(),
        val migrationHint: String? = null
    )

    data class BundlePayload(
        val graph: MindGraph,
        val blobs: Map<String, ByteArray> = emptyMap(),
        val metadata: Map<String, String> = emptyMap()
    )

    class ValidationException(message: String) : IllegalArgumentException(message)

    fun exportJson(
        graph: MindGraph,
        compatibilityHooks: CompatibilityHooks = CompatibilityHooks(),
        metadata: Map<String, String> = emptyMap()
    ): String {
        val root = JSONObject()
        root.put(
            "schema",
            JSONObject()
                .put("family", SCHEMA_FAMILY)
                .put("version", JSONObject().put("major", SCHEMA_MAJOR).put("minor", SCHEMA_MINOR))
        )
        root.put(
            "compatibility",
            JSONObject()
                .put(
                    "min_reader_version",
                    JSONObject()
                        .put("major", compatibilityHooks.minReaderVersion.major)
                        .put("minor", compatibilityHooks.minReaderVersion.minor)
                )
                .put("forward_compat_extensions", JSONArray(compatibilityHooks.forwardCompatExtensions))
                .put("migration_hint", compatibilityHooks.migrationHint)
        )
        root.put("metadata", JSONObject(metadata))
        root.put("graph", graphToJson(graph))

        validateJson(root.toString())
        return root.toString(2)
    }

    fun importJson(json: String): MindGraph {
        validateJson(json)
        val root = JSONObject(json)
        return graphFromJson(root.getJSONObject("graph"))
    }

    fun validateJson(json: String) {
        validateJson(JSONObject(json))
    }

    fun exportBundle(
        payload: BundlePayload,
        compatibilityHooks: CompatibilityHooks = CompatibilityHooks()
    ): ByteArray {
        val graphJson = exportJson(payload.graph, compatibilityHooks, payload.metadata)

        val manifest = JSONObject()
            .put("schema_family", SCHEMA_FAMILY)
            .put("schema_version", JSONObject().put("major", SCHEMA_MAJOR).put("minor", SCHEMA_MINOR))
            .put("entries", JSONArray().apply {
                put(JSONObject().put("path", BUNDLE_GRAPH_PATH).put("type", "graph_json"))
                payload.blobs.keys.sorted().forEach { blobPath ->
                    put(JSONObject().put("path", "blobs/$blobPath").put("type", "blob"))
                }
            })

        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry(BUNDLE_MANIFEST_PATH))
                zip.write(manifest.toString(2).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(BUNDLE_GRAPH_PATH))
                zip.write(graphJson.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                payload.blobs.forEach { (path, bytes) ->
                    val normalized = normalizeBlobPath(path)
                    zip.putNextEntry(ZipEntry("blobs/$normalized"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            baos.toByteArray()
        }
    }

    fun importBundle(bytes: ByteArray): BundlePayload {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryBytes = zin.readBytes()
                    entries[entry.name] = entryBytes
                }
                entry = zin.nextEntry
            }
        }

        val manifest = entries[BUNDLE_MANIFEST_PATH]
            ?: throw ValidationException("Bundle missing $BUNDLE_MANIFEST_PATH")
        val manifestJson = JSONObject(String(manifest, StandardCharsets.UTF_8))
        if (manifestJson.optString("schema_family") != SCHEMA_FAMILY) {
            throw ValidationException("Unsupported schema family in bundle manifest")
        }

        val graphBytes = entries[BUNDLE_GRAPH_PATH]
            ?: throw ValidationException("Bundle missing $BUNDLE_GRAPH_PATH")
        val graphJson = String(graphBytes, StandardCharsets.UTF_8)
        val root = JSONObject(graphJson)
        validateJson(root)

        val blobs = entries
            .filterKeys { it.startsWith("blobs/") }
            .mapKeys { it.key.removePrefix("blobs/") }

        val metadata = root.optJSONObject("metadata")?.toStringMap() ?: emptyMap()
        return BundlePayload(
            graph = graphFromJson(root.getJSONObject("graph")),
            blobs = blobs,
            metadata = metadata
        )
    }

    private fun validateJson(root: JSONObject) {
        val schema = root.optJSONObject("schema")
            ?: throw ValidationException("Missing required object: schema")
        if (schema.optString("family") != SCHEMA_FAMILY) {
            throw ValidationException("Unsupported schema family: ${schema.optString("family")}")
        }

        val version = schema.optJSONObject("version")
            ?: throw ValidationException("Missing required object: schema.version")
        val major = version.optInt("major", -1)
        val minor = version.optInt("minor", -1)
        if (major < 0 || minor < 0) {
            throw ValidationException("schema.version.major and minor must be non-negative integers")
        }
        if (major > SCHEMA_MAJOR) {
            throw ValidationException("Unsupported major schema version: $major")
        }

        val compatibility = root.optJSONObject("compatibility")
            ?: throw ValidationException("Missing required object: compatibility")
        val minReader = compatibility.optJSONObject("min_reader_version")
            ?: throw ValidationException("Missing required object: compatibility.min_reader_version")
        val minReaderMajor = minReader.optInt("major", -1)
        val minReaderMinor = minReader.optInt("minor", -1)
        if (minReaderMajor < 0 || minReaderMinor < 0) {
            throw ValidationException("compatibility.min_reader_version must use non-negative integers")
        }
        val extensions = compatibility.optJSONArray("forward_compat_extensions")
            ?: throw ValidationException("Missing required array: compatibility.forward_compat_extensions")
        for (i in 0 until extensions.length()) {
            val extension = extensions.optString(i)
            if (extension.isBlank()) {
                throw ValidationException("compatibility.forward_compat_extensions[$i] must be non-empty")
            }
        }

        val graph = root.optJSONObject("graph")
            ?: throw ValidationException("Missing required object: graph")
        validateGraph(graph)
    }

    private fun validateGraph(graph: JSONObject) {
        requireField(graph, "id")
        requireField(graph, "name")
        if (!graph.has("created_at") || !graph.has("modified_at")) {
            throw ValidationException("graph.created_at and graph.modified_at are required")
        }
        if (graph.optLong("created_at", Long.MIN_VALUE) == Long.MIN_VALUE ||
            graph.optLong("modified_at", Long.MIN_VALUE) == Long.MIN_VALUE
        ) {
            throw ValidationException("graph.created_at and graph.modified_at must be integers")
        }

        val nodes = graph.optJSONArray("nodes")
            ?: throw ValidationException("graph.nodes is required and must be an array")
        val edges = graph.optJSONArray("edges")
            ?: throw ValidationException("graph.edges is required and must be an array")

        val nodeIds = mutableSetOf<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i)
                ?: throw ValidationException("graph.nodes[$i] must be an object")
            val id = node.optString("id")
            if (id.isBlank()) throw ValidationException("graph.nodes[$i].id is required")
            if (!nodeIds.add(id)) throw ValidationException("Duplicate node id: $id")
            val label = node.optString("label")
            if (label.isBlank()) throw ValidationException("graph.nodes[$i].label is required")
            val type = node.optString("type")
            if (NodeType.values().none { it.name == type }) {
                throw ValidationException("graph.nodes[$i].type must be a known NodeType")
            }
            validateFiniteFloat(node, "x", "graph.nodes[$i].x")
            validateFiniteFloat(node, "y", "graph.nodes[$i].y")

            if (!node.has("attributes") || node.optJSONObject("attributes") == null) {
                throw ValidationException("graph.nodes[$i].attributes must be an object")
            }
            if (!node.has("dimensions") || node.optJSONObject("dimensions") == null) {
                throw ValidationException("graph.nodes[$i].dimensions must be an object")
            }
            val dimensions = node.optJSONObject("dimensions")!!
            dimensions.keys().forEach { key ->
                val value = dimensions.optDouble(key, Double.NaN)
                if (!value.isFinite()) {
                    throw ValidationException("graph.nodes[$i].dimensions.$key must be a finite number")
                }
            }
        }

        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i)
                ?: throw ValidationException("graph.edges[$i] must be an object")
            val from = edge.optString("from_node_id")
            val to = edge.optString("to_node_id")
            if (!nodeIds.contains(from)) throw ValidationException("graph.edges[$i].from_node_id must reference an existing node")
            if (!nodeIds.contains(to)) throw ValidationException("graph.edges[$i].to_node_id must reference an existing node")
            validateFiniteFloat(edge, "strength", "graph.edges[$i].strength")
        }
    }

    private fun graphToJson(graph: MindGraph): JSONObject = JSONObject()
        .put("id", graph.id)
        .put("name", graph.name)
        .put("created_at", graph.createdAt)
        .put("modified_at", graph.modifiedAt)
        .put(
            "nodes",
            JSONArray(graph.nodes.map { node ->
                JSONObject()
                    .put("id", node.id)
                    .put("label", node.label)
                    .put("type", node.type.name)
                    .put("description", node.description)
                    .put("x", node.x.toDouble())
                    .put("y", node.y.toDouble())
                    .put("parent_id", node.parentId)
                    .put("attributes", JSONObject(node.attributes))
                    .put("is_expanded", node.isExpanded)
                    .put("dimensions", JSONObject(node.dimensions.mapValues { it.value.toDouble() }))
            })
        )
        .put(
            "edges",
            JSONArray(graph.edges.map { edge ->
                JSONObject()
                    .put("id", edge.id)
                    .put("from_node_id", edge.fromNodeId)
                    .put("to_node_id", edge.toNodeId)
                    .put("label", edge.label)
                    .put("strength", edge.strength.toDouble())
            })
        )

    private fun graphFromJson(graph: JSONObject): MindGraph {
        val nodesJson = graph.getJSONArray("nodes")
        val nodes = mutableListOf<MindNode>()
        for (i in 0 until nodesJson.length()) {
            val node = nodesJson.getJSONObject(i)
            val attributes = node.getJSONObject("attributes").toStringMap().toMutableMap()
            val dimensions = node.getJSONObject("dimensions")
                .toStringDoubleMap()
                .mapValues { it.value.toFloat() }
            nodes += MindNode(
                id = node.getString("id"),
                label = node.getString("label"),
                type = NodeType.values().firstOrNull { it.name == node.getString("type") } ?: NodeType.CUSTOM,
                description = node.optString("description"),
                x = node.optDouble("x").toFloat(),
                y = node.optDouble("y").toFloat(),
                parentId = node.optString("parent_id").ifBlank { null },
                attributes = attributes,
                isExpanded = node.optBoolean("is_expanded", true),
                dimensions = dimensions
            )
        }

        val edgesJson = graph.getJSONArray("edges")
        val edges = mutableListOf<MindEdge>()
        for (i in 0 until edgesJson.length()) {
            val edge = edgesJson.getJSONObject(i)
            edges += MindEdge(
                id = edge.getString("id"),
                fromNodeId = edge.getString("from_node_id"),
                toNodeId = edge.getString("to_node_id"),
                label = edge.optString("label"),
                strength = edge.optDouble("strength").toFloat()
            )
        }

        return MindGraph(
            id = graph.getString("id"),
            name = graph.getString("name"),
            nodes = nodes,
            edges = edges,
            createdAt = graph.getLong("created_at"),
            modifiedAt = graph.getLong("modified_at")
        )
    }

    private fun requireField(obj: JSONObject, field: String) {
        if (!obj.has(field) || obj.optString(field).isBlank()) {
            throw ValidationException("Missing or blank field: $field")
        }
    }

    private fun validateFiniteFloat(obj: JSONObject, key: String, path: String) {
        val value = obj.optDouble(key, Double.NaN)
        if (!value.isFinite()) {
            throw ValidationException("$path must be a finite number")
        }
    }

    private fun normalizeBlobPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank()) throw ValidationException("Blob path must not be blank")
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw ValidationException("Blob path must be relative and must not contain '..'")
        }
        return normalized
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        keys().forEach { key ->
            map[key] = optString(key)
        }
        return map
    }

    private fun JSONObject.toStringDoubleMap(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        keys().forEach { key ->
            val value = optDouble(key, Double.NaN)
            if (!value.isFinite()) {
                throw ValidationException("Invalid number for key '$key'")
            }
            map[key] = value
        }
        return map
    }
}

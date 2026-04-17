package com.kaleaon.mnxmindmaker.continuity

import android.content.Context
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import com.kaleaon.mnxmindmaker.security.EncryptedArtifactStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ContinuityManager(context: Context) {

    private val encryptedStore = EncryptedArtifactStore(context)

    data class SnapshotRecord(
        val snapshotId: String,
        val parentSnapshotId: String?,
        val timestamp: Long,
        val reason: String,
        val driftNotes: String,
        val integrityHash: String,
        val fileName: String,
        val nodeCount: Int,
        val edgeCount: Int
    )

    data class SnapshotDrift(
        val nodeDelta: Int,
        val edgeDelta: Int,
        val indicator: String
    )

    private val repository = MnxRepository(context)
    private val continuityDir: File = File(context.filesDir, "mnx_continuity").also { it.mkdirs() }
    private val timelineFile: File = File(continuityDir, "snapshots_timeline.json")

    fun createSnapshot(graph: MindGraph, reason: String, driftNotes: String = ""): SnapshotRecord {
        val timeline = readTimeline().toMutableList()
        val parent = timeline.firstOrNull()
        val timestamp = System.currentTimeMillis()
        val snapshotId = UUID.randomUUID().toString()
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fileName = "${safeName}_snapshot_${timestamp}.mnx"
        val outFile = File(continuityDir, fileName)
        val integrityHash = calculateIntegrityHash(graph)

        val metadata = MnxRepository.ContinuityMetadata(
            snapshotId = snapshotId,
            parentSnapshotId = parent?.snapshotId,
            integrityHash = integrityHash,
            reason = reason,
            driftNotes = driftNotes
        )
        repository.writeGraphToFile(graph, outFile, metadata)

        val snapshot = SnapshotRecord(
            snapshotId = snapshotId,
            parentSnapshotId = parent?.snapshotId,
            timestamp = timestamp,
            reason = reason,
            driftNotes = driftNotes,
            integrityHash = integrityHash,
            fileName = fileName,
            nodeCount = graph.nodes.size,
            edgeCount = graph.edges.size
        )
        timeline.add(0, snapshot)
        writeTimeline(timeline)
        return snapshot
    }

    fun listSnapshots(): List<SnapshotRecord> = readTimeline().sortedByDescending { it.timestamp }

    fun restoreSnapshot(snapshotId: String): MindGraph {
        val snapshot = readTimeline().firstOrNull { it.snapshotId == snapshotId }
            ?: error("Snapshot not found: $snapshotId")
        val snapshotFile = File(continuityDir, snapshot.fileName)
        snapshotFile.inputStream().use { input ->
            return repository.importFromMnx(input)
        }
    }

    fun compareSnapshotWithGraph(snapshotId: String, graph: MindGraph): SnapshotDrift {
        val snapshot = readTimeline().firstOrNull { it.snapshotId == snapshotId }
            ?: error("Snapshot not found: $snapshotId")
        val nodeDelta = graph.nodes.size - snapshot.nodeCount
        val edgeDelta = graph.edges.size - snapshot.edgeCount
        val indicator = buildString {
            append(if (nodeDelta >= 0) "+$nodeDelta" else "$nodeDelta")
            append(" nodes, ")
            append(if (edgeDelta >= 0) "+$edgeDelta" else "$edgeDelta")
            append(" edges")
        }
        return SnapshotDrift(nodeDelta = nodeDelta, edgeDelta = edgeDelta, indicator = indicator)
    }

    private fun calculateIntegrityHash(graph: MindGraph): String {
        val canonical = buildString {
            append(graph.id)
            append('|').append(graph.name)
            graph.nodes.sortedBy { it.id }.forEach { node ->
                append('|').append(node.id)
                append(':').append(node.label)
                append(':').append(node.type.name)
                append(':').append(node.description)
                append(':').append(node.x)
                append(':').append(node.y)
                append(':').append(node.parentId ?: "")
                node.dimensions.toSortedMap().forEach { (key, value) ->
                    append(':').append(key).append('=').append(value)
                }
            }
            graph.edges.sortedBy { it.id }.forEach { edge ->
                append('|').append(edge.id)
                append(':').append(edge.fromNodeId)
                append(':').append(edge.toNodeId)
                append(':').append(edge.label)
                append(':').append(edge.strength)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readTimeline(): List<SnapshotRecord> {
        if (!timelineFile.exists()) return emptyList()
        val json = String(encryptedStore.readDecryptedBytes(timelineFile, "snapshot_index"))
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    SnapshotRecord(
                        snapshotId = obj.getString("snapshot_id"),
                        parentSnapshotId = obj.optString("parent_snapshot_id").ifBlank { null },
                        timestamp = obj.getLong("timestamp"),
                        reason = obj.getString("reason"),
                        driftNotes = obj.optString("drift_notes"),
                        integrityHash = obj.getString("integrity_hash"),
                        fileName = obj.getString("file_name"),
                        nodeCount = obj.optInt("node_count", 0),
                        edgeCount = obj.optInt("edge_count", 0)
                    )
                )
            }
        }
    }

    private fun writeTimeline(snapshots: List<SnapshotRecord>) {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject().apply {
                    put("snapshot_id", snapshot.snapshotId)
                    put("parent_snapshot_id", snapshot.parentSnapshotId ?: "")
                    put("timestamp", snapshot.timestamp)
                    put("reason", snapshot.reason)
                    put("drift_notes", snapshot.driftNotes)
                    put("integrity_hash", snapshot.integrityHash)
                    put("file_name", snapshot.fileName)
                    put("node_count", snapshot.nodeCount)
                    put("edge_count", snapshot.edgeCount)
                }
            )
        }
        encryptedStore.writeEncryptedBytes(timelineFile, array.toString().toByteArray(), "snapshot_index")
    }
}

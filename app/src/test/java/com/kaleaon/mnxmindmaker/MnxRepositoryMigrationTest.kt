package com.kaleaon.mnxmindmaker

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxFormat
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.mnx.MnxIdentity
import com.kaleaon.mnxmindmaker.mnx.MnxMeta
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnxRepositoryMigrationTest {

    private val context: Context = createTestContext()

    @Test
    fun `dry-run preview reports migrations without mutating artifact`() {
        val repo = MnxRepository(context)
        val legacyGraph = MindGraph(
            name = "Legacy",
            nodes = mutableListOf(
                MindNode(id = "n1", label = "Identity", type = NodeType.IDENTITY),
                MindNode(id = "n1", label = "Dup", type = NodeType.BELIEF)
            ),
            edges = mutableListOf(MindEdge(id = "e1", fromNodeId = "n1", toNodeId = "missing"))
        )
        val payload = MnxRepository.serializeGraphPayload(legacyGraph)
        val legacyMeta = MnxMeta(
            mapOf(
                "app" to "MnxMindMaker",
                MnxRepository.META_LEGACY_PERSONA_DEPLOYMENT_KEY to "{\"target\":{}}"
            )
        )
        val file = MnxFile(
            header = MnxHeader(),
            sections = mapOf(MnxFormat.MnxSectionType.META to MnxCodec.serializeMeta(legacyMeta)),
            rawSections = mapOf(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE to payload)
        )

        val encodedBytes = MnxCodec.encodeToBytes(file)
        val expectedFile = MnxCodec.decode(ByteArrayInputStream(encodedBytes))
        val report = repo.previewArtifactMigration(ByteArrayInputStream(encodedBytes))

        assertEquals(2, report.initialVersion)
        assertEquals(MnxRepository.LATEST_SCHEMA_VERSION, report.targetVersion)
        assertEquals(2, report.appliedChanges.size)
        assertTrue(report.hasConflicts)
        assertTrue(report.changed)
        assertEquals(null, report.rollbackToken)
        assertEquals(expectedFile, report.migratedFile)
    }

    @Test
    fun `migration supports rollback and fixes legacy artifacts`() {
        val repo = MnxRepository(context)
        val identity = MnxIdentity(
            name = "Migrated",
            createdAt = 1000L,
            biography = "legacy bio",
            coreTraits = listOf("Curious")
        )
        val meta = MnxMeta(
            mapOf(
                "app" to "MnxMindMaker",
                "graph_name" to "Migrated",
                MnxRepository.META_LEGACY_PERSONA_DEPLOYMENT_KEY to "{\"target\":{}}"
            )
        )
        val legacyFile = MnxFile(
            header = MnxHeader(),
            sections = mapOf(
                MnxFormat.MnxSectionType.IDENTITY to MnxCodec.serializeIdentity(identity),
                MnxFormat.MnxSectionType.META to MnxCodec.serializeMeta(meta)
            )
        )

        val encodedBytes = MnxCodec.encodeToBytes(legacyFile)
        val expectedLegacyFile = MnxCodec.decode(ByteArrayInputStream(encodedBytes))
        val report = repo.migrateArtifact(ByteArrayInputStream(encodedBytes))

        assertEquals(1, report.initialVersion)
        assertEquals(3, report.appliedChanges.size)
        assertNotNull(report.rollbackToken)
        assertTrue(report.migratedFile.hasRawSection(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE))

        val rolledBack = repo.rollbackArtifact(report.rollbackToken!!)
        assertEquals(expectedLegacyFile, rolledBack)

        val migratedMeta = MnxCodec.deserializeMeta(
            report.migratedFile.sections[MnxFormat.MnxSectionType.META]!!
        )
        assertFalse(migratedMeta.entries.containsKey(MnxRepository.META_LEGACY_PERSONA_DEPLOYMENT_KEY))
        assertTrue(migratedMeta.entries.containsKey(MnxRepository.META_PERSONA_DEPLOYMENT_KEY))
        assertEquals(
            MnxRepository.LATEST_SCHEMA_VERSION.toString(),
            migratedMeta.entries[MnxRepository.META_SCHEMA_VERSION_KEY]
        )
    }

    @Test
    fun `normalization handles three duplicate ids with deterministic canonical rewrites`() {
        val repo = MnxRepository(context)
        val legacyGraph = MindGraph(
            id = "g-dup",
            name = "Dup graph",
            nodes = mutableListOf(
                MindNode(id = "dup", label = "dup-1", type = NodeType.IDENTITY),
                MindNode(id = "dup", label = "dup-2", type = NodeType.BELIEF, parentId = "dup"),
                MindNode(id = "dup", label = "dup-3", type = NodeType.CUSTOM, parentId = "dup"),
                MindNode(id = "unique", label = "unique", type = NodeType.CUSTOM, parentId = "dup")
            ),
            edges = mutableListOf(
                MindEdge(id = "e1", fromNodeId = "dup", toNodeId = "unique", label = "a"),
                MindEdge(id = "e2", fromNodeId = "unique", toNodeId = "dup", label = "b"),
                MindEdge(id = "e3", fromNodeId = "dup", toNodeId = "dup", label = "c")
            )
        )
        val payload = MnxRepository.serializeGraphPayload(legacyGraph)
        val legacyMeta = MnxMeta(
            mapOf(
                "app" to "MnxMindMaker",
                MnxRepository.META_SCHEMA_VERSION_KEY to "3"
            )
        )
        val file = MnxFile(
            header = MnxHeader(),
            sections = mapOf(MnxFormat.MnxSectionType.META to MnxCodec.serializeMeta(legacyMeta)),
            rawSections = mapOf(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE to payload)
        )

        val report = repo.migrateArtifact(ByteArrayInputStream(MnxCodec.encodeToBytes(file)))
        val normalized = MnxRepository.deserializeGraphPayload(
            report.migratedFile.rawSections[MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE]!!
        )

        assertEquals(listOf("dup", "dup#2", "dup#3", "unique"), normalized.nodes.map { it.id })
        assertEquals(listOf("dup", "dup", "dup"), normalized.nodes.mapNotNull { it.parentId })
        assertTrue(normalized.edges.all { edge ->
            normalized.nodes.any { it.id == edge.fromNodeId } && normalized.nodes.any { it.id == edge.toNodeId }
        })
        assertEquals("dup", normalized.edges.first { it.id == "e1" }.fromNodeId)
        assertEquals("dup", normalized.edges.first { it.id == "e2" }.toNodeId)
        assertEquals(
            "dup" to "dup",
            normalized.edges.first { it.id == "e3" }.let { it.fromNodeId to it.toNodeId }
        )
        assertFalse(report.conflicts.any { it.code == "dangling_edge" })
    }
}

private fun createTestContext(filesDir: java.io.File = java.io.File("/tmp")): Context {
    val prefsMap = mutableMapOf<String, MutableMap<String, Any?>>()
    return object : ContextWrapper(null) {
        override fun getFilesDir(): java.io.File = filesDir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = prefsMap.getOrPut(name) { mutableMapOf() }
            return FakeSharedPreferences(store)
        }
        override fun getPackageName(): String = "com.kaleaon.mnxmindmaker"
        override fun getContentResolver(): ContentResolver? = null
    }
}

private class FakeSharedPreferences(
    private val data: MutableMap<String, Any?> = mutableMapOf()
) : SharedPreferences {
    override fun getAll(): Map<String, *> = data
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { changes[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { changes[key] = this; return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) data.clear()
            for ((k, v) in changes) {
                if (v === this) data.remove(k) else data[k] = v
            }
        }
    }
}

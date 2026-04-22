package com.kaleaon.mnxmindmaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    private val context: Context = ApplicationProvider.getApplicationContext()

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

        val report = repo.previewArtifactMigration(ByteArrayInputStream(MnxCodec.encodeToBytes(file)))

        assertEquals(2, report.initialVersion)
        assertEquals(MnxRepository.LATEST_SCHEMA_VERSION, report.targetVersion)
        assertEquals(2, report.appliedChanges.size)
        assertTrue(report.hasConflicts)
        assertTrue(report.changed)
        assertEquals(null, report.rollbackToken)
        assertEquals(file, report.migratedFile)
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

        val report = repo.migrateArtifact(ByteArrayInputStream(MnxCodec.encodeToBytes(legacyFile)))

        assertEquals(1, report.initialVersion)
        assertEquals(3, report.appliedChanges.size)
        assertNotNull(report.rollbackToken)
        assertTrue(report.migratedFile.hasRawSection(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE))

        val rolledBack = repo.rollbackArtifact(report.rollbackToken!!)
        assertEquals(legacyFile, rolledBack)

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
    fun `workspace pack import fails when raw section is missing`() {
        val repo = MnxRepository(context)
        val fileWithoutPack = MnxFile(
            header = MnxHeader(),
            sections = mapOf(
                MnxFormat.MnxSectionType.META to MnxCodec.serializeMeta(
                    MnxMeta(mapOf("pack_type" to "workspace_pack"))
                )
            ),
            rawSections = emptyMap()
        )

        val error = runCatching {
            repo.importWorkspacePack(ByteArrayInputStream(MnxCodec.encodeToBytes(fileWithoutPack)))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(
            error!!.message!!.contains("does not contain a workspace pack raw section")
        )
    }
}

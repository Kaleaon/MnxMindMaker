package com.kaleaon.mnxmindmaker.mnx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/**
 * Binary codec for reading and writing .mnx (Mind Nexus) files.
 * Ported from github.com/Kaleaon/TronProtocol (mindnexus/MnxCodec.kt).
 *
 * ## Writing
 * Build an [MnxFile] with populated sections, then call [encode].
 *
 * ## Reading
 * Call [decode] with an [InputStream] or [File] to get an [MnxFile].
 *
 * ## Integrity
 * - Each section has a CRC32 checksum verified on read.
 * - The footer contains a SHA-256 digest of all section data.
 */
object MnxCodec {

    // =========================================================================
    //  TOP-LEVEL ENCODE / DECODE
    // =========================================================================

    fun encode(file: MnxFile, output: OutputStream) {
        val writer = MnxWriter(output)

        val allSectionTypes = mutableListOf<Short>()
        val allPayloads = mutableListOf<ByteArray>()

        for ((type, payload) in file.sections) {
            allSectionTypes.add(type.typeId)
            allPayloads.add(payload)
        }
        for ((typeId, payload) in file.rawSections) {
            allSectionTypes.add(typeId)
            allPayloads.add(payload)
        }

        val totalSections = allSectionTypes.size
        val sectionTableSize = totalSections * MnxFormat.SECTION_ENTRY_SIZE
        var dataOffset = (MnxFormat.HEADER_SIZE + sectionTableSize).toLong()

        data class RawEntry(val typeId: Short, val offset: Long, val size: Int, val crc32: Int)
        val rawEntries = mutableListOf<RawEntry>()

        for (i in 0 until totalSections) {
            val payload = allPayloads[i]
            val crc = crc32(payload)
            rawEntries.add(RawEntry(allSectionTypes[i], dataOffset, payload.size, crc))
            dataOffset += payload.size
        }

        val header = file.header.copy(
            sectionCount = totalSections.toShort(),
            sectionTableOffset = MnxFormat.HEADER_SIZE,
            modifiedTimestamp = System.currentTimeMillis(),
            totalUncompressedSize = allPayloads.sumOf { it.size.toLong() }
        )
        writeHeader(writer, header)

        for (entry in rawEntries) {
            writer.writeShort(entry.typeId)
            writer.writeLong(entry.offset)
            writer.writeInt(entry.size)
            writer.writeInt(entry.crc32)
            writer.writeShort(0)
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
        for (payload in allPayloads) {
            writer.writeBytes(payload)
            sha256.update(payload)
        }

        val digest = sha256.digest()
        writer.writeBytes(digest)
        writer.writeInt(MnxFormat.FOOTER_MAGIC)
        writer.flush()
    }

    fun encodeToBytes(file: MnxFile): ByteArray {
        val baos = ByteArrayOutputStream()
        encode(file, baos)
        return baos.toByteArray()
    }

    fun encodeToFile(mnxFile: MnxFile, outputFile: File) {
        FileOutputStream(outputFile).buffered().use { encode(mnxFile, it) }
    }

    fun decode(input: InputStream): MnxFile {
        val reader = MnxReader(input)
        val header = readHeader(reader)

        data class RawEntry(val typeId: Short, val offset: Long, val size: Int, val crc32: Int)
        val rawEntries = mutableListOf<RawEntry>()
        repeat(header.sectionCount.toInt()) {
            val typeId = reader.readShort()
            val offset = reader.readLong()
            val size = reader.readInt()
            val crc = reader.readInt()
            reader.readShort() // reserved
            rawEntries.add(RawEntry(typeId, offset, size, crc))
        }

        val knownSections = mutableMapOf<MnxFormat.MnxSectionType, ByteArray>()
        val unknownSections = mutableMapOf<Short, ByteArray>()
        val sha256 = MessageDigest.getInstance("SHA-256")

        for (entry in rawEntries) {
            val payload = reader.readBytes(entry.size)
            val actualCrc = crc32(payload)
            if (actualCrc != entry.crc32) {
                throw MnxFormatException(
                    "CRC32 mismatch for section 0x${entry.typeId.toString(16)}: " +
                    "expected 0x${entry.crc32.toString(16)}, got 0x${actualCrc.toString(16)}"
                )
            }
            sha256.update(payload)
            val knownType = MnxFormat.MnxSectionType.fromTypeId(entry.typeId)
            if (knownType != null) knownSections[knownType] = payload
            else unknownSections[entry.typeId] = payload
        }

        // Read footer (optional — stream may end without it)
        val footer: MnxFooter? = try {
            val digestBytes = reader.readBytes(32)
            val magic = reader.readInt()
            if (magic == MnxFormat.FOOTER_MAGIC) MnxFooter(digestBytes, magic) else null
        } catch (_: EOFException) { null } catch (_: IOException) { null }

        return MnxFile(header, knownSections, unknownSections, footer)
    }

    fun decodeFromBytes(data: ByteArray): MnxFile = decode(ByteArrayInputStream(data))

    fun decodeFromFile(file: File): MnxFile = FileInputStream(file).buffered().use { decode(it) }

    // =========================================================================
    //  HEADER R/W
    // =========================================================================

    private fun writeHeader(writer: MnxWriter, header: MnxHeader) {
        writer.writeInt(MnxFormat.MAGIC)
        writer.writeByte(header.versionMajor)
        writer.writeByte(header.versionMinor)
        writer.writeByte(header.versionPatch)
        writer.writeByte(header.flags)
        writer.writeLong(header.createdTimestamp)
        writer.writeLong(header.modifiedTimestamp)
        writer.writeShort(header.sectionCount)
        writer.writeInt(header.sectionTableOffset)
        writer.writeUuid(header.fileUuid)
        writer.writeLong(header.totalUncompressedSize)
        writer.writeInt(header.headerCrc32)
        // Pad to HEADER_SIZE = 64
        // Written: 4+1+1+1+1+8+8+2+4+16+8+4 = 58 bytes; pad 6
        repeat(6) { writer.writeByte(0) }
    }

    private fun readHeader(reader: MnxReader): MnxHeader {
        val magic = reader.readInt()
        if (magic != MnxFormat.MAGIC) {
            throw MnxFormatException("Invalid MNX magic: 0x${magic.toString(16)}")
        }
        val major = reader.readByte()
        val minor = reader.readByte()
        val patch = reader.readByte()
        val flags = reader.readByte()
        val created = reader.readLong()
        val modified = reader.readLong()
        val sectionCount = reader.readShort()
        val tableOffset = reader.readInt()
        val fileUuid = reader.readUuid()
        val totalSize = reader.readLong()
        val headerCrc = reader.readInt()
        reader.skip(6) // padding
        return MnxHeader(major, minor, patch, flags, created, modified,
            sectionCount, tableOffset, fileUuid, totalSize, headerCrc)
    }

    // =========================================================================
    //  SECTION SERIALIZERS
    // =========================================================================

    fun serializeIdentity(identity: MnxIdentity): ByteArray = mnxSerialize {
        writeString(identity.name)
        writeLong(identity.createdAt)
        writeString(identity.species)
        writeString(identity.pronouns)
        writeStringList(identity.coreTraits)
        writeString(identity.biography)
        writeStringMap(identity.attributes)
    }

    fun deserializeIdentity(data: ByteArray): MnxIdentity = mnxDeserialize(data) {
        MnxIdentity(
            name = readString(),
            createdAt = readLong(),
            species = readString(),
            pronouns = readString(),
            coreTraits = readStringList(),
            biography = readString(),
            attributes = readStringMap()
        )
    }

    fun serializeMemoryStore(store: MnxMemoryStore): ByteArray = mnxSerialize {
        writeList(store.chunks) { chunk ->
            writeString(chunk.chunkId)
            writeString(chunk.content)
            writeString(chunk.source)
            writeString(chunk.sourceType)
            writeString(chunk.timestamp)
            writeInt(chunk.tokenCount)
            writeFloat(chunk.qValue)
            writeInt(chunk.retrievalCount)
            writeInt(chunk.successCount)
            writeString(chunk.memoryStage)
            writeNullableFloatArray(chunk.embedding)
            writeStringMap(chunk.metadata)
        }
    }

    fun deserializeMemoryStore(data: ByteArray): MnxMemoryStore = mnxDeserialize(data) {
        val chunks = readList {
            MnxMemoryChunk(
                chunkId = readString(),
                content = readString(),
                source = readString(),
                sourceType = readString(),
                timestamp = readString(),
                tokenCount = readInt(),
                qValue = readFloat(),
                retrievalCount = readInt(),
                successCount = readInt(),
                memoryStage = readString(),
                embedding = readNullableFloatArray(),
                metadata = readStringMap()
            )
        }
        MnxMemoryStore(chunks)
    }

    fun serializeKnowledgeGraph(graph: MnxKnowledgeGraph): ByteArray = mnxSerialize {
        writeList(graph.entities) { e ->
            writeString(e.entityId); writeString(e.name); writeString(e.entityType)
            writeString(e.description); writeInt(e.mentionCount); writeLong(e.lastSeen)
        }
        writeList(graph.chunkNodes) { c ->
            writeString(c.chunkId); writeString(c.summary); writeStringList(c.entityIds)
        }
        writeList(graph.edges) { edge ->
            writeString(edge.sourceEntityId); writeString(edge.targetEntityId)
            writeString(edge.relationship); writeFloat(edge.strength); writeStringList(edge.keywords)
        }
    }

    fun deserializeKnowledgeGraph(data: ByteArray): MnxKnowledgeGraph = mnxDeserialize(data) {
        val entities = readList {
            MnxEntityNode(readString(), readString(), readString(), readString(), readInt(), readLong())
        }
        val chunks = readList { MnxChunkNode(readString(), readString(), readStringList()) }
        val edges = readList {
            MnxRelationshipEdge(readString(), readString(), readString(), readFloat(), readStringList())
        }
        MnxKnowledgeGraph(entities, chunks, edges)
    }

    fun serializePersonality(p: MnxPersonality): ByteArray = mnxSerialize {
        writeStringFloatMap(p.traits)
        writeStringFloatMap(p.biases)
        writeFloat(p.curiosityLevel)
        writeFloat(p.openness)
        writeFloat(p.conscientiousness)
        writeFloat(p.extraversion)
        writeFloat(p.agreeableness)
        writeFloat(p.neuroticism)
    }

    fun deserializePersonality(data: ByteArray): MnxPersonality = mnxDeserialize(data) {
        MnxPersonality(
            traits = readStringFloatMap(),
            biases = readStringFloatMap(),
            curiosityLevel = readFloat(),
            openness = readFloat(),
            conscientiousness = readFloat(),
            extraversion = readFloat(),
            agreeableness = readFloat(),
            neuroticism = readFloat()
        )
    }

    fun serializeBeliefStore(store: MnxBeliefStore): ByteArray = mnxSerialize {
        writeList(store.beliefs) { b ->
            writeString(b.beliefId); writeString(b.statement); writeFloat(b.confidence)
            writeStringList(b.evidence); writeLong(b.createdAt); writeLong(b.updatedAt)
        }
    }

    fun deserializeBeliefStore(data: ByteArray): MnxBeliefStore = mnxDeserialize(data) {
        val beliefs = readList {
            MnxBelief(readString(), readString(), readFloat(), readStringList(), readLong(), readLong())
        }
        MnxBeliefStore(beliefs)
    }

    fun serializeValueAlignment(va: MnxValueAlignment): ByteArray = mnxSerialize {
        writeList(va.values) { v ->
            writeString(v.valueId); writeString(v.name); writeFloat(v.weight); writeString(v.description)
        }
    }

    fun deserializeValueAlignment(data: ByteArray): MnxValueAlignment = mnxDeserialize(data) {
        val values = readList { MnxValue(readString(), readString(), readFloat(), readString()) }
        MnxValueAlignment(values)
    }

    fun serializeRelationshipWeb(web: MnxRelationshipWeb): ByteArray = mnxSerialize {
        writeList(web.relationships) { r ->
            writeString(r.entityId); writeString(r.name); writeString(r.relationshipType)
            writeFloat(r.bond); writeLong(r.lastInteraction); writeString(r.notes)
        }
    }

    fun deserializeRelationshipWeb(data: ByteArray): MnxRelationshipWeb = mnxDeserialize(data) {
        val rels = readList {
            MnxRelationship(readString(), readString(), readString(), readFloat(), readLong(), readString())
        }
        MnxRelationshipWeb(rels)
    }

    fun serializeMeta(meta: MnxMeta): ByteArray = mnxSerialize { writeStringMap(meta.entries) }
    fun deserializeMeta(data: ByteArray): MnxMeta = mnxDeserialize(data) { MnxMeta(readStringMap()) }

    /**
     * Serialize an [MnxDimensionalRefs] to bytes.
     *
     * Each ref is stored as:
     *   subject (string) + dimension (string) + target (string) +
     *   targetType (string) + confidence (float) + metadata (string map)
     *
     * Because dimension names and target values are both open-ended strings,
     * the format supports an unlimited number of named dimensions per subject.
     */
    fun serializeDimensionalRefs(refs: MnxDimensionalRefs): ByteArray = mnxSerialize {
        writeList(refs.refs) { ref ->
            writeString(ref.subject)
            writeString(ref.dimension)
            writeString(ref.target)
            writeString(ref.targetType)
            writeFloat(ref.confidence)
            writeStringMap(ref.metadata)
        }
    }

    fun deserializeDimensionalRefs(data: ByteArray): MnxDimensionalRefs = mnxDeserialize(data) {
        val refs = readList {
            MnxDimensionalRef(
                subject = readString(),
                dimension = readString(),
                target = readString(),
                targetType = readString(),
                confidence = readFloat(),
                metadata = readStringMap()
            )
        }
        MnxDimensionalRefs(refs)
    }

    // =========================================================================
    //  UTILITIES
    // =========================================================================

    private fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }
}

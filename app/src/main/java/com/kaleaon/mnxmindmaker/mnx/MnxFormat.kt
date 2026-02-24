package com.kaleaon.mnxmindmaker.mnx

import java.util.UUID

/**
 * Mind Nexus File Format (.mnx) — constants, header, section table, and footer.
 *
 * This is the portable specification layer ported from
 * github.com/Kaleaon/TronProtocol (mindnexus/MnxFormat.kt).
 *
 * ## File Layout
 * ```
 * ┌────────────────────────────────────┐
 * │  HEADER  (64 bytes, fixed)         │  Magic, version, flags, timestamps
 * ├────────────────────────────────────┤
 * │  SECTION TABLE  (20 bytes/entry)   │  Type, offset, size, CRC32 per section
 * ├────────────────────────────────────┤
 * │  SECTION DATA  (variable)          │  Binary-encoded section payloads
 * ├────────────────────────────────────┤
 * │  FOOTER  (36 bytes, fixed)         │  SHA-256 checksum + end marker
 * └────────────────────────────────────┘
 * ```
 */
object MnxFormat {

    // ---- Magic & Markers ----
    const val MAGIC: Int = 0x4D4E5821          // "MNX!"
    const val FOOTER_MAGIC: Int = 0x21584E4D   // "!XNM"
    const val FILE_EXTENSION = ".mnx"
    const val MIME_TYPE = "application/x-mind-nexus"

    // ---- Version ----
    const val VERSION_MAJOR: Byte = 1
    const val VERSION_MINOR: Byte = 0
    const val VERSION_PATCH: Byte = 0

    // ---- Sizes ----
    const val HEADER_SIZE = 64
    const val SECTION_ENTRY_SIZE = 20
    const val FOOTER_SIZE = 36

    // ---- Header Flags ----
    const val FLAG_COMPRESSED: Byte = 0x01
    const val FLAG_ENCRYPTED: Byte = 0x02
    const val FLAG_SIGNED: Byte = 0x04

    // ---- Section Type IDs ----
    enum class MnxSectionType(val typeId: Short, val label: String) {
        IDENTITY(0x0001, "Identity"),
        MEMORY_STORE(0x0002, "Memory Store"),
        KNOWLEDGE_GRAPH(0x0003, "Knowledge Graph"),
        AFFECT_STATE(0x0004, "Affect State"),
        AFFECT_LOG(0x0005, "Affect Log"),
        EXPRESSION_MAP(0x0006, "Expression Map"),
        PERSONALITY(0x0007, "Personality"),
        BELIEF_STORE(0x0008, "Belief Store"),
        VALUE_ALIGNMENT(0x0009, "Value Alignment"),
        RELATIONSHIP_WEB(0x000A, "Relationship Web"),
        PREFERENCE_STORE(0x000B, "Preference Store"),
        TIMELINE(0x000C, "Timeline"),
        EMBEDDING_INDEX(0x000D, "Embedding Index"),
        OPINION_MAP(0x000E, "Opinion Map"),
        ATTACHMENT_MANIFEST(0x000F, "Attachment Manifest"),
        ATTACHMENT_DATA(0x0011, "Attachment Data"),
        SENSORY_ASSOCIATIONS(0x0012, "Sensory Associations"),
        DIMENSIONAL_REFS(0x0013, "Dimensional Refs"),
        META(0x00FF.toShort(), "Meta");

        companion object {
            private val byId = entries.associateBy { it.typeId }
            fun fromTypeId(id: Short): MnxSectionType? = byId[id]
            fun isUserDefined(id: Short): Boolean = id < 0
        }
    }
}

data class MnxHeader(
    val versionMajor: Byte = MnxFormat.VERSION_MAJOR,
    val versionMinor: Byte = MnxFormat.VERSION_MINOR,
    val versionPatch: Byte = MnxFormat.VERSION_PATCH,
    val flags: Byte = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    val sectionCount: Short = 0,
    val sectionTableOffset: Int = MnxFormat.HEADER_SIZE,
    val fileUuid: UUID = UUID.randomUUID(),
    val totalUncompressedSize: Long = 0L,
    val headerCrc32: Int = 0
) {
    val version: String get() = "$versionMajor.$versionMinor.$versionPatch"
    val isCompressed: Boolean get() = (flags.toInt() and MnxFormat.FLAG_COMPRESSED.toInt()) != 0
    val isEncrypted: Boolean get() = (flags.toInt() and MnxFormat.FLAG_ENCRYPTED.toInt()) != 0
    val isSigned: Boolean get() = (flags.toInt() and MnxFormat.FLAG_SIGNED.toInt()) != 0
}

data class MnxSectionEntry(
    val sectionType: MnxFormat.MnxSectionType,
    val offset: Long,
    val size: Int,
    val crc32: Int
)

data class MnxFooter(
    val sha256: ByteArray,
    val footerMagic: Int = MnxFormat.FOOTER_MAGIC
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxFooter) return false
        return sha256.contentEquals(other.sha256) && footerMagic == other.footerMagic
    }
    override fun hashCode(): Int = sha256.contentHashCode() * 31 + footerMagic
}

data class MnxFile(
    val header: MnxHeader,
    val sections: Map<MnxFormat.MnxSectionType, ByteArray>,
    val rawSections: Map<Short, ByteArray> = emptyMap(),
    val footer: MnxFooter? = null
) {
    fun hasSection(type: MnxFormat.MnxSectionType): Boolean = sections.containsKey(type)
    fun hasRawSection(typeId: Short): Boolean = rawSections.containsKey(typeId)
    val sectionCount: Int get() = sections.size + rawSections.size
    val totalPayloadSize: Long
        get() = sections.values.sumOf { it.size.toLong() } +
                rawSections.values.sumOf { it.size.toLong() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxFile) return false
        if (header != other.header) return false
        if (sections.size != other.sections.size) return false
        for ((type, data) in sections) {
            if (!data.contentEquals(other.sections[type] ?: return false)) return false
        }
        if (rawSections.size != other.rawSections.size) return false
        for ((typeId, data) in rawSections) {
            if (!data.contentEquals(other.rawSections[typeId] ?: return false)) return false
        }
        return footer == other.footer
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        for ((type, data) in sections) {
            result = result * 31 + type.hashCode()
            result = result * 31 + data.contentHashCode()
        }
        return result * 31 + (footer?.hashCode() ?: 0)
    }
}

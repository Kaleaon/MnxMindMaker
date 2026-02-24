package com.kaleaon.mnxmindmaker.mnx

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Low-level binary I/O for the Mind Nexus (.mnx) format.
 * Ported from github.com/Kaleaon/TronProtocol (mindnexus/MnxBinaryStream.kt).
 * All multi-byte values use big-endian byte order.
 */

class MnxWriter(output: OutputStream) {
    val dos = DataOutputStream(output)
    val bytesWritten: Long get() = dos.size().toLong()

    fun writeByte(v: Byte) = dos.writeByte(v.toInt())
    fun writeBoolean(v: Boolean) = dos.writeByte(if (v) 1 else 0)
    fun writeShort(v: Short) = dos.writeShort(v.toInt())
    fun writeInt(v: Int) = dos.writeInt(v)
    fun writeLong(v: Long) = dos.writeLong(v)
    fun writeFloat(v: Float) = dos.writeFloat(v)
    fun writeDouble(v: Double) = dos.writeDouble(v)
    fun writeBytes(data: ByteArray) = dos.write(data)

    fun writeString(s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    fun writeUuid(uuid: UUID) {
        dos.writeLong(uuid.mostSignificantBits)
        dos.writeLong(uuid.leastSignificantBits)
    }

    fun writeFloatArray(arr: FloatArray) {
        dos.writeInt(arr.size)
        for (f in arr) dos.writeFloat(f)
    }

    fun writeNullableFloatArray(arr: FloatArray?) {
        if (arr == null) dos.writeInt(-1)
        else writeFloatArray(arr)
    }

    inline fun <T> writeList(list: List<T>, writeElement: (T) -> Unit) {
        dos.writeInt(list.size)
        for (item in list) writeElement(item)
    }

    fun writeStringList(list: List<String>) {
        dos.writeInt(list.size)
        for (s in list) writeString(s)
    }

    fun writeStringMap(map: Map<String, String>) {
        dos.writeInt(map.size)
        for ((k, v) in map) { writeString(k); writeString(v) }
    }

    fun writeStringFloatMap(map: Map<String, Float>) {
        dos.writeInt(map.size)
        for ((k, v) in map) { writeString(k); dos.writeFloat(v) }
    }

    fun flush() = dos.flush()
    fun close() = dos.close()
}

class MnxReader(input: InputStream) {
    val dis = DataInputStream(input)

    fun readByte(): Byte = dis.readByte()
    fun readBoolean(): Boolean = dis.readByte().toInt() != 0
    fun readShort(): Short = dis.readShort()
    fun readInt(): Int = dis.readInt()
    fun readLong(): Long = dis.readLong()
    fun readFloat(): Float = dis.readFloat()
    fun readDouble(): Double = dis.readDouble()
    fun readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        dis.readFully(buf)
        return buf
    }
    fun skip(count: Int) = dis.skipBytes(count)

    fun readString(): String {
        val len = dis.readInt()
        if (len < 0 || len > MAX_STRING_LENGTH) throw MnxFormatException("Invalid string length: $len")
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readUuid(): UUID {
        val msb = dis.readLong()
        val lsb = dis.readLong()
        return UUID(msb, lsb)
    }

    fun readFloatArray(): FloatArray {
        val count = dis.readInt()
        if (count < 0 || count > MAX_ARRAY_LENGTH) throw MnxFormatException("Invalid float array length: $count")
        return FloatArray(count) { dis.readFloat() }
    }

    fun readNullableFloatArray(): FloatArray? {
        val count = dis.readInt()
        if (count == -1) return null
        if (count < 0 || count > MAX_ARRAY_LENGTH) throw MnxFormatException("Invalid float array length: $count")
        return FloatArray(count) { dis.readFloat() }
    }

    inline fun <T> readList(readElement: () -> T): List<T> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_LIST_LENGTH) throw MnxFormatException("Invalid list length: $count")
        return List(count) { readElement() }
    }

    fun readStringList(): List<String> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_LIST_LENGTH) throw MnxFormatException("Invalid string list length: $count")
        return List(count) { readString() }
    }

    fun readStringMap(): Map<String, String> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_MAP_LENGTH) throw MnxFormatException("Invalid map size: $count")
        val map = LinkedHashMap<String, String>(count)
        repeat(count) { map[readString()] = readString() }
        return map
    }

    fun readStringFloatMap(): Map<String, Float> {
        val count = dis.readInt()
        if (count < 0 || count > MAX_MAP_LENGTH) throw MnxFormatException("Invalid map size: $count")
        val map = LinkedHashMap<String, Float>(count)
        repeat(count) { map[readString()] = dis.readFloat() }
        return map
    }

    fun close() = dis.close()

    companion object {
        const val MAX_STRING_LENGTH = 10 * 1024 * 1024
        const val MAX_ARRAY_LENGTH = 1_000_000
        const val MAX_LIST_LENGTH = 1_000_000
        const val MAX_MAP_LENGTH = 1_000_000
    }
}

inline fun mnxSerialize(block: MnxWriter.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    val writer = MnxWriter(baos)
    writer.block()
    writer.flush()
    return baos.toByteArray()
}

inline fun <T> mnxDeserialize(data: ByteArray, block: MnxReader.() -> T): T {
    val reader = MnxReader(data.inputStream())
    return reader.block()
}

class MnxFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

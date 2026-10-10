package com.kaleaon.mnxmindmaker.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class HashUtilsTest {

    @Test
    fun sha256Hex_emptyString_returnsExpectedHash() {
        val input = ""
        val expectedHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actualHex = HashUtils.sha256Hex(input)
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun sha256Hex_simpleString_returnsExpectedHash() {
        val input = "hello world"
        val expectedHex = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        val actualHex = HashUtils.sha256Hex(input)
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun sha256Hex_multiByteUtf8String_producesSameHashForStringAndByteArray() {
        val input = "Hello, 世界 🌍! MNX MindMaker graph continuity test 🧠"
        val bytes = input.toByteArray(StandardCharsets.UTF_8)

        val stringHash = HashUtils.sha256Hex(input)
        val byteArrayHash = HashUtils.sha256Hex(bytes)

        assertEquals(64, stringHash.length)
        assertEquals(stringHash, byteArrayHash)
    }

    @Test
    fun sha256Hex_byteArrayOverload_returnsExpectedHash() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val expectedHex = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        val actualHex = HashUtils.sha256Hex(bytes)
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun sha256Hex_lowercaseFormattingEnforced() {
        val input = "Test Case Hashing"
        val actualHex = HashUtils.sha256Hex(input)
        assertEquals(actualHex.lowercase(), actualHex)
        assertEquals(64, actualHex.length)
    }

    @Test
    fun sha256_returnsRaw32ByteDigest() {
        val input = "hello world"
        val rawBytes = HashUtils.sha256(input)
        assertEquals(32, rawBytes.size)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", HashUtils.toHexString(rawBytes))
    }

    @Test
    fun sha256Hex_and_sha256Bytes_inputStream_hashesStreamContent() {
        val content = "Stream content for SHA-256 validation"
        val inputStream = ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
        val streamHex = HashUtils.sha256Hex(inputStream)
        val stringHex = HashUtils.sha256Hex(content)

        assertEquals(stringHex, streamHex)

        val streamBytesStream = ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
        val rawBytes = HashUtils.sha256Bytes(streamBytesStream)
        assertArrayEquals(HashUtils.sha256(content), rawBytes)
    }

    @Test
    fun sha256Hex_file_hashesFileContent() {
        val tempFile = File.createTempFile("sha256_test", ".txt")
        try {
            val content = "File payload for SHA-256 test"
            tempFile.writeText(content, StandardCharsets.UTF_8)

            val fileHex = HashUtils.sha256Hex(tempFile)
            val expectedHex = HashUtils.sha256Hex(content)

            assertEquals(expectedHex, fileHex)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun sha256Digest_returnsSha256AlgorithmInstance() {
        val digest = HashUtils.sha256Digest()
        assertNotNull(digest)
        assertEquals("SHA-256", digest.algorithm)
    }

    @Test
    fun toHexString_formatsBytesAsLowercaseHex() {
        val bytes = byteArrayOf(0x00.toByte(), 0x0F.toByte(), 0x10.toByte(), 0xFF.toByte())
        val hex = HashUtils.toHexString(bytes)
        assertEquals("000f10ff", hex)
    }
}

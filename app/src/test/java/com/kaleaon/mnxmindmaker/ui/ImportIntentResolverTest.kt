package com.kaleaon.mnxmindmaker.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportIntentResolverTest {

    @Test
    fun `ignores non-view intents`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_MAIN,
            type = "application/json",
            dataString = "content://provider/shared/sample.json"
        )

        assertEquals(ImportIntentResolver.Resolution.Ignore, resolution)
    }

    @Test
    fun `accepts supported mime type with content uri`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_VIEW,
            type = "application/json",
            dataString = "content://provider/shared/sample.unknown"
        )

        assertEquals(
            ImportIntentResolver.Resolution.Import("content://provider/shared/sample.unknown"),
            resolution
        )
    }

    @Test
    fun `accepts supported extension when mime type missing`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_VIEW,
            type = null,
            dataString = "file:///storage/emulated/0/Download/notes.md"
        )

        assertEquals(
            ImportIntentResolver.Resolution.Import("file:///storage/emulated/0/Download/notes.md"),
            resolution
        )
    }

    @Test
    fun `returns unsupported for non-importable type`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_VIEW,
            type = "image/png",
            dataString = "content://provider/shared/image.png"
        )

        assertEquals(ImportIntentResolver.Resolution.Unsupported, resolution)
    }

    @Test
    fun `returns invalid uri for malformed uri string`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_VIEW,
            type = "application/json",
            dataString = "bad uri"
        )

        assertEquals(ImportIntentResolver.Resolution.InvalidUri, resolution)
    }

    @Test
    fun `returns unsupported when scheme is not file sharing compatible`() {
        val resolution = ImportIntentResolver.resolve(
            action = Intent.ACTION_VIEW,
            type = "application/json",
            dataString = "https://example.com/export.json"
        )

        assertTrue(resolution is ImportIntentResolver.Resolution.Unsupported)
    }
}

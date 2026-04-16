package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.security.EncryptedArtifactStore
import java.io.File

class EncryptedBackupRepository(context: Context) {
    private val store = EncryptedArtifactStore(context)
    private val filesDir = context.filesDir

    fun exportEncryptedBackup(passphrase: String, outFile: File): File {
        val payloads = mutableMapOf<String, ByteArray>()
        collectFiles(File(filesDir, "mnx_exports"), payloads)
        collectFiles(File(filesDir, "mnx_continuity"), payloads)
        collectFiles(File(filesDir, "tooling"), payloads)
        collectFiles(File(filesDir, "memory"), payloads)
        collectFiles(File(filesDir, ""), payloads, includeTopLevel = setOf("memory_store.json"))
        return store.exportBackup(payloads, passphrase, outFile)
    }

    fun recoverKeysFromBackup(backupFile: File, passphrase: String) {
        store.recoverHierarchyFromBackup(backupFile.readText(), passphrase)
    }

    private fun collectFiles(
        root: File,
        output: MutableMap<String, ByteArray>,
        includeTopLevel: Set<String> = emptySet()
    ) {
        if (!root.exists()) return
        root.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> collectFiles(file, output)
                includeTopLevel.isNotEmpty() && file.parentFile == root && file.name !in includeTopLevel -> Unit
                else -> output[file.absolutePath.removePrefix(filesDir.absolutePath + File.separator)] = file.readBytes()
            }
        }
    }
}

package com.kaleaon.mnxmindmaker.util.tooling

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject

class SkillPackLoader(
    private val assets: AssetManager,
    private val basePath: String = ASSET_SKILLS_PATH,
    private val validator: SkillManifestValidator
) {

    fun load(): SkillPackLoadReport {
        val entries = assets.list(basePath)?.filter { it.endsWith(".json", ignoreCase = true) } ?: emptyList()
        if (entries.isEmpty()) {
            return SkillPackLoadReport()
        }

        val loaded = mutableListOf<LoadedSkillPack>()
        val disabled = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val issues = mutableListOf<SkillPackValidationIssue>()

        for (entry in entries.sorted()) {
            val source = "$basePath/$entry"
            val root = try {
                val raw = assets.open(source).bufferedReader().use { it.readText() }
                JSONObject(raw)
            } catch (e: Exception) {
                val msg = "Failed to parse $source: ${e.message}"
                Log.w(TAG, msg)
                skipped += source
                issues += SkillPackValidationIssue(source, msg)
                continue
            }

            val (manifest, validationIssues) = validator.validate(source, root)
            if (validationIssues.isNotEmpty() || manifest == null) {
                skipped += source
                issues += validationIssues
                validationIssues.forEach { Log.w(TAG, "Skipping $source: ${it.message}") }
                continue
            }

            if (!manifest.enabled) {
                disabled += source
                Log.i(TAG, "Skill pack disabled by manifest flag: $source")
                continue
            }

            val tools = manifest.tools.map { tool ->
                val operationClass = tool.risk.operationClass ?: defaultOperationClassForHandler(tool.handlerId)
                ToolSpec(
                    name = tool.name,
                    description = tool.description,
                    operationClass = operationClass,
                    inputSchema = JSONObject(tool.inputSchema.toString()),
                    requiresConfirmation = tool.risk.requiresConfirmation ?: (operationClass != ToolOperationClass.READ_ONLY)
                )
            }

            loaded += LoadedSkillPack(source = source, manifest = manifest, tools = tools)
            Log.i(TAG, "Loaded skill pack ${manifest.packId} (${manifest.version}) from $source with ${tools.size} tools")
        }

        return SkillPackLoadReport(
            loadedPacks = loaded,
            disabledPacks = disabled,
            skippedPacks = skipped,
            validationIssues = issues
        )
    }

    companion object {
        private const val TAG = "SkillPackLoader"
        const val ASSET_SKILLS_PATH = "skills"

        private fun defaultOperationClassForHandler(handlerId: String): ToolOperationClass {
            return when {
                handlerId.startsWith("graph.read") || handlerId.startsWith("memory.read") -> ToolOperationClass.READ_ONLY
                handlerId.startsWith("graph.write") || handlerId.startsWith("memory.write") -> ToolOperationClass.MUTATING
                else -> ToolOperationClass.MUTATING
            }
        }
    }
}

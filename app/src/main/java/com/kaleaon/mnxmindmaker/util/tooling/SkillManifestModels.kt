package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONObject

/** JSON manifest root for a tool skill pack under assets/skills/*.json. */
data class SkillPackManifest(
    val packId: String,
    val version: String,
    val enabled: Boolean,
    val tools: List<ManifestToolSpec>
)

data class ManifestToolSpec(
    val name: String,
    val description: String,
    val handlerId: String,
    val inputSchema: JSONObject,
    val risk: ManifestRiskFlags = ManifestRiskFlags(),
    val playbook: ManifestPlaybook? = null
)

data class ManifestRiskFlags(
    val operationClass: ToolOperationClass? = null,
    val requiresConfirmation: Boolean? = null
)

/**
 * Optional guided workflow metadata inspired by local-first agent playbooks.
 * This is appended into tool descriptions to help smaller models follow stable
 * step-by-step tool usage patterns.
 */
data class ManifestPlaybook(
    val summary: String? = null,
    val steps: List<String> = emptyList(),
    val source: String? = null
)

data class LoadedSkillPack(
    val source: String,
    val manifest: SkillPackManifest,
    val tools: List<ToolSpec>
)

data class SkillPackValidationIssue(
    val source: String,
    val message: String
)

data class SkillPackLoadReport(
    val loadedPacks: List<LoadedSkillPack> = emptyList(),
    val disabledPacks: List<String> = emptyList(),
    val skippedPacks: List<String> = emptyList(),
    val validationIssues: List<SkillPackValidationIssue> = emptyList()
)

/** Singleton diagnostics surface consumed by settings/debug UI. */
object SkillPackDiagnosticsStore {
    @Volatile
    var latestReport: SkillPackLoadReport = SkillPackLoadReport()
        private set

    fun update(report: SkillPackLoadReport) {
        latestReport = report
    }
}

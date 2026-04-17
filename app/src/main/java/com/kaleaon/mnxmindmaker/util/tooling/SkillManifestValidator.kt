package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONArray
import org.json.JSONObject

class SkillManifestValidator(
    private val approvedHandlerIds: Set<String>
) {

    fun validate(source: String, root: JSONObject): Pair<SkillPackManifest?, List<SkillPackValidationIssue>> {
        val issues = mutableListOf<SkillPackValidationIssue>()

        val packId = root.optString("pack_id").trim()
        if (packId.isBlank()) issues += issue(source, "Missing required 'pack_id'")

        val version = root.optString("version").trim().ifBlank { "1" }
        val enabled = root.optBoolean("enabled", true)

        val toolsJson = root.optJSONArray("tools")
        if (toolsJson == null) {
            issues += issue(source, "Missing required 'tools' array")
            return null to issues
        }

        val tools = mutableListOf<ManifestToolSpec>()
        val seenNames = mutableSetOf<String>()

        for (i in 0 until toolsJson.length()) {
            val toolObj = toolsJson.optJSONObject(i)
            if (toolObj == null) {
                issues += issue(source, "tools[$i] must be an object")
                continue
            }

            val name = toolObj.optString("name").trim()
            if (name.isBlank()) {
                issues += issue(source, "tools[$i].name is required")
                continue
            }
            if (!seenNames.add(name)) {
                issues += issue(source, "Duplicate tool name '$name' in manifest")
            }

            val description = toolObj.optString("description").trim()
            if (description.isBlank()) {
                issues += issue(source, "tools[$i].description is required")
            }

            val handlerId = toolObj.optString("handler_id").trim()
            if (handlerId.isBlank()) {
                issues += issue(source, "tools[$i].handler_id is required")
            } else if (handlerId !in approvedHandlerIds) {
                issues += issue(source, "tools[$i].handler_id '$handlerId' is not approved")
            }

            val inputSchema = toolObj.optJSONObject("input_schema")
            if (inputSchema == null) {
                issues += issue(source, "tools[$i].input_schema must be a JSON object")
            } else {
                issues += validateSchema(source, "tools[$i].input_schema", inputSchema)
            }

            val riskObj = toolObj.optJSONObject("risk")
            val risk = parseRisk(source, "tools[$i].risk", riskObj, issues)
            val playbook = when {
                !toolObj.has("playbook") -> null
                toolObj.opt("playbook") !is JSONObject -> {
                    issues += issue(source, "tools[$i].playbook must be an object")
                    null
                }
                else -> parsePlaybook(source, "tools[$i].playbook", toolObj.getJSONObject("playbook"), issues)
            }

            if (name.isNotBlank() && description.isNotBlank() && handlerId.isNotBlank() && inputSchema != null) {
                tools += ManifestToolSpec(
                    name = name,
                    description = description,
                    handlerId = handlerId,
                    inputSchema = JSONObject(inputSchema.toString()),
                    risk = risk,
                    playbook = playbook
                )
            }
        }

        if (issues.isNotEmpty()) return null to issues

        return SkillPackManifest(
            packId = packId,
            version = version,
            enabled = enabled,
            tools = tools
        ) to emptyList()
    }

    private fun parseRisk(
        source: String,
        path: String,
        riskObj: JSONObject?,
        issues: MutableList<SkillPackValidationIssue>
    ): ManifestRiskFlags {
        if (riskObj == null) return ManifestRiskFlags()

        val operationClass = riskObj.optString("operation_class").trim().ifBlank { null }?.let { raw ->
            ToolOperationClass.entries.firstOrNull { it.name == raw } ?: run {
                issues += issue(source, "$path.operation_class '$raw' is invalid")
                null
            }
        }

        val requiresConfirmation = if (riskObj.has("requires_confirmation")) {
            when (val raw = riskObj.opt("requires_confirmation")) {
                is Boolean -> raw
                else -> {
                    issues += issue(source, "$path.requires_confirmation must be boolean")
                    null
                }
            }
        } else {
            null
        }

        return ManifestRiskFlags(
            operationClass = operationClass,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun parsePlaybook(
        source: String,
        path: String,
        playbookObj: JSONObject?,
        issues: MutableList<SkillPackValidationIssue>
    ): ManifestPlaybook? {
        if (playbookObj == null) return null

        val summary = playbookObj.optString("summary").trim().ifBlank { null }
        val sourceRef = playbookObj.optString("source").trim().ifBlank { null }

        val steps = mutableListOf<String>()
        if (playbookObj.has("steps")) {
            val stepsJson = playbookObj.optJSONArray("steps")
            if (stepsJson == null) {
                issues += issue(source, "$path.steps must be an array when present")
                return null
            }

            for (idx in 0 until stepsJson.length()) {
                val raw = stepsJson.opt(idx)
                if (raw !is String) {
                    issues += issue(source, "$path.steps[$idx] must be a string")
                    continue
                }
                val step = raw.trim()
                if (step.isBlank()) {
                    issues += issue(source, "$path.steps[$idx] must not be blank")
                    continue
                }
                steps += step
            }

            if (steps.isEmpty()) {
                issues += issue(source, "$path.steps must include at least one non-empty step")
                return null
            }
        }

        return ManifestPlaybook(
            summary = summary,
            steps = steps,
            source = sourceRef
        )
    }

    private fun validateSchema(source: String, path: String, schema: JSONObject): List<SkillPackValidationIssue> {
        val issues = mutableListOf<SkillPackValidationIssue>()
        val type = schema.optString("type")
        if (type.isBlank()) {
            issues += issue(source, "$path.type is required")
        } else if (type !in setOf("object", "array", "string", "number", "integer", "boolean", "null")) {
            issues += issue(source, "$path.type '$type' is not supported")
        }

        if (schema.has("required") && schema.opt("required") !is JSONArray) {
            issues += issue(source, "$path.required must be an array")
        }

        if (schema.has("properties") && schema.opt("properties") !is JSONObject) {
            issues += issue(source, "$path.properties must be an object")
        }

        if (schema.has("additionalProperties") && schema.opt("additionalProperties") !is Boolean) {
            issues += issue(source, "$path.additionalProperties must be boolean")
        }

        val properties = schema.optJSONObject("properties")
        properties?.keys()?.forEach { key ->
            val child = properties.optJSONObject(key)
            if (child == null) {
                issues += issue(source, "$path.properties.$key must be an object schema")
            } else {
                issues += validateSchema(source, "$path.properties.$key", child)
            }
        }

        if (schema.has("items")) {
            val items = schema.optJSONObject("items")
            if (items == null) {
                issues += issue(source, "$path.items must be an object schema")
            } else {
                issues += validateSchema(source, "$path.items", items)
            }
        }

        return issues
    }

    private fun issue(source: String, message: String): SkillPackValidationIssue =
        SkillPackValidationIssue(source = source, message = message)
}

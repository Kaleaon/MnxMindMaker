package com.kaleaon.mnxmindmaker.util.moderation

enum class ModerationStage {
    PROMPT,
    MEMORY_WRITE,
    OUTPUT
}

enum class ModerationAction {
    ALLOW,
    MASK,
    DENY
}

data class ModerationRequest(
    val text: String,
    val stage: ModerationStage,
    val policyId: String = "default"
)

data class ModerationResult(
    val action: ModerationAction,
    val text: String,
    val policyId: String,
    val reason: String? = null,
    val detectedEntityTypes: Set<String> = emptySet(),
    val maskedEntityCount: Int = 0
)

fun interface ModerationPolicy {
    fun apply(request: ModerationRequest): ModerationResult
}

class ModerationPipeline(
    private val policies: List<ModerationPolicy> = listOf(PassThroughModerationPolicy)
) {

    fun moderate(request: ModerationRequest): ModerationResult {
        var current = ModerationResult(
            action = ModerationAction.ALLOW,
            text = request.text,
            policyId = request.policyId
        )

        for (policy in policies) {
            val candidate = policy.apply(request.copy(text = current.text))
            current = when {
                candidate.action == ModerationAction.DENY -> candidate
                candidate.action == ModerationAction.MASK -> merge(current, candidate)
                else -> merge(current, candidate.copy(text = current.text, maskedEntityCount = 0))
            }
            if (current.action == ModerationAction.DENY) return current
        }

        return current
    }

    private fun merge(base: ModerationResult, next: ModerationResult): ModerationResult {
        val mergedAction = when {
            base.action == ModerationAction.DENY || next.action == ModerationAction.DENY -> ModerationAction.DENY
            base.action == ModerationAction.MASK || next.action == ModerationAction.MASK -> ModerationAction.MASK
            else -> ModerationAction.ALLOW
        }

        return ModerationResult(
            action = mergedAction,
            text = next.text,
            policyId = next.policyId.ifBlank { base.policyId },
            reason = next.reason ?: base.reason,
            detectedEntityTypes = base.detectedEntityTypes + next.detectedEntityTypes,
            maskedEntityCount = base.maskedEntityCount + next.maskedEntityCount
        )
    }
}

object PassThroughModerationPolicy : ModerationPolicy {
    override fun apply(request: ModerationRequest): ModerationResult {
        return ModerationResult(
            action = ModerationAction.ALLOW,
            text = request.text,
            policyId = request.policyId
        )
    }
}

data class StageActionPolicy(
    val defaultAction: ModerationAction = ModerationAction.ALLOW,
    val denyEntityTypes: Set<String> = emptySet(),
    val maskEntityTypes: Set<String> = emptySet(),
    val allowEntityTypes: Set<String> = emptySet()
)

class SensitiveEntityModerationPolicy(
    private val stagePolicies: Map<ModerationStage, StageActionPolicy> = defaultStagePolicies,
    private val entityDetectors: Map<String, Regex> = defaultEntityDetectors
) : ModerationPolicy {

    override fun apply(request: ModerationRequest): ModerationResult {
        val stagePolicy = stagePolicies[request.stage] ?: StageActionPolicy()
        val detections = entityDetectors.mapValues { (_, regex) -> regex.findAll(request.text).toList() }
            .filterValues { it.isNotEmpty() }

        if (detections.isEmpty()) {
            return ModerationResult(
                action = ModerationAction.ALLOW,
                text = request.text,
                policyId = request.policyId
            )
        }

        val detectedTypes = detections.keys
        val action = resolveAction(stagePolicy, detectedTypes)

        if (action == ModerationAction.DENY) {
            return ModerationResult(
                action = ModerationAction.DENY,
                text = "",
                policyId = request.policyId,
                reason = "Denied by ${request.policyId} for ${request.stage.name.lowercase()} due to sensitive entities",
                detectedEntityTypes = detectedTypes,
                maskedEntityCount = 0
            )
        }

        val shouldMask = action == ModerationAction.MASK
        val maskedText = if (shouldMask) {
            maskText(request.text, detections.keys)
        } else {
            request.text
        }

        return ModerationResult(
            action = action,
            text = maskedText,
            policyId = request.policyId,
            reason = if (shouldMask) {
                "Masked sensitive entities for ${request.stage.name.lowercase()}"
            } else {
                null
            },
            detectedEntityTypes = detections.keys,
            maskedEntityCount = if (shouldMask) detections.values.sumOf { it.size } else 0
        )
    }

    private fun resolveAction(stagePolicy: StageActionPolicy, detectedTypes: Set<String>): ModerationAction {
        if (detectedTypes.any { it in stagePolicy.allowEntityTypes }) {
            return ModerationAction.ALLOW
        }
        if (detectedTypes.any { it in stagePolicy.denyEntityTypes }) {
            return ModerationAction.DENY
        }
        if (detectedTypes.any { it in stagePolicy.maskEntityTypes }) {
            return ModerationAction.MASK
        }
        return stagePolicy.defaultAction
    }

    private fun maskText(text: String, entityTypes: Set<String>): String {
        var masked = text
        entityTypes.forEach { entityType ->
            val regex = entityDetectors[entityType] ?: return@forEach
            masked = regex.replace(masked) { "[REDACTED:${entityType.uppercase()}]" }
        }
        return masked
    }

    companion object {
        val defaultEntityDetectors: Map<String, Regex> = mapOf(
            "email" to Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            "phone" to Regex("(?:\\+?1[ .-]?)?(?:\\(?\\d{3}\\)?[ .-]?)\\d{3}[ .-]?\\d{4}\\b"),
            "ssn" to Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            "credit_card" to Regex("\\b(?:\\d[ -]*?){13,16}\\b"),
            "api_key" to Regex("\\b(?:sk|pk|api|key)_[A-Za-z0-9]{10,}\\b", RegexOption.IGNORE_CASE)
        )

        val defaultStagePolicies: Map<ModerationStage, StageActionPolicy> = mapOf(
            ModerationStage.PROMPT to StageActionPolicy(
                defaultAction = ModerationAction.ALLOW,
                denyEntityTypes = setOf("ssn", "credit_card"),
                maskEntityTypes = setOf("email", "phone", "api_key")
            ),
            ModerationStage.MEMORY_WRITE to StageActionPolicy(
                defaultAction = ModerationAction.MASK,
                denyEntityTypes = setOf("ssn", "credit_card"),
                maskEntityTypes = setOf("email", "phone", "api_key")
            ),
            ModerationStage.OUTPUT to StageActionPolicy(
                defaultAction = ModerationAction.MASK,
                denyEntityTypes = setOf("ssn"),
                maskEntityTypes = setOf("email", "phone", "credit_card", "api_key")
            )
        )
    }
}

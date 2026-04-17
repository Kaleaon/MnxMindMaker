package com.kaleaon.mnxmindmaker.util.eval

/**
 * Aggregate durability gate used by release automation.
 *
 * Every category is expected in [0.0, 1.0].
 */
data class DurabilityCategoryScores(
    val importExportRoundTrip: Double,
    val retrievalAccuracy: Double,
    val migrationSafety: Double,
    val policyEnforcement: Double,
    val failoverBehavior: Double
)

data class DurabilityGateResult(
    val overallScore: Double,
    val threshold: Double,
    val perCategory: Map<String, Double>
) {
    val passed: Boolean = overallScore >= threshold
}

object DurabilityGate {
    private const val DEFAULT_THRESHOLD = 0.90

    fun evaluate(
        scores: DurabilityCategoryScores,
        threshold: Double = DEFAULT_THRESHOLD
    ): DurabilityGateResult {
        val perCategory = linkedMapOf(
            "import_export_round_trip" to normalize(scores.importExportRoundTrip),
            "retrieval_accuracy" to normalize(scores.retrievalAccuracy),
            "migration_safety" to normalize(scores.migrationSafety),
            "policy_enforcement" to normalize(scores.policyEnforcement),
            "failover_behavior" to normalize(scores.failoverBehavior)
        )
        val overallScore = perCategory.values.average()
        return DurabilityGateResult(
            overallScore = overallScore,
            threshold = normalize(threshold),
            perCategory = perCategory
        )
    }

    private fun normalize(value: Double): Double = value.coerceIn(0.0, 1.0)
}

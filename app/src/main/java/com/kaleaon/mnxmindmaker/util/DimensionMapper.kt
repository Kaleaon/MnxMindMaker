package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.mnx.MnxDimensionalRef
import com.kaleaon.mnxmindmaker.mnx.MnxDimensionalRefs

/**
 * Assigns semantically meaningful N-dimensional coordinates to each [MindNode].
 *
 * The visual canvas only renders two spatial dimensions (x, y). This mapper
 * populates [MindNode.dimensions] with additional named axes so that the full
 * multi-dimensional structure of the AI mind is preserved in the exported
 * .mnx DIMENSIONAL_REFS section. There is no upper bound on the number of
 * dimensions — each NodeType carries the axes most relevant to its domain.
 *
 * ### Per-type default dimension sets
 * | NodeType     | Dimensions (examples — all names are open strings)             |
 * |------------- |----------------------------------------------------------------|
 * | IDENTITY     | self_clarity, stability, coherence, distinctiveness            |
 * | MEMORY       | recency, importance, valence, distinctiveness, retrieval_ease  |
 * | KNOWLEDGE    | confidence, recency, importance, specificity, verifiability    |
 * | AFFECT       | valence, arousal, dominance, intensity, social_share           |
 * | PERSONALITY  | openness, conscientiousness, extraversion, agreeableness, neuroticism |
 * | BELIEF       | confidence, evidence_strength, emotional_loading, social_consensus, revisability |
 * | VALUE        | ethical_weight, social_impact, personal_relevance, priority, universality |
 * | RELATIONSHIP | bond_strength, trust, reciprocity, history_depth, affective_tone |
 * | CUSTOM       | salience, novelty, utility                                     |
 *
 * All values default to 0.5 (neutral / mid-range) unless overridden by the
 * caller via [MindNode.dimensions]. Values are floats; bipolar axes (e.g.
 * valence, affective_tone) use [-1.0, 1.0]; unipolar axes use [0.0, 1.0].
 */
object DimensionMapper {

    /**
     * Return the ordered list of dimension names that are canonical for [type].
     * The order is stable and meaningful: later dimensions extend earlier ones
     * (i.e. first N dimensions form a coherent sub-space for any N).
     */
    fun defaultDimensionNames(type: NodeType): List<String> = when (type) {
        NodeType.IDENTITY -> listOf(
            "self_clarity",        // How clearly defined the identity is
            "stability",           // Resistance to change
            "coherence",           // Internal consistency
            "distinctiveness",     // Uniqueness relative to others
            "expressiveness"       // How outwardly manifested the identity is
        )
        NodeType.MEMORY -> listOf(
            "recency",             // How recent (0 = very old, 1 = just now)
            "importance",          // Subjective significance
            "valence",             // Emotional tone (-1 negative … +1 positive)
            "distinctiveness",     // How unique vs. redundant
            "retrieval_ease",      // Ease of recall
            "source_reliability",  // Trust in the original source
            "emotional_intensity"  // Strength of emotion attached
        )
        NodeType.KNOWLEDGE -> listOf(
            "confidence",          // Certainty of the fact
            "recency",             // How up-to-date
            "importance",          // Relevance to goals
            "specificity",         // General ↔ highly specific
            "verifiability",       // Can it be externally verified?
            "abstraction"          // Concrete ↔ abstract
        )
        NodeType.AFFECT -> listOf(
            "valence",             // Positive/negative (-1 … +1)
            "arousal",             // Calm ↔ excited (0 … 1)
            "dominance",           // Submissive ↔ in-control (0 … 1)
            "intensity",           // Magnitude of the emotion
            "social_share",        // Tendency to express this emotion outwardly
            "persistence"          // How long the affect lasts
        )
        NodeType.PERSONALITY -> listOf(
            "openness",            // Big Five O
            "conscientiousness",   // Big Five C
            "extraversion",        // Big Five E
            "agreeableness",       // Big Five A
            "neuroticism",         // Big Five N
            "curiosity",           // Extra: drive to explore
            "empathy"              // Extra: sensitivity to others
        )
        NodeType.BELIEF -> listOf(
            "confidence",          // Strength of conviction
            "evidence_strength",   // Quality of supporting evidence
            "emotional_loading",   // How emotionally charged
            "social_consensus",    // How widely shared
            "revisability",        // Openness to change
            "centrality",          // How core to the overall worldview
            "acquired_recency"     // When the belief was formed
        )
        NodeType.VALUE -> listOf(
            "ethical_weight",      // Moral significance
            "social_impact",       // Effect on others
            "personal_relevance",  // Importance to the individual
            "priority",            // Rank vs. competing values
            "universality",        // Applies broadly vs. narrowly
            "actionability",       // Guides concrete behaviour
            "intrinsic_worth"      // Value for its own sake vs. instrumental
        )
        NodeType.RELATIONSHIP -> listOf(
            "bond_strength",       // Closeness
            "trust",               // Reliability attributed to the other
            "reciprocity",         // Balance of giving/receiving
            "history_depth",       // Length of relationship
            "affective_tone",      // Overall positive/negative (-1 … +1)
            "dependency",          // Reliance on the other
            "conflict_level"       // Degree of ongoing friction
        )
        NodeType.CUSTOM -> listOf(
            "salience",            // How attention-grabbing
            "novelty",             // How new/surprising
            "utility",             // Practical usefulness
            "confidence"           // Certainty about the item
        )
    }

    /**
     * Return a fresh [Map] of dimension-name → default value (0.5 for all
     * unipolar axes, 0.0 for bipolar axes) for the given [NodeType].
     *
     * The caller can override specific entries via [MindNode.dimensions].
     */
    fun defaultDimensions(type: NodeType): Map<String, Float> {
        val bipolar = setOf("valence", "affective_tone")
        return defaultDimensionNames(type).associateWith { name ->
            if (name in bipolar) 0.0f else 0.5f
        }
    }

    /**
     * Merge [MindNode.dimensions] with the type-default dimensions.
     * Caller-supplied values take precedence; missing dimensions get defaults.
     */
    fun resolve(node: MindNode): Map<String, Float> {
        val defaults = defaultDimensions(node.type)
        return defaults + node.dimensions   // caller overrides win
    }

    /**
     * Build a flat [MnxDimensionalRefs] covering all nodes in [nodes].
     *
     * Each (node, dimension, value) triple becomes one [MnxDimensionalRef]
     * stored under the node's id as subject. This is written to the
     * DIMENSIONAL_REFS section of the .mnx file so the full N-dimensional
     * structure survives round-trips through the codec.
     */
    fun buildDimensionalRefs(nodes: List<MindNode>): MnxDimensionalRefs {
        val refs = nodes.flatMap { node ->
            resolve(node).map { (dim, value) ->
                MnxDimensionalRef(
                    subject = node.id,
                    dimension = dim,
                    target = value.toString(),
                    targetType = "scalar",
                    confidence = 1.0f,
                    metadata = mapOf("node_type" to node.type.name, "node_label" to node.label)
                )
            }
        }
        return MnxDimensionalRefs(refs)
    }

    /**
     * Rebuild the per-node dimension maps from a [MnxDimensionalRefs] section.
     * Returns a map of nodeId → (dimension → value).
     */
    fun restoreDimensions(refs: MnxDimensionalRefs): Map<String, Map<String, Float>> {
        val result = mutableMapOf<String, MutableMap<String, Float>>()
        for (ref in refs.refs) {
            if (ref.targetType != "scalar") continue
            val value = ref.target.toFloatOrNull() ?: continue
            result.getOrPut(ref.subject) { mutableMapOf() }[ref.dimension] = value
        }
        return result
    }
}

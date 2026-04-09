package com.kaleaon.mnxmindmaker.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalMetricsTest {

    @Test
    fun `recall at k and mrr are calculated correctly for mixed rankings`() {
        val rankings = listOf(
            listOf("a", "b", "c"),
            listOf("d", "e", "f"),
            listOf("x", "y", "z")
        )
        val relevant = listOf(
            setOf("b"),
            setOf("f"),
            setOf("q")
        )

        val recallAt1 = recallAtK(rankings, relevant, k = 1)
        val recallAt3 = recallAtK(rankings, relevant, k = 3)
        val mrr = meanReciprocalRank(rankings, relevant)

        assertEquals(0.0, recallAt1, 1e-9)
        assertEquals(2.0 / 3.0, recallAt3, 1e-9)
        assertEquals((1.0 / 2.0 + 1.0 / 3.0 + 0.0) / 3.0, mrr, 1e-9)
    }

    @Test
    fun `perfect first hit yields recall and mrr of one`() {
        val rankings = listOf(
            listOf("doc-1", "doc-2"),
            listOf("doc-3", "doc-4")
        )
        val relevant = listOf(
            setOf("doc-1"),
            setOf("doc-3")
        )

        assertEquals(1.0, recallAtK(rankings, relevant, k = 1), 1e-9)
        assertEquals(1.0, meanReciprocalRank(rankings, relevant), 1e-9)
    }

    private fun recallAtK(rankings: List<List<String>>, relevant: List<Set<String>>, k: Int): Double {
        require(rankings.size == relevant.size) { "rankings and relevant judgments must have the same size" }
        if (rankings.isEmpty() || k <= 0) return 0.0

        val hits = rankings.indices.count { idx ->
            rankings[idx].take(k).any { candidate -> candidate in relevant[idx] }
        }
        return hits.toDouble() / rankings.size.toDouble()
    }

    private fun meanReciprocalRank(rankings: List<List<String>>, relevant: List<Set<String>>): Double {
        require(rankings.size == relevant.size) { "rankings and relevant judgments must have the same size" }
        if (rankings.isEmpty()) return 0.0

        val reciprocalSum = rankings.indices.sumOf { idx ->
            val firstRelevantRank = rankings[idx].indexOfFirst { candidate -> candidate in relevant[idx] }
            if (firstRelevantRank < 0) 0.0 else 1.0 / (firstRelevantRank + 1).toDouble()
        }
        return reciprocalSum / rankings.size.toDouble()
    }
}

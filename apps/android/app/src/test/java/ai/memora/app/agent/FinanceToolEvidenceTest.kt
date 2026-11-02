package ai.memora.app.agent

import org.junit.Assert.*
import org.junit.Test

class FinanceToolEvidenceTest {
    @Test fun largeMultilingualHistoryIsBoundedWithoutLosingExactLedgerSummary() {
        val summary = mapOf("outstanding_debt_minor_units" to 27_000L)
        val events = (12 downTo 1).map { index -> mapOf<String, Any?>(
            "event_id" to "synthetic-$index", "source" to "əЖ".repeat(150),
            "annotations" to List(4) { "əЖ".repeat(250) },
        ) }
        val result = boundedFinanceEvidence(mapOf("financial_summary" to summary), events, 18)
        assertTrue(AgentCanonical.bytes(result).size <= 10_000)
        assertEquals(summary, result["financial_summary"])
        assertEquals(18, result["matching_event_count"])
        assertEquals(true, result["truncated"])
        val retained = result["events"] as List<*>
        assertTrue(retained.isNotEmpty() && retained.size < 12)
        assertEquals("synthetic-12", (retained.first() as Map<*, *>)["event_id"])
    }

    @Test fun smallHistoryPreservesEveryEventAndCompleteness() {
        val events = listOf(mapOf<String, Any?>("event_id" to "synthetic-one", "minor_units" to 8_000L))
        val result = boundedFinanceEvidence(emptyMap(), events, 1)
        assertEquals(events, result["events"])
        assertEquals(1, result["count"])
        assertEquals(false, result["truncated"])
    }
}

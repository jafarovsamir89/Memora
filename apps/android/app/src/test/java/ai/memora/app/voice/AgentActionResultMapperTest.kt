package ai.memora.app.voice

import ai.memora.app.agent.AgentToolObservation
import ai.memora.app.agent.ToolObservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionResultMapperTest {
    @Test fun cardsKeepOnlyFinalWriteStatusSafeCodeAndValidTargetId() {
        val turnId = "00000000-0000-4000-8000-000000000101"
        val targetId = "00000000-0000-4000-8000-000000000102"
        val attempts = listOf(
            AgentToolObservation("call-1", "record_money_event", ToolObservationStatus.FAILED,
                "temporary_failure", operationKey = "opaque-digest"),
            AgentToolObservation("call-2", "record_money_event", ToolObservationStatus.APPLIED,
                "receipt_recorded", mapOf("id" to targetId, "person_name" to "Synthetic Person", "minor_units" to 100_00),
                operationKey = "opaque-digest"),
            AgentToolObservation("call-3", "search_money", ToolObservationStatus.APPLIED,
                "finance_evidence_loaded", mapOf("person_name" to "Synthetic Person")),
            AgentToolObservation("call-4", "create_task", ToolObservationStatus.NEEDS_CONFIRMATION,
                "confirmation_required", mapOf("id" to "not-an-app-uuid")),
        )

        val cards = AgentActionResultMapper.map("scope", turnId, attempts, 1_800_000_000_000L)

        assertEquals(2, cards.size)
        assertEquals("APPLIED", cards[0].status)
        assertEquals("finance", cards[0].targetKind)
        assertEquals(targetId, cards[0].targetId)
        assertEquals("NEEDS_CONFIRMATION", cards[1].status)
        assertEquals("task", cards[1].targetKind)
        assertEquals(null, cards[1].targetId)
        val stored = cards.toString()
        assertFalse(stored.contains("Synthetic Person"))
        assertFalse(stored.contains("10000"))
        assertFalse(stored.contains("opaque-digest"))
        assertTrue(cards.all { it.turnId == turnId && it.scopeId == "scope" })
    }
}

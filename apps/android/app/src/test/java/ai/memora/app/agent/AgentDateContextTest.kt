package ai.memora.app.agent

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDateContextTest {
    @Test fun profileTimeZoneDeterminesTheAppDateAcrossYearBoundary() {
        val clock = Clock.fixed(Instant.parse("2025-12-31T21:30:00Z"), ZoneOffset.UTC)

        assertEquals("2026-01-01", AgentDateContext.currentLocalDate("Asia/Baku", clock))
        assertEquals("2025-12-31", AgentDateContext.currentLocalDate("UTC", clock))
    }

    @Test fun currentDateInstructionIsExplicitAndDoesNotDiscloseZoneId() {
        val instruction = AgentDateContext.promptInstruction("2026-11-10")

        assertTrue(instruction.contains("2026-11-10"))
        assertTrue(instruction.contains("authoritative"))
        assertTrue(instruction.contains("Do not use a year inferred from model knowledge"))
        assertFalse(instruction.contains("Asia/Baku"))
        assertTrue(AgentDateContext.promptInstruction("not-a-date").contains("Do not guess a year"))
    }

    @Test fun taskDateIntentResolvesAzRuEnRelativeDatesFromTheSameReferenceDate() {
        val today = LocalDate.parse("2026-11-10")

        assertEquals(LocalDate.parse("2026-11-11"), TaskDateIntent.resolveSingleDate("Sabah saat 9-da zəng et", today))
        assertEquals(LocalDate.parse("2026-11-11"), TaskDateIntent.resolveSingleDate("завтра в 09:00 позвонить", today))
        assertEquals(LocalDate.parse("2026-11-11"), TaskDateIntent.resolveSingleDate("Call tomorrow at 9", today))
        assertEquals(LocalDate.parse("2026-11-13"), TaskDateIntent.resolveSingleDate("Call next Friday", today))
    }

    @Test fun ambiguousDateAndConflictingRelativeDatesAreNotGuessed() {
        val today = LocalDate.parse("2026-11-10")

        assertNull(TaskDateIntent.resolveSingleDate("Schedule for 15 April", today))
        assertNull(TaskDateIntent.resolveSingleDate("Tomorrow or next Friday", today))
        assertTrue(TaskDateIntent.hasExplicitYear("Schedule this for 2025-04-15"))
        assertTrue(TaskDateIntent.explicitlyRefersToPastYear("do this last year"))
    }
}

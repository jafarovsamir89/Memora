package ai.memora.app.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VoiceMoneyMemoryTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private fun command(id: String = "one", name: String = "Samir", amount: String = "100",
        source: String = "Samir mənə 100 manat verdi.") =
        VoiceReceiptCommand(id, "person-$id", "receipt-$id", name, amount, source, now)

    @Test fun spokenReceiptCreatesLinkedPersonAndSourceAndAnswersFromSavedEvents() {
        val first = VoiceMoneyRules.save(VoiceMoneySnapshot(), command()) as VoiceMemoryChange.Applied
        assertEquals("Samir", first.snapshot.persons.single().name)
        assertEquals("Samir mənə 100 manat verdi.", first.snapshot.receipts.single().source)
        assertNull(first.snapshot.receipts.single().occurredAt)
        val answer = VoiceMoneyRules.received(first.snapshot, "Samir") as VoiceMoneyAnswer.Found
        assertEquals(10_000L, answer.totalMinor)
        assertEquals("AZN", answer.receipts.single().currency)
        assertEquals(VoiceMemoryChange.Duplicate, VoiceMoneyRules.save(first.snapshot, command()))
        assertEquals(VoiceMemoryChange.Conflict, VoiceMoneyRules.save(first.snapshot, command(source = "Samir mənə 100 manat verib.")))
    }

    @Test fun repeatedDistinctReceiptsAddExactlyAndUnknownIsNotZero() {
        val first = VoiceMoneyRules.save(VoiceMoneySnapshot(), command()) as VoiceMemoryChange.Applied
        val second = VoiceMoneyRules.save(first.snapshot, command("two", amount = "0,25", source = "Samir mənə 0,25 manat verdi.")) as VoiceMemoryChange.Applied
        assertEquals(1, second.snapshot.persons.size)
        assertEquals(10_025L, (VoiceMoneyRules.received(second.snapshot, "Samir") as VoiceMoneyAnswer.Found).totalMinor)
        assertEquals(VoiceMoneyAnswer.Missing, VoiceMoneyRules.received(second.snapshot, "Nigar"))
    }

    @Test fun questionsNegationsInventedNamesAmountsAndAmbiguityCannotCreateEvents() {
        val empty = VoiceMoneySnapshot()
        val bad = listOf(
            command(source = "Samir mənə nə qədər pul verdi?"),
            command(source = "Samir mənə 100 manat vermədi."),
            command(name = "Nigar"),
            command(amount = "200"),
            command(source = "Samir mənə 100 manat və 200 manat verdi."),
            command().copy(occurredAt = now),
        )
        bad.forEach { assertEquals(VoiceMemoryChange.Invalid, VoiceMoneyRules.save(empty, it)) }
        val ambiguous = VoiceMoneySnapshot(persons = listOf(VoicePerson("a", "Samir"), VoicePerson("b", "Samir")))
        assertEquals(VoiceMemoryChange.Ambiguous, VoiceMoneyRules.save(ambiguous, command()))
        assertEquals(VoiceMoneyAnswer.Ambiguous, VoiceMoneyRules.received(ambiguous, "Samir"))
    }

    @Test fun noFloatingPointRoundingOrOverflow() {
        assertEquals(123L, VoiceMoneyRules.parseMinor("1,23"))
        listOf("0", "1.234", "-1", "1e3", "999999999999999999999999").forEach { assertNull(VoiceMoneyRules.parseMinor(it)) }
    }
}

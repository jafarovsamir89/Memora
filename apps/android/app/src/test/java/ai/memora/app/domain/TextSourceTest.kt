package ai.memora.app.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TextSourceTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private fun source() = (TextSourceRules.create("source", "local", "r1", "Salam 🌍", now) as SourceChange.Changed).source

    @Test fun revisionKeepsOriginalEvidenceAndDoesNotReuseOffsetsForNewText() {
        val old = source()
        val revised = (TextSourceRules.revise(old, 1, "r2", "Salam dünya", now.plusSeconds(1)) as SourceChange.Changed).source
        assertEquals("🌍", TextSourceRules.quote(revised, TextEvidence("r1", 6, 8)))
        assertEquals("dü", TextSourceRules.quote(revised, TextEvidence("r2", 6, 8)))
        assertEquals("Salam 🌍", old.revisions.single().text)
    }

    @Test fun staleVersionAndReusedRevisionCannotAlterSource() {
        val original = source()
        assertEquals(SourceChange.Conflict, TextSourceRules.revise(original, 0, "r2", "other", now))
        assertEquals(SourceChange.Conflict, TextSourceRules.revise(original, 1, "r1", "other", now))
        assertEquals(SourceChange.Unchanged, TextSourceRules.revise(original, 1, "r2", "Salam 🌍", now))
        assertEquals(1, original.revisions.size)
    }

    @Test fun evidenceRejectsMissingRevisionOutOfRangeAndSplitSurrogate() {
        val original = source()
        assertNull(TextSourceRules.quote(original, TextEvidence("missing", 0, 1)))
        assertNull(TextSourceRules.quote(original, TextEvidence("r1", -1, 1)))
        assertNull(TextSourceRules.quote(original, TextEvidence("r1", 0, 20)))
        assertNull(TextSourceRules.quote(original, TextEvidence("r1", 6, 7)))
        assertNull(TextSourceRules.quote(original, TextEvidence("r1", 7, 8)))
    }

    @Test fun malformedTextIsRejectedButClockCorrectionDoesNotBlockRevision() {
        assertEquals(SourceChange.Invalid, TextSourceRules.create("s", "local", "r", "\uD800", now))
        val changed = TextSourceRules.revise(source(), 1, "r2", "new", now.minusSeconds(1)) as SourceChange.Changed
        assertEquals(2L, changed.source.revisions.last().number)
    }

    @Test fun invalidReconstructedHistoryCannotProvideEvidence() {
        val bad = source().copy(revisions = listOf(TextRevision("r1", 2, "text", now)))
        assertNull(TextSourceRules.quote(bad, TextEvidence("r1", 0, 1)))
        assertEquals(SourceChange.Invalid, TextSourceRules.revise(bad, 2, "r2", "new", now))
    }
}

package ai.memora.app.domain

import ai.memora.app.data.CaptureEntry
import ai.memora.app.data.CaptureOrigin
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LocalMemoryIndexTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private fun entry(text: String, archived: Boolean = false) = CaptureEntry("command", 1, "Asia/Baku",
        TextSource("source", "local", listOf(TextRevision("r1", 1, text, now))), CaptureOrigin.LOCAL, archived)

    @Test fun azerbaijaniDottedAndDotlessIAreDistinctAndRussianStillMatches() {
        val records = listOf(entry("İlqar İŞIQ üçün sabah позвонит Əliyə."))
        assertEquals(1, LocalMemoryIndex.search(records, "ilqar işıq ПОЗВОНИТ").size)
        assertTrue(LocalMemoryIndex.search(records, "ılqar").isEmpty())
        assertTrue(LocalMemoryIndex.search(records, "isik").isEmpty())
    }

    @Test fun retrievalLinksExactLatestRevisionAndKeepsNegation() {
        val original = entry("120 manat")
        val corrected = original.copy(source = original.source.copy(revisions = original.source.revisions +
            TextRevision("r2", 2, "120 deyil, 20 manat", now)))
        val match = LocalMemoryIndex.search(listOf(corrected), "20").single()
        assertEquals("r2", match.evidence.revisionId)
        assertEquals("120 deyil, 20 manat", TextSourceRules.quote(corrected.source, match.evidence))
        assertEquals(2, corrected.source.revisions.size)
    }

    @Test fun missingOrArchivedSourceDoesNotBecomeAnAnswer() {
        assertTrue(LocalMemoryIndex.search(emptyList(), "borc").isEmpty())
        val archived = listOf(entry("Əli 20 manat", archived = true))
        assertTrue(LocalMemoryIndex.search(archived, "Əli").isEmpty())
        assertEquals(1, LocalMemoryIndex.search(archived, "Əli", includeArchived = true).size)
    }
}

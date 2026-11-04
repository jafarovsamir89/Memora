package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarPersonLinkRepositoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun calendarPersonRelationRequiresOwnerCanonicalActiveRecordsAndKeepsReversibleHistory() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val scope = "synthetic-calendar-scope"
        domain.createObject(scope, "synthetic-calendar-person", DomainKind.PERSON, "Synthetic contact", null, null,
            "2026-11-03T10:00:00Z")
        domain.createObject(scope, "synthetic-calendar-note", DomainKind.NOTE, "Not a person", null, null,
            "2026-11-03T10:00:00Z")
        domain.createCalendarEvent(CalendarInput("synthetic-calendar-event", scope, "Synthetic appointment",
            "2026-11-07T11:00", "2026-11-07T11:30", "Asia/Baku", "minute"))
        val repository = CalendarPersonLinkRepository(db)

        assertTrue(runCatching { repository.confirm(scope, "synthetic-calendar-event", "synthetic-calendar-person",
            confirmedBy = "model", now = "2026-11-03T10:01:00Z") }.isFailure)
        assertTrue(runCatching { repository.confirm(scope, "missing-event", "synthetic-calendar-person",
            confirmedBy = "owner", now = "2026-11-03T10:01:00Z") }.isFailure)
        assertTrue(runCatching { repository.confirm(scope, "synthetic-calendar-event", "synthetic-calendar-note",
            confirmedBy = "owner", now = "2026-11-03T10:01:00Z") }.isFailure)
        assertTrue(runCatching { repository.confirm("other-scope", "synthetic-calendar-event", "synthetic-calendar-person",
            confirmedBy = "owner", now = "2026-11-03T10:01:00Z") }.isFailure)
        assertNull(db.protectedStoreDao().calendarPersonLink(scope, "synthetic-calendar-event", "synthetic-calendar-person"))

        val linked = repository.confirm(scope, "synthetic-calendar-event", "synthetic-calendar-person",
            linkId = "synthetic-calendar-person-link", confirmedBy = "owner", now = "2026-11-03T10:02:00Z")
        assertEquals("participant", linked.relation)
        assertEquals(linked, repository.confirm(scope, "synthetic-calendar-event", "synthetic-calendar-person",
            linkId = "ignored-idempotent-link", confirmedBy = "owner", now = "2026-11-03T10:03:00Z"))
        assertEquals(listOf(linked), db.protectedStoreDao().activeCalendarPersonLinks(scope, "synthetic-calendar-person"))
        assertEquals(listOf("linked"), db.protectedStoreDao().calendarPersonLinkRevisions(scope, linked.linkId).map { it.action })

        repository.retire(scope, linked.linkId, confirmedBy = "owner", now = "2026-11-03T10:04:00Z")
        assertTrue(db.protectedStoreDao().activeCalendarPersonLinks(scope, "synthetic-calendar-person").isEmpty())
        val restored = repository.confirm(scope, "synthetic-calendar-event", "synthetic-calendar-person",
            relation = "organizer", confirmedBy = "owner", now = "2026-11-03T10:05:00Z")
        assertEquals(linked.linkId, restored.linkId)
        assertEquals(3L, restored.version)
        assertEquals(listOf("linked", "retired", "restored"),
            db.protectedStoreDao().calendarPersonLinkRevisions(scope, linked.linkId).map { it.action })
        assertEquals("organizer", db.protectedStoreDao().activeCalendarPersonLinks(scope, "synthetic-calendar-person").single().relation)
    }
}

package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID

/** Maintains explicit owner-confirmed calendar/person links without copying event data. */
internal class CalendarPersonLinkRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()

    suspend fun confirm(
        scope: String,
        eventId: String,
        domainPersonId: String,
        relation: String = "participant",
        linkId: String = UUID.randomUUID().toString(),
        confirmedBy: String,
        now: String = Instant.now().toString(),
    ): CalendarPersonLinkRow = db.withTransaction {
        require(scope.isNotBlank() && eventId.isNotBlank() && domainPersonId.isNotBlank())
        require(confirmedBy == "owner") { "calendar_person_link_requires_owner" }
        require(linkId.isNotBlank() && linkId.length <= 100)
        require(relation.matches(Regex("[a-z][a-z0-9_]{0,39}")))
        Instant.parse(now)
        val event = dao.calendarEvent(scope, eventId)
        check(event?.status == "active") { "calendar_event_not_found_or_inactive" }
        val person = dao.domainObject(scope, domainPersonId)
        check(person?.kind == DomainKind.PERSON.name && person.deletedAt == null) { "domain_person_not_found" }

        val existing = dao.calendarPersonLink(scope, eventId, domainPersonId)
        if (existing != null && existing.deletedAt == null) {
            check(existing.relation == relation) { "calendar_person_link_conflict" }
            return@withTransaction existing
        }
        val row = if (existing == null) {
            CalendarPersonLinkRow(linkId, scope, eventId, domainPersonId, relation, 1, "owner", now, now, null).also {
                dao.insertCalendarPersonLink(it)
                dao.insertCalendarPersonLinkRevision(CalendarPersonLinkRevisionRow(
                    it.linkId, it.version, scope, eventId, domainPersonId, "linked", relation, "owner", now,
                ))
            }
        } else {
            val nextVersion = Math.addExact(existing.version, 1L)
            check(dao.restoreCalendarPersonLink(scope, existing.linkId, existing.version, nextVersion, relation, now) == 1) {
                "calendar_person_link_conflict"
            }
            existing.copy(relation = relation, version = nextVersion, updatedAt = now, deletedAt = null).also {
                dao.insertCalendarPersonLinkRevision(CalendarPersonLinkRevisionRow(
                    it.linkId, it.version, scope, eventId, domainPersonId, "restored", relation, "owner", now,
                ))
            }
        }
        check(dao.calendarPersonLink(scope, eventId, domainPersonId) == row &&
            dao.calendarPersonLinkRevisions(scope, row.linkId).lastOrNull()?.let { it.version == row.version } == true) {
            "calendar_person_link_readback_mismatch"
        }
        row
    }

    suspend fun retire(scope: String, linkId: String, confirmedBy: String, now: String = Instant.now().toString()) = db.withTransaction {
        require(confirmedBy == "owner") { "calendar_person_unlink_requires_owner" }
        Instant.parse(now)
        val current = dao.calendarPersonLinkById(scope, linkId)?.takeIf { it.deletedAt == null }
            ?: error("calendar_person_link_not_found")
        val nextVersion = Math.addExact(current.version, 1L)
        check(dao.retireCalendarPersonLink(scope, linkId, current.version, nextVersion, now) == 1) {
            "calendar_person_link_conflict"
        }
        val retired = current.copy(version = nextVersion, updatedAt = now, deletedAt = now)
        dao.insertCalendarPersonLinkRevision(CalendarPersonLinkRevisionRow(
            linkId, retired.version, scope, current.eventId, current.domainPersonId, "retired", current.relation, "owner", now,
        ))
        check(dao.calendarPersonLink(scope, current.eventId, current.domainPersonId) == retired &&
            dao.calendarPersonLinkRevisions(scope, linkId).lastOrNull()?.action == "retired") {
            "calendar_person_unlink_readback_mismatch"
        }
    }
}

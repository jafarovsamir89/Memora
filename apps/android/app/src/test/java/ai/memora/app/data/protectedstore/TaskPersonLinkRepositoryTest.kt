package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.domain.TaskEvent
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
class TaskPersonLinkRepositoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun directTaskPersonLinkRequiresOwnerAndCanonicalRecordsAndKeepsReversibleHistory() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val cases = ProtectedCaseRepository(db)
        val scope = "synthetic-scope"
        domain.createObject(scope, "synthetic-person", DomainKind.PERSON, "Samir", null, null, "2026-11-03T10:00:00Z")
        domain.appendTaskEvent(TaskEvent.Created("synthetic-task", scope, "synthetic-owner", "synthetic-create", "Call about BMW"))

        assertTrue(runCatching {
            cases.confirmTaskPersonLink(scope, "synthetic-task", "synthetic-person", confirmedBy = "model",
                now = "2026-11-03T10:01:00Z")
        }.isFailure)
        assertTrue(runCatching {
            cases.confirmTaskPersonLink(scope, "missing-task", "synthetic-person", confirmedBy = "owner",
                now = "2026-11-03T10:01:00Z")
        }.isFailure)
        assertNull(db.protectedStoreDao().taskPersonLink(scope, "synthetic-task", "synthetic-person"))

        val linked = cases.confirmTaskPersonLink(scope, "synthetic-task", "synthetic-person", "assigned_to",
            linkId = "synthetic-task-person-link", confirmedBy = "owner", now = "2026-11-03T10:02:00Z")
        assertEquals("assigned_to", linked.relation)
        assertEquals(linked, cases.confirmTaskPersonLink(scope, "synthetic-task", "synthetic-person", "assigned_to",
            linkId = "another-command-idempotent", confirmedBy = "owner", now = "2026-11-03T10:03:00Z"))
        assertEquals(listOf(linked), db.protectedStoreDao().activeTaskPersonLinks(scope, "synthetic-person"))
        assertEquals(listOf("linked"), db.protectedStoreDao().taskPersonLinkRevisions(scope, linked.linkId).map { it.action })

        cases.unlinkTaskPerson(scope, linked.linkId, confirmedBy = "owner", now = "2026-11-03T10:04:00Z")
        assertTrue(db.protectedStoreDao().activeTaskPersonLinks(scope, "synthetic-person").isEmpty())
        val restored = cases.confirmTaskPersonLink(scope, "synthetic-task", "synthetic-person", "related_to",
            confirmedBy = "owner", now = "2026-11-03T10:05:00Z")
        assertEquals(linked.linkId, restored.linkId)
        assertEquals(3L, restored.version)
        assertEquals(listOf("linked", "retired", "restored"),
            db.protectedStoreDao().taskPersonLinkRevisions(scope, linked.linkId).map { it.action })
        assertEquals("related_to", db.protectedStoreDao().activeTaskPersonLinks(scope, "synthetic-person").single().relation)
    }
}

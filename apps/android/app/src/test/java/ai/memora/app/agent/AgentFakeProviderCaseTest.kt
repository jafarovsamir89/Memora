package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderCaseTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun sameNameSuggestionsStayUnlinkedUntilOwnerConfirmsAndLinkCanBeReversed() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000103"
        val domain = ProtectedDomainRepository(db)
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 10_000, "AZN", "synthetic source", null,
            "finance-elchin", "receipt-elchin", "command-elchin", "2026-11-03T10:00:00Z", "2026-11-03T10:00:00Z")
        domain.createObject(scope, "domain-elchin-a", DomainKind.PERSON, "Synthetic Elchin", "First synthetic profile", null, "2026-11-03T10:00:00Z")
        domain.createObject(scope, "domain-elchin-b", DomainKind.PERSON, "Synthetic Elchin", "Second synthetic profile", null, "2026-11-03T10:01:00Z")
        val cases = ProtectedCaseRepository(db)

        val choices = requireNotNull(cases.financePersonIdentityChoices(scope, "finance-elchin"))
        assertEquals(2, choices.candidates.size)
        assertNull(choices.confirmedLink)
        val denied = runCatching {
            cases.confirmFinancePersonIdentity(scope, "finance-elchin", "domain-elchin-a", confirmedBy = "model")
        }.exceptionOrNull()
        assertEquals("identity_link_requires_owner", denied?.message)
        assertNull(cases.financePersonIdentityChoices(scope, "finance-elchin")?.confirmedLink)

        val link = cases.confirmFinancePersonIdentity(scope, "finance-elchin", "domain-elchin-a", linkId = "synthetic-link",
            confirmedBy = "owner", now = "2026-11-03T10:02:00Z")
        assertEquals("owner", link.verifiedBy)
        assertEquals("domain-elchin-a", cases.financePersonIdentityChoices(scope, "finance-elchin")?.confirmedLink?.domainPersonId)
        val conflictingLink = runCatching {
            cases.confirmFinancePersonIdentity(scope, "finance-elchin", "domain-elchin-b", confirmedBy = "owner",
                now = "2026-11-03T10:03:00Z")
        }.exceptionOrNull()
        assertEquals("identity_link_conflict", conflictingLink?.message)

        cases.unlinkFinancePersonIdentity(scope, link.linkId, confirmedBy = "owner", now = "2026-11-03T10:04:00Z")
        assertNull(cases.financePersonIdentityChoices(scope, "finance-elchin")?.confirmedLink)
        val remapped = cases.confirmFinancePersonIdentity(scope, "finance-elchin", "domain-elchin-b", linkId = "synthetic-link-2",
            confirmedBy = "owner", now = "2026-11-03T10:05:00Z")
        assertEquals("domain-elchin-b", remapped.domainPersonId)
        assertEquals(1, db.protectedStoreDao().moneyEvents(scope).size)
    }

    @Test fun fakeProviderCreatesCaseAndLinksCanonicalFinanceSourceWithoutCopyingIt() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000104"
        val domain = ProtectedDomainRepository(db)
        domain.createObject(scope, "synthetic-person-object", DomainKind.PERSON,
            "Synthetic Elchin", "Fictional person for case integration test", null, "2026-11-03T09:00:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 10_000, "AZN", "synthetic source", null,
            "synthetic-finance-person", "synthetic-receipt", "synthetic-receipt-command",
            "2026-11-03T09:10:00Z", "2026-11-03T09:10:00Z")

        val provider = ScriptedCaseProvider()
        val handlers = LocalMemoryTools(domain, ProtectedMemoryRepository(db), financeEnabled = true).handlers()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000101", scope, "00000000-0000-4000-8000-000000000102", null, "az-AZ",
                "Synthetic: track a fictional BMW repair case"))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Synthetic case stored.", result.answer)
        assertEquals(listOf("case_created", "case_participant_added", "case_source_linked", "cases_loaded"),
            result.toolResults.map { it.code })
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val caseId = provider.caseId
        assertNotNull(caseId)
        val snapshot = requireNotNull(ProtectedCaseRepository(db).caseSnapshot(scope, requireNotNull(caseId)))
        assertEquals("Synthetic BMW repair", snapshot.record.title)
        assertEquals(listOf("Synthetic Elchin"), snapshot.participants.map { it.title })
        assertEquals(1, snapshot.evidence.size)
        assertEquals("synthetic-receipt", snapshot.evidence.single().evidence.sourceId)
        assertTrue(snapshot.evidence.single().sourceAvailable)
        assertEquals(1, db.protectedStoreDao().moneyEvents(scope).size)
        assertEquals(1, snapshot.timeline.count { it.sourceKind == "money_event" })
        assertTrue(snapshot.timeline.any { it.sourceKind == "case_revision" })
    }

    @Test fun caseUpdatesAppendRevisionsRejectStaleWritesAndRespectScope() = runBlocking {
        val repository = ProtectedCaseRepository(databaseRule.database)
        val scope = "00000000-0000-4000-8000-000000000105"
        val otherScope = "00000000-0000-4000-8000-000000000106"
        val createdAt = "2026-11-03T11:00:00Z"
        val first = repository.createCase(scope, "synthetic-case-revision", "Synthetic supply", "Fictional order",
            nextAction = "Confirm delivery", sourceTurnId = "synthetic-turn-1", now = createdAt)
        assertEquals(first, repository.createCase(scope, first.caseId, first.title, first.summary,
            nextAction = first.nextAction, sourceTurnId = first.sourceTurnId, now = createdAt))
        assertEquals("case_idempotency_conflict", runCatching {
            repository.createCase(scope, first.caseId, "Different case", first.summary,
                nextAction = first.nextAction, sourceTurnId = first.sourceTurnId, now = createdAt)
        }.exceptionOrNull()?.message)

        val updated = repository.updateCase(scope, first.caseId, expectedVersion = 1, title = first.title,
            summary = "Fictional order confirmed", status = "waiting", nextAction = "Ask for delivery date",
            sourceTurnId = "synthetic-turn-2", now = "2026-11-03T11:10:00Z")
        assertEquals(2L, updated.version)
        assertEquals("waiting", updated.status)
        assertEquals(2, databaseRule.database.protectedStoreDao().caseRevisions(first.caseId).size)
        assertEquals("case_version_conflict", runCatching {
            repository.updateCase(scope, first.caseId, expectedVersion = 1, title = first.title,
                summary = first.summary, status = "open", nextAction = first.nextAction,
                sourceTurnId = "synthetic-stale-turn", now = "2026-11-03T11:11:00Z")
        }.exceptionOrNull()?.message)
        assertNull(repository.caseSnapshot(otherScope, first.caseId))
        assertTrue(repository.searchCases(otherScope, "Synthetic").isEmpty())
        assertEquals(2L, requireNotNull(repository.caseSnapshot(scope, first.caseId)).record.version)
    }

    private class ScriptedCaseProvider : AgentModelGateway {
        var call = 0
        var caseId: String? = null
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            return when (call++) {
                0 -> AgentModelReply.Calls(listOf(AgentModelToolCall("case-create", "create_case", 1,
                    mapOf("title" to "Synthetic BMW repair", "summary" to "Fictional repair for a test", "next_action" to "Wait for synthetic parts"))))
                1 -> {
                    caseId = request.previousToolResults.last { it.name == "create_case" }.modelData["case_id"] as String
                    AgentModelReply.Calls(listOf(AgentModelToolCall("case-person", "add_case_participant", 1,
                        mapOf("case_id" to caseId, "person_object_id" to "synthetic-person-object", "role" to "customer"))))
                }
                2 -> AgentModelReply.Calls(listOf(AgentModelToolCall("case-source", "link_case_source", 1,
                    mapOf("case_id" to caseId, "source_kind" to "money_event", "source_id" to "synthetic-receipt",
                        "relation" to "advance"))))
                3 -> AgentModelReply.Calls(listOf(AgentModelToolCall("case-search", "search_cases", 1,
                    mapOf("query" to "BMW"))))
                else -> AgentModelReply.Answer("Synthetic case stored.")
            }
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class MemoryJournal : AgentRunJournal {
        override suspend fun beginTurn(turnId: String) = Unit
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun recordCommand(command: AgentCommandJournalEntry) = Unit
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) = Unit
        override suspend fun interruptedTurns() = emptyList<AgentTurnJournalEntry>()
        override suspend fun command(commandId: String): AgentCommandJournalEntry? = null
        override suspend fun unresolvedCommands() = emptyList<AgentCommandJournalEntry>()
    }
}

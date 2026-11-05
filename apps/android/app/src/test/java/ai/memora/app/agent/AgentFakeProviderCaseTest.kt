package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.MemoryRevisionRow
import ai.memora.app.data.protectedstore.MemorySourceRow
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

    @Test fun fakeProviderCreatesCaseAndLinksCanonicalFinanceProjectAndMemorySourcesWithoutCopyingThem() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000104"
        val domain = ProtectedDomainRepository(db)
        domain.createObject(scope, "synthetic-person-object", DomainKind.PERSON,
            "Synthetic Elchin", "Fictional person for case integration test", null, "2026-11-03T09:00:00Z")
        domain.createObject(scope, "synthetic-bmw-project", DomainKind.PROJECT,
            "Synthetic BMW repair project", "Fictional project source", null, "2026-11-03T09:05:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 10_000, "AZN", "synthetic source", null,
            "synthetic-finance-person", "synthetic-receipt", "synthetic-receipt-command",
            "2026-11-03T09:10:00Z", "2026-11-03T09:10:00Z")
        val obligation = domain.recordFinanceEvent(scope, "Synthetic Elchin", "debt", 30_000, "AZN",
            "Synthetic agreed repair total", null, "synthetic-finance-person", "synthetic-bmw-obligation",
            "synthetic-bmw-obligation-command", "2026-11-03T09:09:00Z", "2026-11-03T09:09:00Z")
        val remaining = domain.recordSettlement(scope, "synthetic-bmw-settlement", "synthetic-bmw-settlement-command",
            obligation.eventId, 10_000, "AZN", "2026-11-03T09:10:00Z", "Synthetic advance payment", "synthetic-receipt")
        assertEquals(20_000L, remaining)
        ProtectedCaseRepository(db).confirmFinancePersonIdentity(scope, "synthetic-finance-person", "synthetic-person-object",
            confirmedBy = "owner", now = "2026-11-03T09:11:00Z")
        db.protectedStoreDao().putSources(listOf(MemorySourceRow("synthetic-memory-source", "synthetic-memory-command",
            null, "Asia/Baku", "voice", false, 1, scope)))
        db.protectedStoreDao().putRevisions(listOf(MemoryRevisionRow("synthetic-memory-source", "synthetic-memory-revision",
            1, "Fictional repair decision: wait for the blue replacement part.", "2026-11-03T09:15:00Z")))

        val provider = ScriptedCaseProvider()
        val handlers = LocalMemoryTools(domain, ProtectedMemoryRepository(db), financeEnabled = true).handlers()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000101", scope, "00000000-0000-4000-8000-000000000102", null, "az-AZ",
                "Synthetic: track a fictional BMW repair case"))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Synthetic case stored.", result.answer)
        assertEquals(listOf("case_created", "case_participant_added", "case_source_linked", "case_source_linked",
            "case_source_linked", "case_source_linked", "cases_loaded"),
            result.toolResults.map { it.code })
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        assertEquals(true, result.toolResults.single { it.name == "create_case" }.modelData["source_quote_verified"])
        val caseId = provider.caseId
        assertNotNull(caseId)
        val snapshot = requireNotNull(ProtectedCaseRepository(db).caseSnapshot(scope, requireNotNull(caseId)))
        assertEquals("Synthetic BMW repair", snapshot.record.title)
        assertEquals(listOf("Synthetic Elchin"), snapshot.participants.map { it.title })
        assertEquals(4, snapshot.evidence.size)
        val financeEvidence = snapshot.evidence.single { it.evidence.sourceId == "synthetic-receipt" }
        val obligationEvidence = snapshot.evidence.single { it.evidence.sourceId == "synthetic-bmw-obligation" }
        val projectEvidence = snapshot.evidence.single { it.evidence.sourceKind == "domain_object" }
        val memoryEvidence = snapshot.evidence.single { it.evidence.sourceKind == "memory_revision" }
        assertEquals("synthetic-receipt", financeEvidence.evidence.sourceId)
        assertEquals("synthetic-bmw-obligation", obligationEvidence.evidence.sourceId)
        assertEquals("synthetic-bmw-project", projectEvidence.evidence.sourceId)
        assertEquals("Synthetic BMW repair project", projectEvidence.title)
        assertEquals("synthetic-memory-source", memoryEvidence.evidence.sourceId)
        assertEquals("synthetic-memory-revision", memoryEvidence.evidence.sourceRevisionId)
        assertEquals("Fictional repair decision: wait for the blue replacement part.", memoryEvidence.detail)
        assertTrue(snapshot.evidence.all { it.sourceAvailable })
        assertEquals(2, db.protectedStoreDao().moneyEvents(scope).size)
        assertEquals(1, db.protectedStoreDao().activeDomainObjects(scope, DomainKind.PROJECT.name, 20).size)
        assertEquals(1, db.protectedStoreDao().sources().count { it.sourceId == "synthetic-memory-source" })
        assertEquals(1, db.protectedStoreDao().revisions().count { it.sourceId == "synthetic-memory-source" })
        assertEquals(2, snapshot.timeline.count { it.sourceKind == "money_event" })
        assertEquals(1, snapshot.timeline.count { it.sourceKind == "domain_object" })
        assertEquals(1, snapshot.timeline.count { it.sourceKind == "memory_revision" })
        assertEquals(20_000L, domain.outstandingDebtMinor(scope, "synthetic-bmw-obligation"))
        val confirmedIdentity = requireNotNull(ProtectedCaseRepository(db).financePersonIdentityChoices(scope, "synthetic-finance-person"))
            .confirmedLink
        assertEquals("synthetic-person-object", confirmedIdentity?.domainPersonId)
        assertTrue(snapshot.timeline.any { it.sourceKind == "case_revision" })
        assertEquals("track a fictional BMW repair case",
            snapshot.timeline.single { it.sourceKind == "case_revision" }.sourceExcerpt)
    }

    @Test fun caseUpdatesAppendRevisionsRejectStaleWritesAndRespectScope() = runBlocking {
        val repository = ProtectedCaseRepository(databaseRule.database)
        val scope = "00000000-0000-4000-8000-000000000105"
        val otherScope = "00000000-0000-4000-8000-000000000106"
        val createdAt = "2026-11-03T11:00:00Z"
        val firstSource = "🙂 Synthetic: confirm delivery of the blue replacement part."
        val first = repository.createCase(scope, "synthetic-case-revision", "Synthetic supply", "Fictional order",
            nextAction = "Confirm delivery", sourceTurnId = "synthetic-turn-1", now = createdAt,
            sourceText = firstSource, sourceQuote = "confirm delivery of the blue replacement part")
        val firstRevision = databaseRule.database.protectedStoreDao().caseRevisions(first.caseId).single()
        assertEquals(firstSource.indexOf("confirm delivery"), firstRevision.sourceStartUtf16)
        assertEquals(firstSource.indexOf("confirm delivery") + "confirm delivery of the blue replacement part".length,
            firstRevision.sourceEndUtf16)
        assertEquals(first, repository.createCase(scope, first.caseId, first.title, first.summary,
            nextAction = first.nextAction, sourceTurnId = first.sourceTurnId, now = createdAt))
        assertEquals("case_idempotency_conflict", runCatching {
            repository.createCase(scope, first.caseId, "Different case", first.summary,
                nextAction = first.nextAction, sourceTurnId = first.sourceTurnId, now = createdAt)
        }.exceptionOrNull()?.message)

        val updated = repository.updateCase(scope, first.caseId, expectedVersion = 1, title = first.title,
            summary = "Fictional order confirmed", status = "waiting", nextAction = "Ask for delivery date",
            sourceTurnId = "synthetic-turn-2", now = "2026-11-03T11:10:00Z",
            sourceText = "Synthetic correction: the blue part will arrive Friday.",
            sourceQuote = "the blue part will arrive Friday")
        assertEquals(2L, updated.version)
        assertEquals("waiting", updated.status)
        val snapshot = requireNotNull(repository.caseSnapshot(scope, first.caseId))
        assertEquals("the blue part will arrive Friday",
            snapshot.timeline.single { it.sourceRevisionId == "synthetic-turn-2" }.sourceExcerpt)
        assertEquals(2, databaseRule.database.protectedStoreDao().caseRevisions(first.caseId).size)
        assertEquals("case_version_conflict", runCatching {
            repository.updateCase(scope, first.caseId, expectedVersion = 1, title = first.title,
                summary = first.summary, status = "open", nextAction = first.nextAction,
                sourceTurnId = "synthetic-stale-turn", now = "2026-11-03T11:11:00Z")
        }.exceptionOrNull()?.message)
        val unmatched = repository.createCase(scope, "synthetic-case-unmatched-quote", "Synthetic unanchored case", null,
            sourceTurnId = "synthetic-turn-unmatched", now = "2026-11-03T11:12:00Z",
            sourceText = "Synthetic current message", sourceQuote = "a quote that was never said")
        val unmatchedRevision = databaseRule.database.protectedStoreDao().caseRevisions(unmatched.caseId).single()
        assertNull(unmatchedRevision.sourceStartUtf16)
        assertNull(unmatchedRevision.sourceEndUtf16)
        assertNull(requireNotNull(repository.caseSnapshot(scope, unmatched.caseId)).timeline.single().sourceExcerpt)
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
                    mapOf("title" to "Synthetic BMW repair", "summary" to "Fictional repair for a test",
                        "next_action" to "Wait for synthetic parts", "source_quote" to "track a fictional BMW repair case"))))
                1 -> {
                    caseId = request.previousToolResults.last { it.name == "create_case" }.modelData["case_id"] as String
                    AgentModelReply.Calls(listOf(AgentModelToolCall("case-person", "add_case_participant", 1,
                        mapOf("case_id" to caseId, "person_object_id" to "synthetic-person-object", "role" to "customer"))))
                }
                2 -> AgentModelReply.Calls(listOf(
                    AgentModelToolCall("case-finance-source", "link_case_source", 1,
                        mapOf("case_id" to caseId, "source_kind" to "money_event", "source_id" to "synthetic-receipt",
                            "relation" to "advance")),
                    AgentModelToolCall("case-project-source", "link_case_source", 1,
                        mapOf("case_id" to caseId, "source_kind" to "domain_object", "source_id" to "synthetic-bmw-project",
                            "relation" to "repair_project")),
                    AgentModelToolCall("case-obligation-source", "link_case_source", 1,
                        mapOf("case_id" to caseId, "source_kind" to "money_event", "source_id" to "synthetic-bmw-obligation",
                            "relation" to "agreed_repair_total")),
                    AgentModelToolCall("case-memory-source", "link_case_source", 1,
                        mapOf("case_id" to caseId, "source_kind" to "memory_revision", "source_id" to "synthetic-memory-source",
                            "source_revision_id" to "synthetic-memory-revision", "relation" to "repair_decision")),
                ))
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

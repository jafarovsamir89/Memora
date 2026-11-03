package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves an alternate model adapter reaches the same protected finance tool and exact ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderFinanceTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun alternateProviderWritesExactAmountThroughExistingFinanceHandler() = runBlocking {
        val database = databaseRule.database
        val scopeId = "00000000-0000-4000-8000-000000000003"
        val handler = LocalMemoryTools(
            ProtectedDomainRepository(database), ProtectedMemoryRepository(database), financeEnabled = true,
        ).handlers().single { it.descriptor.name == "record_money_event" }
        val provider = FakeFinanceProvider()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(listOf(handler)), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest(
                turnId = "00000000-0000-4000-8000-000000000001",
                scopeId = scopeId,
                actorId = "00000000-0000-4000-8000-000000000004",
                sourceRevisionId = null,
                locale = "az-AZ",
                text = "Synthetic: a fictional customer paid 100.25 AZN",
            ))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(1, result.toolResults.size)
        assertEquals(ToolObservationStatus.APPLIED, result.toolResults.single().status)
        val evidence = ProtectedDomainRepository(database).financeHistory(scopeId, "Synthetic Customer")
        assertNotNull(evidence)
        assertEquals(1, evidence!!.events.size)
        assertEquals(10_025L, evidence.events.single().minorUnits)
        assertEquals("AZN", evidence.events.single().currency)
        assertEquals("receipt", evidence.events.single().kind)
        assertEquals("Synthetic: a fictional customer paid 100.25 AZN", evidence.events.single().source)
        assertEquals("receipt_recorded", result.toolResults.single().code)
        assertEquals(10_025L, result.toolResults.single().modelData["minor_units"])
        assertEquals(2, provider.turnCount)
    }

    @Test fun exactAliasWritesToConfirmedFinanceIdentityAndRefusesUnlinkedAlias() = runBlocking {
        val db = databaseRule.database
        val scope = "synthetic-alias-finance-scope"
        val domain = ProtectedDomainRepository(db)
        val aliasRepository = PersonAliasRepository(db)
        domain.createObject(scope, "synthetic-alias-person", DomainKind.PERSON, "Canonical Person", null, null,
            "2026-11-03T10:00:00Z")
        domain.recordFinanceEvent(scope, "Ledger Canonical", "receipt", 5_000L, "AZN", "synthetic seed", null,
            "synthetic-finance-person", "synthetic-seed-event", "synthetic-seed-command",
            "2026-11-03T10:01:00Z", "2026-11-03T10:01:00Z")
        aliasRepository.add(scope, "synthetic-alias-person", "Samir Usta", nowMillis = 10)
        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = aliasRepository).handlers()
            .single { it.descriptor.name == "record_money_event" }
        val turn = AgentTurnRequest("synthetic-alias-turn", scope, "synthetic-owner", null, "az-AZ",
            "Synthetic: Samir Usta paid 25 manat", AgentInputChannel.TEXT)
        val call = AgentModelToolCall("synthetic-alias-call", "record_money_event", 1, mapOf(
            "kind" to "receipt", "person_name" to "Samir Usta", "amount" to "25", "currency" to "AZN",
        ))

        val unlinked = handler.prepare(call, turn, "synthetic-alias-command-unlinked")
        assertTrue(unlinked is ToolPreparation.Conflict)
        assertEquals("person_alias_finance_identity_unconfirmed", (unlinked as ToolPreparation.Conflict).code)
        assertEquals(1, domain.financePeople(scope).size)

        ProtectedCaseRepository(db).confirmFinancePersonIdentity(scope, "synthetic-finance-person",
            "synthetic-alias-person", confirmedBy = "owner", now = "2026-11-03T10:02:00Z")
        val prepared = handler.prepare(call, turn, "synthetic-alias-command-linked")
        assertTrue(prepared is ToolPreparation.Prepared)
        val result = handler.commit((prepared as ToolPreparation.Prepared).command)
        assertEquals(ToolObservationStatus.APPLIED, result.status)
        assertEquals("Ledger Canonical", result.modelData["person_name"])
        assertEquals(1, domain.financePeople(scope).size)
        assertEquals(2, domain.allFinanceEvents(scope).size)

        val search = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = aliasRepository).handlers()
            .single { it.descriptor.name == "search_money" }
        val found = search.read(AgentModelToolCall("synthetic-alias-search", "search_money", 1,
            mapOf("person_name" to "Samir Usta", "query" to "", "kind" to "")), turn)
        assertEquals(ToolObservationStatus.APPLIED, found.status)
        assertEquals(true, found.modelData["person_found"])
        assertEquals(2, (found.modelData["events"] as List<*>).size)
    }

    @Test fun alternateProviderReadsExactMonthlyFinanceFromProtectedLedgerWithoutWriting() = runBlocking {
        val database = databaseRule.database
        val scopeId = "00000000-0000-4000-8000-000000000013"
        val repository = ProtectedDomainRepository(database)
        val occurredAt = "2026-11-03T08:00:00Z"
        repository.recordFinanceEvent(scopeId, "Synthetic Counterparty", "receipt", 10_025, "AZN",
            "Synthetic report fixture", null, "synthetic-person", "synthetic-receipt", "synthetic-receipt-command", occurredAt, occurredAt)
        repository.recordFinanceEvent(scopeId, "Synthetic Counterparty", "expense", 15_000, "AZN",
            "Synthetic report fixture", null, "synthetic-person", "synthetic-expense", "synthetic-expense-command", occurredAt, occurredAt)
        repository.recordFinanceEvent(scopeId, "Synthetic Counterparty", "debt", 35_000, "AZN",
            "Synthetic report fixture", null, "synthetic-person", "synthetic-debt", "synthetic-debt-command", occurredAt, occurredAt)
        val handler = LocalMemoryTools(repository, ProtectedMemoryRepository(database), financeEnabled = true)
            .handlers().single { it.descriptor.name == "analyze_finance_period" }
        val provider = FakeFinanceAnalyticsProvider()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(listOf(handler)), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest(
                turnId = "00000000-0000-4000-8000-000000000011",
                scopeId = scopeId,
                actorId = "00000000-0000-4000-8000-000000000014",
                sourceRevisionId = null,
                locale = "az-AZ",
                text = "Synthetic: analyze 2026-11 ledger",
            ))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(1, result.toolResults.size)
        val observation = result.toolResults.single()
        assertEquals("analyze_finance_period", observation.name)
        assertEquals(ToolObservationStatus.APPLIED, observation.status)
        assertEquals("finance_report_ready", observation.code)
        val period = observation.modelData["period"] as Map<*, *>
        val totals = (period["currencies"] as List<*>).single() as Map<*, *>
        assertEquals(10_025L, totals["received_minor_units"])
        assertEquals(15_000L, totals["spent_minor_units"])
        assertEquals(35_000L, totals["debt_originated_minor_units_not_income"])
        assertEquals(-4_975L, totals["recorded_cash_movement_minor_units_not_profit"])
        assertEquals(35_000L, (observation.modelData["outstanding_debt_as_of_query"] as List<*>)
            .single().let { (it as Map<*, *>)["minor_units"] })
        val coverage = observation.modelData["coverage"] as Map<*, *>
        assertEquals(false, coverage["profit_calculable"])
        assertEquals(false, (observation.modelData["project_analysis"] as Map<*, *>)["available"])
        assertEquals(3, repository.allFinanceEvents(scopeId).size)
        assertEquals(2, provider.turnCount)
    }

    private class FakeFinanceProvider : AgentModelGateway {
        private val replies = ArrayDeque(listOf(
            AgentModelReply.Calls(listOf(AgentModelToolCall("fake-finance-call", "record_money_event", 1, mapOf(
                "kind" to "receipt", "person_name" to "Synthetic Customer", "amount" to "100.25",
            )))),
            AgentModelReply.Answer("Synthetic receipt saved."),
        ))
        var turnCount = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach { AgentToolCatalog.require(it) }
            turnCount++
            return replies.removeFirst()
        }
    }

    private class FakeFinanceAnalyticsProvider : AgentModelGateway {
        private val replies = ArrayDeque(listOf(
            AgentModelReply.Calls(listOf(AgentModelToolCall("fake-finance-report-call", "analyze_finance_period", 1,
                mapOf("month" to "2026-11")))),
            AgentModelReply.Answer("Synthetic ledger analysis is available."),
        ))
        var turnCount = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            turnCount++
            return replies.removeFirst()
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class MemoryJournal : AgentRunJournal {
        private val commands = mutableMapOf<String, AgentCommandJournalEntry>()
        override suspend fun beginTurn(turnId: String) = Unit
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun recordCommand(command: AgentCommandJournalEntry) { commands[command.commandId] = command }
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) {
            commands[commandId]?.let { commands[commandId] = it.copy(status = status) }
        }
        override suspend fun interruptedTurns() = emptyList<AgentTurnJournalEntry>()
        override suspend fun command(commandId: String) = commands[commandId]
        override suspend fun unresolvedCommands() = emptyList<AgentCommandJournalEntry>()
    }
}

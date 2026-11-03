package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

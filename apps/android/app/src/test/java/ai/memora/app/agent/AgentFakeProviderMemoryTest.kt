package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers consented local gift-context retrieval with synthetic people and scope isolation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderMemoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun fakeProviderUsesScopedMemoryEvidenceForGiftAdviceWithoutWriting() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000133"
        val otherScope = "00000000-0000-4000-8000-000000000134"
        val domain = ProtectedDomainRepository(db)
        domain.createObject(
            scope, "synthetic-person-ruslan", DomainKind.PERSON, "Synthetic Ruslan",
            "Fictional preference: enjoys astronomy and handmade notebooks. Synthetic occasion: birthday in December.",
            null, "2026-11-03T09:00:00Z",
        )
        domain.createObject(
            otherScope, "foreign-person-ruslan", DomainKind.PERSON, "Synthetic Ruslan",
            "Foreign scope marker that must not be returned.",
            null, "2026-11-03T09:01:00Z",
        )
        val rowsBefore = domain.domainObjects(scope, DomainKind.PERSON)
        val provider = GiftContextProvider()
        val memorySearch = LocalMemoryTools(domain, ProtectedMemoryRepository(db)).handlers()
            .filter { it.descriptor.name == "search_memory" }
        val result = AgentOrchestrator(
            provider, DefaultAgentToolRegistry(memorySearch), AllowPolicy(), MemoryJournal(),
        ).run(AgentTurnRequest(
            "00000000-0000-4000-8000-000000000131",
            scope,
            "00000000-0000-4000-8000-000000000132",
            null,
            "az-AZ",
            "Synthetic: find a useful gift idea for Synthetic Ruslan's birthday.",
        ))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Synthetic suggestion grounded in the saved preference.", result.answer)
        assertEquals(listOf("evidence_found"), result.toolResults.map { it.code })
        assertEquals(ToolObservationStatus.APPLIED, result.toolResults.single().status)
        val evidence = result.toolResults.single().modelData["evidence"] as List<*>
        val person = evidence.single() as Map<*, *>
        assertEquals("synthetic-person-ruslan", person["id"])
        assertEquals("person", person["kind"])
        assertEquals("Synthetic Ruslan", person["title"])
        assertTrue((person["text"] as String).contains("astronomy"))
        assertEquals("synthetic-person-ruslan:1", person["revision"])
        assertFalse((person["text"] as String).contains("Foreign scope marker"))
        assertEquals(rowsBefore, domain.domainObjects(scope, DomainKind.PERSON))
        assertEquals(1, domain.domainObjects(otherScope, DomainKind.PERSON).size)
        assertEquals(2, provider.turnCount)
    }

    private class GiftContextProvider : AgentModelGateway {
        var turnCount = 0

        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            return when (turnCount++) {
                0 -> AgentModelReply.Calls(listOf(AgentModelToolCall(
                    "synthetic-gift-memory-search", "search_memory", 1,
                    mapOf("query" to "Synthetic Ruslan birthday gift preference"),
                )))
                else -> {
                    val observation = request.previousToolResults.single()
                    val evidence = observation.modelData["evidence"] as List<*>
                    check(observation.status == ToolObservationStatus.APPLIED && evidence.isNotEmpty())
                    AgentModelReply.Answer("Synthetic suggestion grounded in the saved preference.")
                }
            }
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) =
            PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) =
            PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) =
            PolicyDecision(PolicyAction.ALLOW, "allowed")
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

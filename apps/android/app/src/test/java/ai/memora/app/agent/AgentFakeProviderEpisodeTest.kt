package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderEpisodeTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun fakeProviderFindsVerifiesAndPersistsEpisodeWithExactUserTurnProvenance() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000261"
        val foreignScope = "00000000-0000-4000-8000-000000000262"
        val sourceId = "synthetic-turn-business-001"
        val repository = ProtectedConversationRepository(db)
        repository.appendTurn(scope, sourceId,
            "Synthetic: we agreed to request the BMW repair invoice from Elchin after the 100 manat Friday payment.",
            "Synthetic assistant follow-up, not a source of personal facts.", 1_800_000_000_000L)
        repository.appendTurn(foreignScope, "synthetic-turn-foreign-002", "Synthetic foreign marker.", "Not available.",
            1_800_000_000_001L)

        val provider = EpisodeWritingProvider()
        val handlers = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db), conversations = repository).handlers()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowMemoryPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000241", scope,
                "00000000-0000-4000-8000-000000000242", null, "az-AZ", "Synthetic: recall and preserve the open BMW invoice follow-up."))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(listOf("conversation_evidence_loaded", "conversation_source_loaded", "episode_saved"),
            result.toolResults.map { it.code })
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val episode = repository.searchEpisodes(scope, "BMW invoice Elchin", limit = 5).single()
        assertEquals(listOf(sourceId), episode.sourceTurnIds)
        assertEquals(1L, episode.revision)
        assertTrue(repository.searchEpisodes(foreignScope, "BMW invoice Elchin", limit = 5).isEmpty())

        val searchMemory = handlers.single { it.descriptor.name == "search_memory" }
        val memoryResult = searchMemory.read(AgentModelToolCall("episode-readback", "search_memory", 1,
            mapOf("query" to "BMW invoice Elchin")), AgentTurnRequest("00000000-0000-4000-8000-000000000243",
            scope, "00000000-0000-4000-8000-000000000242", null, "az-AZ", "Synthetic readback"))
        val episodeResults = memoryResult.modelData["episodes"] as List<*>
        assertEquals(episode.episodeId, (episodeResults.single() as Map<*, *>)["id"])
        assertTrue((episodeResults.single() as Map<*, *>)["derived_not_canonical"] as Boolean)

        val deleteHandler = handlers.single { it.descriptor.name == "delete_memory_episode" }
        val deleteTurn = AgentTurnRequest("00000000-0000-4000-8000-000000000244", scope,
            "00000000-0000-4000-8000-000000000242", null, "az-AZ", "Synthetic delete request")
        val deletion = deleteHandler.prepare(AgentModelToolCall("episode-delete", "delete_memory_episode", 1,
            mapOf("episode_id" to episode.episodeId)), deleteTurn, "00000000-0000-4000-8000-000000000245")
        assertTrue(deletion is ToolPreparation.Prepared)
        val deleteResult = deleteHandler.commit((deletion as ToolPreparation.Prepared).command)
        assertEquals(ToolObservationStatus.APPLIED, deleteResult.status)
        assertTrue(repository.searchEpisodes(scope, "BMW invoice Elchin", limit = 5).isEmpty())
        assertNotNull(repository.turnEvidence(scope, sourceId))

        repository.saveEpisode(scope, episode.title, episode.summary, episode.sourceTurnIds, nowMillis = 1_800_000_000_010L)
        assertTrue(repository.deleteTurn(scope, sourceId))
        assertTrue(repository.searchEpisodes(scope, "BMW invoice Elchin", limit = 5).isEmpty())
        assertFalse(repository.episodeSourcesAvailable(scope, listOf(sourceId)))
    }

    @Test fun revokedMemoryConsentBlocksEpisodeToolBeforeCommit() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000263"
        val repository = ProtectedConversationRepository(db)
        val provider = ConsentProbeProvider()
        val handlers = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db), conversations = repository).handlers()
        val policy = ConsentAgentRuntimePolicy(cloudConsent = { true }, memoryConsent = { false })
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), policy, MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000251", scope,
                "00000000-0000-4000-8000-000000000252", null, "az-AZ", "Synthetic request"))

        assertEquals(1, provider.rounds)
        assertTrue(result.toolResults.any { it.name == "save_memory_episode" && it.code == "memory_consent_required" })
        assertTrue(repository.searchEpisodes(scope, "private synthetic summary", limit = 5).isEmpty())
    }

    private class EpisodeWritingProvider : AgentModelGateway {
        private var round = 0
        private var sourceTurnId = ""
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            return when (round++) {
                0 -> AgentModelReply.Calls(listOf(AgentModelToolCall("episode-search", "search_conversation_history", 1,
                    mapOf("query" to "Synthetic BMW invoice Elchin"))))
                1 -> {
                    val result = request.previousToolResults.single { it.name == "search_conversation_history" }
                    val evidence = result.modelData["evidence"] as List<*>
                    sourceTurnId = (evidence.asSequence().map { it as Map<*, *> }
                        .first { it["speaker"] == "user" }["turn_id"] as String)
                    AgentModelReply.Calls(listOf(AgentModelToolCall("episode-source", "read_conversation_turn", 1,
                        mapOf("turn_id" to sourceTurnId))))
                }
                2 -> {
                    val source = request.previousToolResults.single { it.name == "read_conversation_turn" }
                    check(source.status == ToolObservationStatus.APPLIED)
                    check((source.modelData["user_message"] as String).contains("100 manat"))
                    AgentModelReply.Calls(listOf(AgentModelToolCall("episode-save", "save_memory_episode", 2, mapOf(
                        "title" to "Synthetic BMW repair follow-up",
                        "summary" to "After the synthetic Friday payment, request the repair invoice from Elchin.",
                        "source_turn_ids" to listOf(sourceTurnId),
                    ))))
                }
                else -> AgentModelReply.Answer("Synthetic episode stored with its source turn.")
            }
        }
    }

    private class ConsentProbeProvider : AgentModelGateway {
        var rounds = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            rounds++
            return AgentModelReply.Calls(listOf(AgentModelToolCall("revoked-episode-write", "save_memory_episode", 2, mapOf(
                "title" to "Synthetic private memory", "summary" to "private synthetic summary", "source_turn_ids" to emptyList<String>()))))
        }
    }

    private class AllowMemoryPolicy : AgentRuntimePolicy {
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

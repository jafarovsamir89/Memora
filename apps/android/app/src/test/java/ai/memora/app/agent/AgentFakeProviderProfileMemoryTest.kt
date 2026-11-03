package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderProfileMemoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun explicitProfileFactRoutesThroughToolWithExactSourceAndVersionedCorrection() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000801"
        val actor = "00000000-0000-4000-8000-000000000802"
        val sourceTurn = "00000000-0000-4000-8000-000000000803"
        val conversations = ProtectedConversationRepository(db)
        val profiles = ProtectedProfileMemoryRepository(db)
        val handlers = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db),
            conversations = conversations, profileMemory = profiles).handlers()
        val provider = ProfileFactProvider()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest(sourceTurn, scope, actor, null,
                "az-AZ", "Synthetic statement: I prefer brief answers in Azerbaijani."))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(listOf("profile_fact_saved"), result.toolResults.map { it.code })
        assertEquals(ToolObservationStatus.APPLIED, result.toolResults.single().status)
        assertEquals(2, provider.rounds)
        val stored = profiles.list(scope, "brief Azerbaijani", limit = 5).single()
        assertEquals("preference", stored.category)
        assertEquals("I prefer brief answers in Azerbaijani", stored.fact)
        assertEquals(listOf(sourceTurn), stored.sourceTurnIds)
        assertEquals("user_stated", stored.revisions.single().provenance)
        assertEquals("Synthetic statement: I prefer brief answers in Azerbaijani.",
            conversations.sourceUserTurn(scope, sourceTurn)?.message)

        val search = handlers.single { it.descriptor.name == "search_memory" }.read(
            AgentModelToolCall("profile-search", "search_memory", 1, mapOf("query" to "brief Azerbaijani")),
            AgentTurnRequest("00000000-0000-4000-8000-000000000804", scope, actor, null, "az-AZ", "Synthetic search"))
        @Suppress("UNCHECKED_CAST")
        val returnedFacts = search.modelData["profile_facts"] as List<Map<String, Any?>>
        assertEquals(stored.factId, returnedFacts.single()["id"])
        assertEquals("user_stated", returnedFacts.single()["provenance"])

        val editHandler = handlers.single { it.descriptor.name == "edit_profile_fact" }
        val correctionTurn = AgentTurnRequest("00000000-0000-4000-8000-000000000805", scope, actor, null,
            "az-AZ", "Synthetic correction: detailed answers are better for me.")
        val prepared = editHandler.prepare(AgentModelToolCall("profile-edit", "edit_profile_fact", 1,
            mapOf("fact_id" to stored.factId, "expected_revision" to stored.revision,
                "fact" to "I prefer detailed answers")), correctionTurn, "00000000-0000-4000-8000-000000000806")
        assertTrue(prepared is ToolPreparation.Prepared)
        val edited = editHandler.commit((prepared as ToolPreparation.Prepared).command)
        assertEquals(ToolObservationStatus.APPLIED, edited.status)
        val updated = requireNotNull(profiles.get(scope, stored.factId))
        assertEquals(2L, updated.revision)
        assertEquals("I prefer detailed answers", updated.fact)
        assertEquals(correctionTurn.turnId, updated.revisions.last().sourceTurnId)

        val stale = editHandler.prepare(AgentModelToolCall("profile-stale-edit", "edit_profile_fact", 1,
            mapOf("fact_id" to stored.factId, "expected_revision" to 1L, "fact" to "Stale value")),
            AgentTurnRequest("00000000-0000-4000-8000-000000000807", scope, actor, null,
                "az-AZ", "Synthetic stale edit"), "00000000-0000-4000-8000-000000000808")
        assertTrue(stale is ToolPreparation.Conflict)
        assertFalse(profiles.get(scope, stored.factId)!!.fact == "Stale value")

        val deleteHandler = handlers.single { it.descriptor.name == "delete_profile_fact" }
        val deletion = deleteHandler.prepare(AgentModelToolCall("profile-delete", "delete_profile_fact", 1,
            mapOf("fact_id" to stored.factId, "expected_revision" to 2L)),
            AgentTurnRequest("00000000-0000-4000-8000-000000000809", scope, actor, null,
                "az-AZ", "Synthetic forget request"), "00000000-0000-4000-8000-000000000810")
        assertTrue(deletion is ToolPreparation.Prepared)
        assertEquals(ToolObservationStatus.APPLIED, deleteHandler.commit((deletion as ToolPreparation.Prepared).command).status)
        assertTrue(profiles.list(scope).isEmpty())
        assertEquals("Synthetic statement: I prefer brief answers in Azerbaijani.",
            conversations.sourceUserTurn(scope, sourceTurn)?.message)
    }

    @Test fun revokedMemoryConsentBlocksProfileWriteBeforeItCreatesASourceOrFact() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000811"
        val profiles = ProtectedProfileMemoryRepository(db)
        val conversations = ProtectedConversationRepository(db)
        val handlers = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db),
            conversations = conversations, profileMemory = profiles).handlers()
        val provider = RevokedProfileProvider()
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers),
            ConsentAgentRuntimePolicy(cloudConsent = { true }, memoryConsent = { false }), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000812", scope,
                "00000000-0000-4000-8000-000000000813", null,
                "az-AZ", "Synthetic explicit profile statement."))

        assertEquals(1, provider.rounds)
        assertTrue(result.toolResults.any { it.name == "save_profile_fact" && it.code == "memory_consent_required" })
        assertTrue(profiles.list(scope).isEmpty())
        assertTrue(conversations.turn(scope, "00000000-0000-4000-8000-000000000812").isEmpty())
    }

    @Test fun failedProfileDeletionNeverReceivesFalseSuccessReplyInAzRuOrEn() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000821"
        val actor = "00000000-0000-4000-8000-000000000822"
        val conversations = ProtectedConversationRepository(db)
        val handlers = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db),
            conversations = conversations, profileMemory = ProtectedProfileMemoryRepository(db)).handlers()
        val expectedMessages = mapOf(
            "az-AZ" to "Silinmə təsdiqlənməyib",
            "ru-RU" to "Удаление не подтверждено",
            "en-US" to "Deletion isn't confirmed",
        )
        expectedMessages.forEach { (locale, expectedMessage) ->
            val provider = FailedProfileDeletionProvider()
            val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowPolicy(), MemoryJournal())
                .run(AgentTurnRequest(provider.turnId, scope, actor, null, locale, "Synthetic deletion request"))

            assertEquals(AgentRunResultStatus.PARTIAL, result.status)
            assertEquals("tool_write_failed", result.code)
            assertEquals("profile_fact_not_found", result.toolResults.single().code)
            assertTrue(result.answer.orEmpty().contains(expectedMessage))
            assertFalse(result.answer.orEmpty().contains("Synthetic item deleted"))
        }
    }

    private class ProfileFactProvider : AgentModelGateway {
        var rounds = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            return when (rounds++) {
                0 -> AgentModelReply.Calls(listOf(AgentModelToolCall("profile-save", "save_profile_fact", 1,
                    mapOf("category" to "preference", "fact" to "I prefer brief answers in Azerbaijani"))))
                else -> AgentModelReply.Answer("Synthetic preference saved with its source.")
            }
        }
    }

    private class RevokedProfileProvider : AgentModelGateway {
        var rounds = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            rounds++
            return AgentModelReply.Calls(listOf(AgentModelToolCall("revoked-profile-save", "save_profile_fact", 1,
                mapOf("category" to "preference", "fact" to "Synthetic private preference"))))
        }
    }

    private class FailedProfileDeletionProvider : AgentModelGateway {
        val turnId = java.util.UUID.randomUUID().toString()
        private var rounds = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply = when (rounds++) {
            0 -> AgentModelReply.Calls(listOf(AgentModelToolCall("missing-profile-delete", "delete_profile_fact", 1,
                mapOf("fact_id" to "00000000-0000-4000-8000-000000000823", "expected_revision" to 1L))))
            else -> AgentModelReply.Answer("Synthetic item deleted.")
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

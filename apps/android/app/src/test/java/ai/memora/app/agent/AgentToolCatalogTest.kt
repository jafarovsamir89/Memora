package ai.memora.app.agent

import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test fun everyExistingGeminiToolHasOneVersionedProviderNeutralContract() {
        val expected = setOf(
            "search_conversation_history", "search_memory", "search_money", "record_money_event", "settle_debt",
            "reverse_money_event", "correct_money_event", "annotate_money_event", "save_note", "edit_memory_object",
            "delete_memory_object", "restore_memory_object", "link_objects", "list_tables", "read_table_rows",
            "create_table", "add_table_row", "update_table_row", "delete_table_row", "restore_table_row",
            "rename_table", "delete_table", "restore_table", "list_tasks", "create_task", "edit_task",
            "set_task_completion", "list_calendar_events", "create_calendar_event", "edit_calendar_event",
            "cancel_calendar_event",
        )
        val contracts = AgentToolCatalog.all()

        assertEquals(expected, contracts.map { it.name }.toSet())
        assertEquals(contracts.size, contracts.map { it.name to it.version }.distinct().size)
        contracts.forEach { contract ->
            assertEquals(1, contract.version)
            assertEquals(1, contract.result.version)
            assertNotNull(contract.description.takeIf(String::isNotBlank))
            assertEquals(contract.parameters.size, contract.parameters.map { it.name }.distinct().size)
            contract.parameters.forEach { parameter ->
                if (parameter.type == AgentToolValueType.ENUM) assertTrue(parameter.enumValues.isNotEmpty())
                else assertTrue(parameter.enumValues.isEmpty())
            }
        }
    }

    @Test fun requiredAndOptionalFieldsMatchLocalHandlerContracts() {
        val record = requireNotNull(AgentToolCatalog.find("record_money_event", 1))
        assertEquals(setOf("kind", "person_name", "amount"), record.parameters.filterNot { it.optional }.map { it.name }.toSet())
        assertEquals(setOf("currency", "occurred_date", "comment"), record.optionalParameters.toSet())
        assertEquals(listOf("receipt", "debt", "expense"), record.parameters.single { it.name == "kind" }.enumValues)

        val editTask = requireNotNull(AgentToolCatalog.find("edit_task", 1))
        assertEquals(setOf("task_id"), editTask.parameters.filterNot { it.optional }.map { it.name }.toSet())
        assertEquals(7, editTask.optionalParameters.size)

        val readMoney = requireNotNull(AgentToolCatalog.find("search_money", 1))
        assertTrue(readMoney.parameters.all { it.optional })
    }

    @Test fun alternateProviderUsesTheSameRuntimeToolAndTypedResultEnvelope() = runBlocking {
        val tool = PersistedNoteTool()
        val fakeProvider = FakeAgentModelGateway(
            AgentModelReply.Calls(listOf(AgentModelToolCall("fake-call-1", "save_note", 1,
                mapOf("title" to "Synthetic note", "body" to "Remember a fictional meeting", "kind" to "note")))),
            AgentModelReply.Answer("Yadda saxladım."),
        )
        val result = AgentOrchestrator(fakeProvider, DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004", null,
                "az-AZ", "Sintetik qeydi yadda saxla"))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Yadda saxladım.", result.answer)
        assertEquals(1, tool.commitCount)
        assertEquals("Synthetic note", tool.savedTitle)
        assertTrue(fakeProvider.requests.first().tools.any { it.name == "save_note" })
        val observed = result.toolResults.single().toProviderNeutralResult()
        assertEquals(ToolObservationStatus.APPLIED, observed.status)
        assertEquals("saved", observed.code)
        assertEquals(mapOf("id" to "synthetic-object-id", "kind" to "note"), observed.data)
        assertFalse(fakeProvider.requests.first().tools.any { AgentToolCatalog.find(it.name, it.version) == null })
    }

    @Test fun alternateProviderCanAnswerConversationWithoutSelectingTools() = runBlocking {
        val fakeProvider = FakeAgentModelGateway(AgentModelReply.Answer("Salam! Necə kömək edim?"))
        val result = AgentOrchestrator(fakeProvider, DefaultAgentToolRegistry(), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004", null,
                "az-AZ", "Salam"))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Salam! Necə kömək edim?", result.answer)
        assertTrue(result.toolResults.isEmpty())
        assertTrue(fakeProvider.requests.single().tools.isEmpty())
    }

    private class FakeAgentModelGateway(vararg replies: AgentModelReply) : AgentModelGateway {
        private val script = ArrayDeque(replies.toList())
        val requests = mutableListOf<AgentModelRequest>()
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            requests += request
            return script.removeFirst()
        }
    }

    private class PersistedNoteTool : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("save_note", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        var commitCount = 0
        var savedTitle: String? = null

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val body = call.arguments["body"] as? String ?: return ToolPreparation.Rejected("invalid_body")
            if (call.arguments["kind"] != "note" || title.isBlank() || body.isBlank()) return ToolPreparation.Rejected("invalid_note")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, "a".repeat(64), emptyMap(), false,
                mapOf("title" to title, "body" to body)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            commitCount++
            savedTitle = (command.opaquePayload as Map<*, *>)["title"] as String
            return ToolCommitResult(ToolObservationStatus.APPLIED, "saved", mapOf("id" to "synthetic-object-id", "kind" to "note"))
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class MemoryJournal : AgentRunJournal {
        private val turns = mutableMapOf<String, AgentTurnStatus>()
        private val commands = mutableMapOf<String, AgentCommandJournalEntry>()
        override suspend fun beginTurn(turnId: String) { turns[turnId] = AgentTurnStatus.THINKING }
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) { turns[turnId] = status }
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) { turns[turnId] = status }
        override suspend fun recordCommand(command: AgentCommandJournalEntry) { commands[command.commandId] = command }
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) {
            commands[commandId]?.let { commands[commandId] = it.copy(status = status) }
        }
        override suspend fun interruptedTurns() = emptyList<AgentTurnJournalEntry>()
        override suspend fun command(commandId: String) = commands[commandId]
        override suspend fun unresolvedCommands() = emptyList<AgentCommandJournalEntry>()
    }
}

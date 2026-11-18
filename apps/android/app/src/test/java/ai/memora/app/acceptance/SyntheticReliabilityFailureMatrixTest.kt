package ai.memora.app.acceptance

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.agent.AgentCommandJournalEntry
import ai.memora.app.agent.AgentCommandRecovery
import ai.memora.app.agent.AgentGatewayFailure
import ai.memora.app.agent.AgentInputChannel
import ai.memora.app.agent.AgentModelGateway
import ai.memora.app.agent.AgentModelReply
import ai.memora.app.agent.AgentModelRequest
import ai.memora.app.agent.AgentModelToolCall
import ai.memora.app.agent.AgentOrchestrator
import ai.memora.app.agent.AgentRunJournal
import ai.memora.app.agent.AgentRunResultStatus
import ai.memora.app.agent.AgentRuntimePolicy
import ai.memora.app.agent.AgentToolDescriptor
import ai.memora.app.agent.AgentTurnJournalEntry
import ai.memora.app.agent.AgentTurnRequest
import ai.memora.app.agent.AgentTurnStatus
import ai.memora.app.agent.CommandRecoveryOutcome
import ai.memora.app.agent.DefaultAgentToolRegistry
import ai.memora.app.agent.LocalMemoryTools
import ai.memora.app.agent.PolicyAction
import ai.memora.app.agent.PolicyDecision
import ai.memora.app.agent.PreparedAgentCommand
import ai.memora.app.agent.ToolObservationStatus
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic failures at the existing agent/runtime and protected-ledger boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyntheticReliabilityFailureMatrixTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun providerUnavailable503AndOfflineBeforeAWriteLeaveLedgerAndJournalUntouched() = runBlocking {
        val db = databaseRule.database
        val scope = uuid(41)
        val domain = ProtectedDomainRepository(db)
        val initial = domain.allFinanceEvents(scope).size
        listOf("provider_http_503", "network_unavailable").forEachIndexed { index, code ->
            val journal = SyntheticJournal()
            val gateway = object : AgentModelGateway {
                override suspend fun complete(request: AgentModelRequest): AgentModelReply = throw AgentGatewayFailure(code)
            }
            val result = runtime(gateway, emptyList(), journal).run(turn(scope, uuid(42 + index)))

            assertEquals(AgentRunResultStatus.FAILED, result.status)
            assertEquals(code, result.code)
            assertTrue(journal.commands.isEmpty())
            assertEquals(initial, domain.allFinanceEvents(scope).size)
        }

        val timeoutJournal = SyntheticJournal()
        val hanging = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply = CompletableDeferred<AgentModelReply>().await()
        }
        val timeout = runtime(hanging, emptyList(), timeoutJournal, modelTimeoutMillis = 25)
            .run(turn(scope, uuid(44)))
        assertEquals(AgentRunResultStatus.FAILED, timeout.status)
        assertEquals("model_timeout", timeout.code)
        assertTrue(timeoutJournal.commands.isEmpty())
        assertEquals(initial, domain.allFinanceEvents(scope).size)
    }

    @Test fun provider503AfterVerifiedReceiptKeepsExactlyOneWriteAndReportsPartial() = runBlocking {
        val db = databaseRule.database
        val scope = uuid(45)
        val domain = ProtectedDomainRepository(db)
        val journal = SyntheticJournal()
        var requests = 0
        val gateway = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                return if (requests++ == 0) AgentModelReply.Calls(listOf(receiptCall("receipt-once")))
                else throw AgentGatewayFailure("provider_http_503")
            }
        }

        val result = runtime(gateway, listOf(moneyHandler(db)), journal).run(turn(scope, uuid(46)))

        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals("reply_unavailable_after_write", result.code)
        assertEquals(2, requests)
        assertEquals(1, result.toolResults.count { it.status == ToolObservationStatus.APPLIED })
        assertEquals(1, domain.allFinanceEvents(scope).size)
        assertEquals(listOf("committed"), journal.commands.values.map { it.status })
    }

    @Test fun duplicateSemanticFinanceCallWithDifferentProviderIdsReusesTheCommittedReadback() = runBlocking {
        val db = databaseRule.database
        val scope = uuid(47)
        val domain = ProtectedDomainRepository(db)
        val journal = SyntheticJournal()
        var requests = 0
        val gateway = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                return when (requests++) {
                    0 -> AgentModelReply.Calls(listOf(receiptCall("provider-id-a")))
                    1 -> {
                        assertEquals(1, request.previousToolResults.count { it.status == ToolObservationStatus.APPLIED })
                        AgentModelReply.Calls(listOf(receiptCall("provider-id-b")))
                    }
                    else -> AgentModelReply.Answer("Synthetic receipt is recorded once.")
                }
            }
        }

        val result = runtime(gateway, listOf(moneyHandler(db)), journal).run(turn(scope, uuid(48)))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(3, requests)
        assertEquals(2, result.toolResults.size)
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val receiptIds = result.toolResults.map { it.modelData["id"] }.distinct()
        assertEquals(1, receiptIds.size)
        assertEquals(1, domain.allFinanceEvents(scope).size)
        assertEquals(1, journal.commands.size)
    }

    @Test fun interruptionAfterLedgerCommitIsMarkedUnknownAndRecoveryReadsInsteadOfRetrying() = runBlocking {
        val db = databaseRule.database
        val scope = uuid(49)
        val domain = ProtectedDomainRepository(db)
        val journal = RoomAgentRunJournal(db)
        val delegate = moneyHandler(db)
        var requests = 0
        var simulateLostResult = true
        val gateway = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                requests++
                return AgentModelReply.Calls(listOf(receiptCall("interrupted-receipt")))
            }
        }
        val interruptedHandler = object : ai.memora.app.agent.AgentToolHandler {
            override val descriptor: AgentToolDescriptor get() = delegate.descriptor
            override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest) = delegate.read(call, turn)
            override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
                delegate.prepare(call, turn, commandId)
            override suspend fun commit(command: PreparedAgentCommand): ai.memora.app.agent.ToolCommitResult {
                val verified = delegate.commit(command)
                if (simulateLostResult) {
                    simulateLostResult = false
                    throw CancellationException("synthetic process interruption after durable write")
                }
                return verified
            }
        }

        val firstRuntime = runtime(gateway, listOf(interruptedHandler), journal)
        val interrupted = runCatching { firstRuntime.run(turn(scope, uuid(50))) }.exceptionOrNull()
        assertTrue(interrupted is ai.memora.app.agent.AgentTurnCancelledException)
        assertTrue((interrupted as ai.memora.app.agent.AgentTurnCancelledException).recoveryRequired)
        assertEquals(1, domain.allFinanceEvents(scope).size)
        val unresolved = journal.unresolvedCommands()
        assertEquals(1, unresolved.size)
        assertEquals("reconciling", unresolved.single().status)
        val commandId = unresolved.single().commandId

        val recovered = runtime(gateway, listOf(moneyHandler(db)), RoomAgentRunJournal(db)).recoverUnresolvedCommands(AgentCommandRecovery { command ->
            if (db.protectedStoreDao().moneyEventForCommand(scope, command.commandId) != null)
                CommandRecoveryOutcome.COMMITTED else CommandRecoveryOutcome.NOT_APPLIED
        })

        assertEquals("committed", recovered.single().status)
        assertEquals("committed", journal.command(commandId)?.status)
        assertEquals(1, requests)
        assertEquals(1, domain.allFinanceEvents(scope).size)
        assertFalse(journal.command(commandId)?.status == "reconciling")
    }

    private fun moneyHandler(db: ai.memora.app.data.protectedstore.AgentDatabase) = LocalMemoryTools(
        ProtectedDomainRepository(db), ProtectedMemoryRepository(db), financeEnabled = true,
    ).handlers().single { it.descriptor.name == "record_money_event" }

    private fun runtime(
        gateway: AgentModelGateway,
        handlers: List<ai.memora.app.agent.AgentToolHandler>,
        journal: ai.memora.app.agent.AgentRunJournal,
        modelTimeoutMillis: Long = 30_000,
    ) = AgentOrchestrator(gateway, DefaultAgentToolRegistry(handlers), SyntheticPolicy(), journal,
        modelTimeoutMillis = modelTimeoutMillis)

    private fun turn(scope: String, turnId: String) = AgentTurnRequest(
        turnId, scope, uuid(51), null, "az-AZ", "Synthetic: Nigar received 42 manat", AgentInputChannel.TEXT,
    )

    private fun receiptCall(callId: String) = AgentModelToolCall(callId, "record_money_event", 1, mapOf(
        "kind" to "receipt", "person_name" to "Synthetic Nigar", "amount" to "42", "currency" to "AZN",
        "comment" to "Reliability fixture receipt",
    ))

    private fun uuid(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private class SyntheticPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class SyntheticJournal : AgentRunJournal {
        val commands = linkedMapOf<String, AgentCommandJournalEntry>()
        private val turns = linkedMapOf<String, AgentTurnStatus>()
        override suspend fun beginTurn(turnId: String) { turns[turnId] = AgentTurnStatus.THINKING }
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) { turns[turnId] = status }
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) { turns[turnId] = status }
        override suspend fun recordCommand(command: AgentCommandJournalEntry) { commands[command.commandId] = command }
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) {
            val old = commands[commandId] ?: error("missing synthetic command")
            check(old.status == expected)
            commands[commandId] = old.copy(status = status)
        }
        override suspend fun interruptedTurns() = turns.filterValues {
            it == AgentTurnStatus.THINKING || it == AgentTurnStatus.EXECUTING
        }.map { AgentTurnJournalEntry(it.key, it.value, 0) }
        override suspend fun command(commandId: String) = commands[commandId]
        override suspend fun unresolvedCommands() = commands.values.filter {
            it.status in setOf("prepared", "applying", "reconciling")
        }
    }
}

package ai.memora.app.agent

import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeTest {
    private val turn = AgentTurnRequest(
        turnId = id(1), scopeId = id(2), actorId = id(3), sourceRevisionId = id(4),
        locale = "az-AZ", text = "Sintetik tapşırıq yarat",
    )

    @Test fun emptyRegistryIsClosedAndCanAnswerWithoutTools() = runBlocking {
        val registry = DefaultAgentToolRegistry()
        val model = QueueModel(AgentModelReply.Answer("Hazırdır"))
        val result = runtime(model, registry).run(turn)
        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Hazırdır", result.answer)
        assertEquals(0, model.lastRequest!!.tools.size)
    }

    @Test fun toolThenAnswerRechecksMemoryConsentOnEveryModelSend() = runBlocking {
        val memoryTool = FakeTool(access = ToolAccess.PERSISTED_MEMORY, effect = ToolEffect.READ)
        val registry = DefaultAgentToolRegistry(listOf(memoryTool))
        val policy = AllowPolicy()
        val model = QueueModel(
            AgentModelReply.Calls(listOf(call("first", "write_test"))),
            AgentModelReply.Answer("Tapıldı"),
        )
        val result = runtime(model, registry, policy).run(turn)
        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(listOf(false, true), policy.modelMemoryRequirements)
        assertEquals(1, memoryTool.reads)
        assertEquals(0, memoryTool.commits)
        assertTrue(policy.journal.commands.isEmpty())
    }

    @Test fun deniedMemoryConsentPreventsSecondCloudSend() = runBlocking {
        val tool = FakeTool(access = ToolAccess.PERSISTED_MEMORY, effect = ToolEffect.READ)
        val policy = AllowPolicy().apply { allowMemory = false }
        val model = QueueModel(AgentModelReply.Calls(listOf(call("first", "write_test"))), AgentModelReply.Answer("must not arrive"))
        val result = runtime(model, DefaultAgentToolRegistry(listOf(tool)), policy).run(turn)
        assertEquals(AgentRunResultStatus.FAILED, result.status)
        assertEquals("consent_required", result.code)
        assertEquals(1, model.requests)
        assertEquals(1, tool.reads)
        assertEquals(0, tool.commits)
    }

    @Test fun unknownAndDisabledToolsAreRejectedWithoutEffect() = runBlocking {
        val disabled = FakeTool(enabled = false)
        val model = QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test"))), AgentModelReply.Answer("No se pudo"))
        val result = runtime(model, DefaultAgentToolRegistry(listOf(disabled))).run(turn)
        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals(ToolObservationStatus.REJECTED, result.toolResults.single().status)
        assertEquals("tool_unavailable", result.toolResults.single().code)
        assertEquals(0, disabled.commits)
    }

    @Test fun duplicateProviderCallIdCannotCauseSecondCommit() = runBlocking {
        val tool = FakeTool()
        val model = QueueModel(
            AgentModelReply.Calls(listOf(call("same", "write_test"))),
            AgentModelReply.Calls(listOf(call("same", "write_test"))),
            AgentModelReply.Answer("Təkrar rədd edildi"),
        )
        val result = runtime(model, DefaultAgentToolRegistry(listOf(tool))).run(turn)
        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals(1, tool.commits)
        assertEquals(listOf(ToolObservationStatus.APPLIED, ToolObservationStatus.REJECTED), result.toolResults.map { it.status })
    }

    @Test fun allCallsInOneModelReplyAreCommittedAndReturnedTogether() = runBlocking {
        val note = FakeTool(toolName = "save_note")
        val finance = FakeTool(toolName = "record_money_event")
        val model = QueueModel(
            AgentModelReply.Calls(listOf(call("a", "save_note"), call("b", "record_money_event"))),
            AgentModelReply.Answer("Yadda saxlanıldı"),
        )
        val result = runtime(model, DefaultAgentToolRegistry(listOf(note, finance))).run(turn)

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(1, note.commits)
        assertEquals(1, finance.commits)
        assertEquals(listOf("save_note", "record_money_event"), model.lastRequest!!.previousToolResults.map { it.name })
    }

    @Test fun prepareFailureIsReportedAndDoesNotStopOtherCallsOrInviteFalseSuccess() = runBlocking {
        val failing = FakeTool(toolName = "save_note", prepareFailure = IllegalStateException("synthetic-private-error"))
        val succeeding = FakeTool(toolName = "record_money_event")
        val model = QueueModel(
            AgentModelReply.Calls(listOf(call("bad", "save_note"), call("good", "record_money_event"))),
            AgentModelReply.Answer("Hər şey hazırdır"),
        )
        val result = runtime(model, DefaultAgentToolRegistry(listOf(failing, succeeding))).run(turn)

        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals("Əməliyyat qismən tamamlandı, amma bir dəyişiklik yadda saxlanmadı. Yazını yoxlayın.", result.answer)
        assertEquals(listOf(ToolObservationStatus.FAILED, ToolObservationStatus.APPLIED), result.toolResults.map { it.status })
        assertEquals("prepare_failed", result.toolResults.first().code)
        assertEquals(0, failing.commits)
        assertEquals(1, succeeding.commits)
        assertFalse(result.answer.orEmpty().contains("synthetic-private-error"))
    }

    @Test fun domainConflictCanReturnClarificationButCannotBeReportedAsSaved() = runBlocking {
        fun ambiguousTool(code: String = "person_name_ambiguous") = object : AgentToolHandler {
            override val descriptor = AgentToolDescriptor("record_money_event", 1, ToolAccess.PERSISTED_MEMORY,
                ToolEffect.WRITE, runtimeEnabled = true)
            override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
                ToolPreparation.Conflict(code)
            override suspend fun commit(command: PreparedAgentCommand) =
                ToolCommitResult(ToolObservationStatus.APPLIED, "should_not_commit")
        }
        val clarification = "Hansı Samiri nəzərdə tutursunuz?"
        val conflictWithQuestion = runtime(QueueModel(
            AgentModelReply.Calls(listOf(call("ambiguous", "record_money_event"))),
            AgentModelReply.Clarification(clarification),
        ), DefaultAgentToolRegistry(listOf(ambiguousTool()))).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CLARIFICATION, conflictWithQuestion.status)
        assertEquals(clarification, conflictWithQuestion.answer)
        assertEquals(ToolObservationStatus.CONFLICT, conflictWithQuestion.toolResults.single().status)

        val conflictWithFalseSuccess = runtime(QueueModel(
            AgentModelReply.Calls(listOf(call("ambiguous", "record_money_event"))),
            AgentModelReply.Answer("Yazıldı"),
        ), DefaultAgentToolRegistry(listOf(ambiguousTool()))).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CLARIFICATION, conflictWithFalseSuccess.status)
        assertEquals("person_name_ambiguous", conflictWithFalseSuccess.code)
        assertFalse(conflictWithFalseSuccess.answer.orEmpty().contains("Yazıldı"))

        val storageConflict = runtime(QueueModel(
            AgentModelReply.Calls(listOf(call("conflict", "record_money_event"))),
            AgentModelReply.Answer("Yazıldı"),
        ), DefaultAgentToolRegistry(listOf(ambiguousTool("version_conflict")))).run(turn)
        assertEquals(AgentRunResultStatus.PARTIAL, storageConflict.status)
        assertEquals("tool_write_failed", storageConflict.code)
    }

    @Test fun successfulRetryResolvesEarlierRejectedWriteForSameTarget() = runBlocking {
        var attempts = 0
        val correction = object : AgentToolHandler {
            override val descriptor = AgentToolDescriptor("correct_money_event", 1, ToolAccess.PERSISTED_MEMORY,
                ToolEffect.WRITE, runtimeEnabled = true)
            override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
                attempts++
                if (attempts == 1) return ToolPreparation.Rejected("invalid_amount")
                return ToolPreparation.Prepared(PreparedAgentCommand(commandId, "a".repeat(64), emptyMap(), false, Unit))
            }
            override suspend fun commit(command: PreparedAgentCommand) = ToolCommitResult(ToolObservationStatus.APPLIED, "corrected")
        }
        val target = id(27)
        val args = mapOf("event_id" to target, "new_amount" to "80")
        val result = runtime(QueueModel(
            AgentModelReply.Calls(listOf(call("attempt-1", "correct_money_event", args))),
            AgentModelReply.Calls(listOf(call("attempt-2", "correct_money_event", args))),
            AgentModelReply.Answer("Düzəliş edildi"),
        ), DefaultAgentToolRegistry(listOf(correction))).run(turn)

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(listOf(ToolObservationStatus.REJECTED, ToolObservationStatus.APPLIED), result.toolResults.map { it.status })
        assertEquals(1, result.toolResults.map { it.operationKey }.distinct().size)
        assertEquals("Düzəliş edildi", result.answer)
    }

    @Test fun successfulDifferentWriteDoesNotMaskAnUnresolvedFailure() = runBlocking {
        val correction = object : AgentToolHandler {
            override val descriptor = AgentToolDescriptor("correct_money_event", 1, ToolAccess.PERSISTED_MEMORY,
                ToolEffect.WRITE, runtimeEnabled = true)
            override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
                if (call.arguments["event_id"] == id(28)) return ToolPreparation.Rejected("invalid_amount")
                return ToolPreparation.Prepared(PreparedAgentCommand(commandId, "a".repeat(64), emptyMap(), false, Unit))
            }
            override suspend fun commit(command: PreparedAgentCommand) = ToolCommitResult(ToolObservationStatus.APPLIED, "corrected")
        }
        val result = runtime(QueueModel(
            AgentModelReply.Calls(listOf(
                call("failed", "correct_money_event", mapOf("event_id" to id(28), "new_amount" to "80")),
                call("applied", "correct_money_event", mapOf("event_id" to id(29), "new_amount" to "80")),
            )),
            AgentModelReply.Answer("Hər şey hazırdır"),
        ), DefaultAgentToolRegistry(listOf(correction))).run(turn)

        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals("tool_write_failed", result.code)
        assertEquals(listOf(ToolObservationStatus.REJECTED, ToolObservationStatus.APPLIED), result.toolResults.map { it.status })
    }

    @Test fun followupTimeoutAfterVerifiedWriteReportsPartialInsteadOfFalseStorageFailure() = runBlocking {
        val tool = FakeTool(toolName = "save_note", access = ToolAccess.PERSISTED_MEMORY)
        var requests = 0
        val model = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                requests++
                return if (requests == 1) AgentModelReply.Calls(listOf(call("save", "save_note")))
                else CompletableDeferred<AgentModelReply>().await()
            }
        }
        val journal = MemoryJournal()
        val result = AgentOrchestrator(model, DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal), journal,
            modelTimeoutMillis = 100, modelFollowupTimeoutMillis = 25, turnTimeoutMillis = 500).run(turn)

        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals("reply_timeout_after_write", result.code)
        assertTrue(result.answer.orEmpty().contains("təsdiqləyə bilmədi"))
        assertEquals(ToolObservationStatus.APPLIED, result.toolResults.single().status)
        assertEquals(1, tool.commits)
        assertEquals(2, requests)
    }

    @Test fun emptyFollowupAfterAppliedWriteRemainsPartialAndDoesNotRepeatCommit() = runBlocking {
        val tool = FakeTool(toolName = "save_note", access = ToolAccess.PERSISTED_MEMORY)
        var requests = 0
        val model = object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply =
                if (++requests == 1) AgentModelReply.Calls(listOf(call("save", "save_note")))
                else requireGeminiFinalAnswer("  ")
        }
        val result = runtime(model, DefaultAgentToolRegistry(listOf(tool))).run(turn)
        assertEquals(AgentRunResultStatus.PARTIAL, result.status)
        assertEquals("reply_unavailable_after_write", result.code)
        assertEquals(ToolObservationStatus.APPLIED, result.toolResults.single().status)
        assertEquals(1, tool.commits)
        assertEquals(2, requests)
    }

    @Test fun commandIdentityComesFromAppAndDigestAndVersionsAreJournaled() = runBlocking {
        val appCommandId = id(20)
        val tool = FakeTool()
        val journal = MemoryJournal()
        val result = AgentOrchestrator(QueueModel(AgentModelReply.Calls(listOf(call("provider-owned", "write_test"))), AgentModelReply.Answer("Oldu")),
            DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal), journal, idFactory = { appCommandId }).run(turn)
        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        val entry = journal.command(appCommandId)!!
        assertEquals(appCommandId, entry.commandId)
        assertEquals(setOf(id(8) to 3L), entry.targetVersions.entries.map { it.key to it.value }.toSet())
        assertEquals("a".repeat(64), entry.payloadDigest)
    }

    @Test fun unconfirmedWriteStopsBeforeJournalAndCommit() = runBlocking {
        val tool = FakeTool(requiresConfirmation = true)
        val journal = MemoryJournal()
        val result = runtime(QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test")))),
            DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal).apply { confirmation = false }, journal).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CONFIRMATION, result.status)
        assertEquals("confirmation_required", result.code)
        assertEquals(0, journal.commands.size)
        assertEquals(0, tool.commits)
    }

    @Test fun externalWriteAlwaysRequiresExplicitConfirmation() = runBlocking {
        val tool = FakeTool(access = ToolAccess.EXTERNAL)
        val journal = MemoryJournal()
        val result = runtime(QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test")))),
            DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal).apply { confirmation = false }, journal).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CONFIRMATION, result.status)
        assertEquals(0, tool.commits)
        assertTrue(journal.commands.isEmpty())
    }

    @Test fun unknownEffectIsReconciledAndNeverBlindlyRetried() = runBlocking {
        val tool = FakeTool(commitFailure = IllegalStateException("synthetic-private-error"))
        val journal = MemoryJournal()
        val model = QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test"))), AgentModelReply.Answer("must not retry"))
        val result = runtime(model, DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal), journal).run(turn)
        assertEquals(AgentRunResultStatus.RECOVERY_REQUIRED, result.status)
        assertEquals("effect_outcome_unknown", result.code)
        assertEquals(1, model.requests)
        assertEquals("reconciling", journal.command(tool.lastCommandId)?.status)
        assertFalse(result.code!!.contains("private"))
        val executor = runtime(model, DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal), journal)
        val unknown = executor.recoverUnresolvedCommands { CommandRecoveryOutcome.UNKNOWN }
        assertEquals("reconciling", unknown.single().status)
        val reconciled = executor.recoverUnresolvedCommands { CommandRecoveryOutcome.COMMITTED }
        assertEquals("committed", reconciled.single().status)
        assertEquals(1, model.requests)
        assertEquals(1, tool.commits)
    }

    @Test fun persistedCallIdAndModelArgsCannotExceedBoundedPayload() = runBlocking {
        val tool = FakeTool()
        val model = QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test", mapOf("text" to "x".repeat(33_000))))))
        val result = runtime(model, DefaultAgentToolRegistry(listOf(tool))).run(turn)
        assertEquals(AgentRunResultStatus.FAILED, result.status)
        assertEquals(0, tool.commits)
        assertEquals("agent_execution_failed", result.code)
    }

    @Test fun malformedInputAndWrongLocaleAreRejectedBeforeJournal() = runBlocking {
        val journal = MemoryJournal()
        try { runtime(QueueModel(AgentModelReply.Answer("ok")), DefaultAgentToolRegistry(), AllowPolicy(journal), journal)
            .run(turn.copy(text = "\uD800")); fail("unpaired surrogate accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(journal.turns.isEmpty())
        try { runtime(QueueModel(AgentModelReply.Answer("ok")), DefaultAgentToolRegistry(), AllowPolicy(journal), journal)
            .run(turn.copy(locale = "tr-TR")); fail("unsupported locale accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(journal.turns.isEmpty())
    }

    @Test fun recoveryMarksInterruptedTurnWithoutRetainingText() = runBlocking {
        val journal = MemoryJournal()
        journal.beginTurn(turn.turnId)
        journal.setTurnStatus(turn.turnId, AgentTurnStatus.EXECUTING)
        val recovered = runtime(QueueModel(AgentModelReply.Answer("unused")), DefaultAgentToolRegistry(), AllowPolicy(journal), journal)
            .recoverInterruptedTurns()
        assertEquals(AgentTurnStatus.RECOVERY_REQUIRED, recovered.single().status)
        assertEquals(AgentTurnStatus.RECOVERY_REQUIRED, journal.turns[turn.turnId])
        assertFalse(journal.toString().contains(turn.text))
    }

    @Test fun cancellationBeforeCommitPreventsEffectAndFinishesMetadata() = runBlocking {
        val prepared = CompletableDeferred<Unit>()
        val holdPolicy = AllowPolicy().apply { beforeCommit = { prepared.complete(Unit); CompletableDeferred<Unit>().await() } }
        val tool = FakeTool()
        val journal = MemoryJournal()
        val scope = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext)
        val controller = TurnController(scope, runtime(QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test")))),
            DefaultAgentToolRegistry(listOf(tool)), holdPolicy, journal))
        assertTrue(controller.submit(turn))
        assertFalse(controller.submit(turn.copy(turnId = id(99))))
        withTimeout(2_000) { prepared.await() }
        assertTrue(controller.cancel(turn.turnId))
        withTimeout(2_000) { while (controller.states.value[turn.turnId] != AgentTurnStatus.CANCELLED) kotlinx.coroutines.yield() }
        assertEquals(0, tool.commits)
        assertEquals(AgentTurnStatus.CANCELLED, journal.turns[turn.turnId])
    }

    @Test fun modelTimeoutIsTypedAndDoesNotCreateAnyCommand() = runBlocking {
        val journal = MemoryJournal()
        val result = AgentOrchestrator(object : AgentModelGateway {
            override suspend fun complete(request: AgentModelRequest): AgentModelReply = CompletableDeferred<AgentModelReply>().await()
        }, DefaultAgentToolRegistry(), AllowPolicy(journal), journal, modelTimeoutMillis = 25, turnTimeoutMillis = 250).run(turn)
        assertEquals(AgentRunResultStatus.FAILED, result.status)
        assertEquals("model_timeout", result.code)
        assertTrue(journal.commands.isEmpty())
    }

    @Test fun cancellationDuringCommitKeepsEffectUnknownForRecovery() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val tool = FakeTool(commitHook = { started.complete(Unit); CompletableDeferred<Unit>().await() })
        val journal = MemoryJournal()
        val controller = TurnController(kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext),
            runtime(QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test")))),
                DefaultAgentToolRegistry(listOf(tool)), AllowPolicy(journal), journal))
        assertTrue(controller.submit(turn))
        withTimeout(2_000) { started.await() }
        assertTrue(controller.cancel(turn.turnId))
        withTimeout(2_000) {
            while (controller.states.value[turn.turnId] != AgentTurnStatus.RECOVERY_REQUIRED) kotlinx.coroutines.yield()
        }
        assertEquals("reconciling", journal.command(tool.lastCommandId)?.status)
        assertEquals(1, tool.commits)
    }

    @Test fun clarificationAndConfirmationHaveDistinctTurnStates() = runBlocking {
        val clarification = runtime(QueueModel(AgentModelReply.Clarification("Hansı şəxs?")), DefaultAgentToolRegistry()).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CLARIFICATION, clarification.status)
        val confirmTool = FakeTool(requiresConfirmation = true)
        val confirmation = runtime(QueueModel(AgentModelReply.Calls(listOf(call("x", "write_test")))),
            DefaultAgentToolRegistry(listOf(confirmTool)), AllowPolicy().apply { confirmation = false }).run(turn)
        assertEquals(AgentRunResultStatus.AWAITING_CONFIRMATION, confirmation.status)
        assertNotEquals(clarification.status, confirmation.status)
    }

    private fun runtime(model: AgentModelGateway, registry: AgentToolRegistry, policy: AllowPolicy = AllowPolicy(), journal: MemoryJournal = policy.journal) =
        AgentOrchestrator(model, registry, policy, journal)

    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun call(id: String, name: String, arguments: Map<String, Any?> = emptyMap()) = AgentModelToolCall(id, name, 1, arguments)

    private class QueueModel(vararg replies: AgentModelReply) : AgentModelGateway {
        private val queue = ArrayDeque(replies.toList())
        var requests = 0
        var lastRequest: AgentModelRequest? = null
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            requests++
            lastRequest = request
            return queue.removeFirst()
        }
    }

    private class MemoryJournal : AgentRunJournal {
        val turns = linkedMapOf<String, AgentTurnStatus>()
        val commands = linkedMapOf<String, AgentCommandJournalEntry>()
        override suspend fun beginTurn(turnId: String) { check(turnId !in turns); turns[turnId] = AgentTurnStatus.THINKING }
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) { check(turnId in turns); turns[turnId] = status }
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) { setTurnStatus(turnId, status) }
        override suspend fun recordCommand(command: AgentCommandJournalEntry) {
            val old = commands[command.commandId]
            if (old == null) commands[command.commandId] = command
            else check(old.payloadDigest == command.payloadDigest && old.targetVersions == command.targetVersions)
        }
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) {
            val old = commands[commandId] ?: error("missing command")
            check(old.status == expected)
            commands[commandId] = old.copy(status = status)
        }
        override suspend fun interruptedTurns() = turns.filterValues { it in setOf(AgentTurnStatus.THINKING, AgentTurnStatus.EXECUTING) }
            .map { AgentTurnJournalEntry(it.key, it.value, 0) }
        override suspend fun command(commandId: String) = commands[commandId]
        override suspend fun unresolvedCommands() = commands.values.filter { it.status in setOf("prepared", "applying", "reconciling") }
        override fun toString() = "MemoryJournal(turns=${turns.keys.size}, commands=${commands.keys.size})"
    }

    private class AllowPolicy(val journal: MemoryJournal = MemoryJournal()) : AgentRuntimePolicy {
        val modelMemoryRequirements = mutableListOf<Boolean>()
        var allowMemory = true
        var confirmation = true
        var beforeCommit: (suspend () -> Unit)? = null
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean): PolicyDecision {
            modelMemoryRequirements += includesStoredMemory
            return if (!includesStoredMemory || allowMemory) PolicyDecision(PolicyAction.ALLOW, "allowed")
            else PolicyDecision(PolicyAction.DENY, "consent_required")
        }
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand): PolicyDecision {
            beforeCommit?.invoke()
            return PolicyDecision(PolicyAction.ALLOW, "allowed")
        }
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = confirmation
    }

    private class FakeTool(
        private val toolName: String = "write_test",
        access: ToolAccess = ToolAccess.CURRENT_TURN,
        effect: ToolEffect = ToolEffect.WRITE,
        enabled: Boolean = true,
        private val requiresConfirmation: Boolean = false,
        private val prepareFailure: Exception? = null,
        private val commitFailure: Exception? = null,
        private val commitHook: (suspend () -> Unit)? = null,
    ) : AgentToolHandler {
        override val descriptor = AgentToolDescriptor(toolName, 1, access, effect, enabled)
        var commits = 0
        var reads = 0
        var lastCommandId = ""
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            reads++
            return ToolCommitResult(ToolObservationStatus.APPLIED, "read_applied", mapOf("version" to 1))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            lastCommandId = commandId
            prepareFailure?.let { throw it }
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, "a".repeat(64),
                mapOf("00000000-0000-4000-8000-000000000008" to 3), requiresConfirmation, "opaque"))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            commits++
            commitHook?.invoke()
            commitFailure?.let { throw it }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "applied", mapOf("version" to 4))
        }
    }
}

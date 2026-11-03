package ai.memora.app.agent

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal val AGENT_PERSISTED_WRITE_TOOLS = setOf("save_note", "save_memory_episode", "delete_memory_episode", "record_money_event", "correct_money_event", "link_objects", "edit_memory_object",
    "delete_memory_object", "restore_memory_object", "create_table", "add_table_row", "update_table_row",
    "delete_table_row", "restore_table_row", "rename_table", "delete_table", "restore_table", "create_task",
    "set_task_completion", "edit_task", "create_calendar_event", "edit_calendar_event", "cancel_calendar_event",
    "settle_debt", "reverse_money_event", "annotate_money_event", "create_case", "update_case",
    "add_case_participant", "link_case_source")
private val WRITE_FAILURES = setOf(ToolObservationStatus.REJECTED, ToolObservationStatus.CONFLICT, ToolObservationStatus.FAILED)

internal fun unresolvedAgentWriteFailures(observations: List<AgentToolObservation>): List<AgentToolObservation> =
    observations.asSequence()
        .filter { it.name in AGENT_PERSISTED_WRITE_TOOLS }
        .groupBy { it.operationKey ?: "call:${it.callId}" }
        .values
        .map { it.last() }
        .filter { it.status in WRITE_FAILURES }

/** Ephemeral user input. Never written to AgentRunJournal. */
enum class AgentInputChannel { VOICE, TEXT }

/** A bounded excerpt of completed dialogue, loaded only when persisted-memory consent is active. */
data class AgentDialogueMessage(val role: String, val text: String) {
    init { require(role in setOf("user", "model") && text.isNotBlank() && text.length <= 4_000) }
}

/** Explicit, non-identifying profile choices that may be sent only after the cloud model consent gate. */
data class AgentResponsePreferences(val language: String, val toneStyle: String, val responseStyle: String) {
    init {
        require(language in setOf("az", "ru", "en"))
        require(toneStyle in setOf("friendly", "direct"))
        require(responseStyle in setOf("concise", "balanced", "detailed"))
    }
}

data class AgentTurnRequest(
    val turnId: String,
    val scopeId: String,
    val actorId: String,
    val sourceRevisionId: String?,
    val locale: String,
    val text: String,
    val channel: AgentInputChannel = AgentInputChannel.VOICE,
    val conversationHistory: List<AgentDialogueMessage> = emptyList(),
    val responsePreferences: AgentResponsePreferences? = null,
)

enum class AgentTurnStatus {
    THINKING, EXECUTING, AWAITING_CLARIFICATION, AWAITING_CONFIRMATION,
    COMPLETED, PARTIAL, FAILED, CANCELLED, RECOVERY_REQUIRED,
}
enum class ToolAccess { CURRENT_TURN, PERSISTED_MEMORY, EXTERNAL }
enum class ToolEffect { READ, WRITE }

data class AgentToolDescriptor(
    val name: String,
    val version: Int,
    val access: ToolAccess,
    val effect: ToolEffect,
    /** False unless a future product gate explicitly adds this tool. */
    val runtimeEnabled: Boolean = false,
)

data class AgentModelToolCall(
    val callId: String,
    val name: String,
    val version: Int,
    val arguments: Map<String, Any?>,
)

data class AgentToolObservation(
    val callId: String,
    val name: String,
    val status: ToolObservationStatus,
    /** Bounded, typed handler output; never raw provider exception/log text. */
    val code: String,
    val modelData: Map<String, Any?> = emptyMap(),
    /** In-memory correlation digest for a model retry; never sent to Gemini or persisted. */
    val operationKey: String? = null,
)

enum class ToolObservationStatus { APPLIED, REJECTED, CONFLICT, FAILED, UNKNOWN, NEEDS_CONFIRMATION }

class AgentTurnCancelledException(val recoveryRequired: Boolean) : CancellationException(
    if (recoveryRequired) "Agent turn cancelled with an unresolved effect" else "Agent turn cancelled",
)

data class AgentModelRequest(
    val turnId: String,
    val locale: String,
    val userText: String,
    val previousToolResults: List<AgentToolObservation>,
    val tools: List<AgentToolDescriptor>,
    val conversationHistory: List<AgentDialogueMessage> = emptyList(),
    val responsePreferences: AgentResponsePreferences? = null,
)

sealed interface AgentModelReply {
    data class Calls(val calls: List<AgentModelToolCall>) : AgentModelReply
    data class Answer(val text: String) : AgentModelReply
    data class Clarification(val text: String) : AgentModelReply
}

interface AgentModelGateway {
    /** Exchanges canonical model/tool values only; provider SDK objects stay inside the adapter. */
    suspend fun complete(request: AgentModelRequest): AgentModelReply
}

enum class PolicyAction { ALLOW, DENY, NEEDS_CONFIRMATION }
data class PolicyDecision(val action: PolicyAction, val code: String)

/** Implementations must re-read consent/permissions on every model send, tool use and commit. */
interface AgentRuntimePolicy {
    suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean): PolicyDecision
    suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor): PolicyDecision
    suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand): PolicyDecision
    suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand): Boolean
}

data class PreparedAgentCommand(
    /** Created by the app; model-provided callId is never used as command identity. */
    val commandId: String,
    val payloadDigest: String,
    val targetVersions: Map<String, Long>,
    val requiresConfirmation: Boolean,
    /** In-memory handler payload; it must never enter the journal or diagnostics. */
    val opaquePayload: Any,
)

sealed interface ToolPreparation {
    data class Prepared(val command: PreparedAgentCommand) : ToolPreparation
    data class Rejected(val code: String) : ToolPreparation
    data class Conflict(val code: String) : ToolPreparation
    data class Failed(val code: String) : ToolPreparation
}

data class ToolCommitResult(
    val status: ToolObservationStatus,
    val code: String,
    val modelData: Map<String, Any?> = emptyMap(),
)

interface AgentToolHandler {
    val descriptor: AgentToolDescriptor
    /** Read-only tools must not create a command or enter the mutation journal. */
    suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult =
        ToolCommitResult(ToolObservationStatus.FAILED, "read_not_supported")
    /** Validate untrusted arguments and reserve app-authored IDs, but make no domain change. */
    suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation
    /** Implementations must atomically commit domain state, history and durable result. */
    suspend fun commit(command: PreparedAgentCommand): ToolCommitResult
}

interface AgentToolRegistry {
    fun enabledTools(): List<AgentToolDescriptor>
    fun handler(name: String, version: Int): AgentToolHandler?
}

/** Exact name/version lookup and deny-by-default. */
class DefaultAgentToolRegistry(handlers: List<AgentToolHandler> = emptyList()) : AgentToolRegistry {
    private val byKey = handlers.associateBy { it.descriptor.name to it.descriptor.version }

    init {
        require(byKey.size == handlers.size) { "Duplicate tool name/version" }
        require(handlers.all { validToolName(it.descriptor.name) && it.descriptor.version > 0 })
    }

    override fun enabledTools() = byKey.values.map { it.descriptor }.filter { it.runtimeEnabled }
        .sortedWith(compareBy<AgentToolDescriptor> { it.name }.thenBy { it.version })

    override fun handler(name: String, version: Int): AgentToolHandler? =
        byKey[name to version]?.takeIf { it.descriptor.runtimeEnabled }

    companion object {
        private fun validToolName(name: String) = name.matches(Regex("[a-z][a-z0-9_]{0,63}"))
    }
}

data class AgentTurnJournalEntry(val turnId: String, val status: AgentTurnStatus, val updatedAtMillis: Long)
data class AgentCommandJournalEntry(
    val commandId: String,
    val payloadDigest: String,
    val status: String,
    val targetVersions: Map<String, Long>,
)

/** Persist only active turn IDs/statuses and command recovery metadata, never dialogue/model payloads. */
interface AgentRunJournal {
    suspend fun beginTurn(turnId: String)
    suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus)
    suspend fun finishTurn(turnId: String, status: AgentTurnStatus)
    suspend fun recordCommand(command: AgentCommandJournalEntry)
    suspend fun setCommandStatus(commandId: String, expected: String, status: String)
    suspend fun interruptedTurns(): List<AgentTurnJournalEntry>
    suspend fun command(commandId: String): AgentCommandJournalEntry?
    suspend fun unresolvedCommands(): List<AgentCommandJournalEntry>
}

enum class CommandRecoveryOutcome { COMMITTED, NOT_APPLIED, UNKNOWN }
fun interface AgentCommandRecovery {
    /** Must check canonical local state by app-authored commandId; never invoke the command. */
    suspend fun reconcile(command: AgentCommandJournalEntry): CommandRecoveryOutcome
}

enum class AgentRunResultStatus { COMPLETED, PARTIAL, AWAITING_CLARIFICATION, AWAITING_CONFIRMATION, FAILED, CANCELLED, RECOVERY_REQUIRED }
data class AgentRunResult(
    val turnId: String,
    val status: AgentRunResultStatus,
    val answer: String? = null,
    val code: String? = null,
    val toolResults: List<AgentToolObservation> = emptyList(),
)

/** Bounded model/tool loop. The production registry is empty until a domain package is accepted. */
class AgentOrchestrator(
    private val model: AgentModelGateway,
    private val registry: AgentToolRegistry,
    private val policy: AgentRuntimePolicy,
    private val journal: AgentRunJournal,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val maxModelRounds: Int = 8,
    private val maxToolCalls: Int = 16,
    private val modelTimeoutMillis: Long = 30_000,
    private val modelFollowupTimeoutMillis: Long = 30_000,
    private val turnTimeoutMillis: Long = 60_000,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxModelRounds in 1..8 && maxToolCalls in 1..16)
        require(modelTimeoutMillis in 1..30_000 && modelFollowupTimeoutMillis in 1..30_000 && turnTimeoutMillis in 1..60_000)
    }

    suspend fun run(turn: AgentTurnRequest): AgentRunResult {
        validateTurn(turn)
        journal.beginTurn(turn.turnId)
        val observations = ArrayList<AgentToolObservation>()
        val seenCallIds = hashSetOf<String>()
        var includesStoredMemory = turn.conversationHistory.isNotEmpty()
        var failedTools = false
        var uncertainEffect = false
        var terminal = AgentTurnStatus.FAILED
        var failureStage = "turn_loop"
        try {
            return withTimeout(turnTimeoutMillis) {
            journal.setTurnStatus(turn.turnId, AgentTurnStatus.THINKING)
            repeat(maxModelRounds) { round ->
                failureStage = "consent_gate"
                val consent = policy.beforeModelSend(turn, includesStoredMemory)
                logStage(turn.turnId, "policy_model", consent.action.name.lowercase(), consent.code)
                if (consent.action != PolicyAction.ALLOW) {
                    terminal = AgentTurnStatus.FAILED
                    return@withTimeout finish(turn, terminal, code = safeCode(consent.code), observations = observations)
                }
                failureStage = "model_gateway"
                val requestTimeout = if (observations.isEmpty()) modelTimeoutMillis else modelFollowupTimeoutMillis
                val reply = try {
                    withTimeoutOrNull(requestTimeout) { model.complete(AgentModelRequest(
                        turnId = turn.turnId,
                        locale = turn.locale,
                        userText = turn.text,
                        previousToolResults = observations.toList(),
                        tools = registry.enabledTools(),
                        conversationHistory = turn.conversationHistory,
                        responsePreferences = turn.responsePreferences,
                    )) }
                } catch (error: AgentGatewayFailure) {
                    val fallback = finishAfterModelFailure(turn, observations, error.safeCode)
                    if (fallback != null) return@withTimeout fallback
                    throw error
                } ?: run {
                    logWarning("model_timeout trace_id=${turn.turnId} phase=${if (observations.isEmpty()) "initial" else "followup"} tool_results=${observations.size} timeout_ms=$requestTimeout")
                    val fallback = finishAfterModelFailure(turn, observations, "model_timeout")
                    if (fallback != null) return@withTimeout fallback
                    return@withTimeout finish(turn, AgentTurnStatus.FAILED,
                        code = "model_timeout", observations = observations)
                }
                failureStage = "model_reply_validation"
                when (reply) {
                    is AgentModelReply.Answer -> {
                        failureStage = "answer_validation"
                        if (!safeOutput(reply.text)) {
                            logWarning("model_reply_rejected kind=answer")
                            return@withTimeout finish(turn, AgentTurnStatus.AWAITING_CLARIFICATION,
                                answer = "Zəhmət olmasa, istəyinizi bir az dəqiqləşdirin.",
                                code = "invalid_model_output", observations = observations)
                        }
                        val failedWrites = unresolvedAgentWriteFailures(observations)
                        val appliedWriteCount = observations.count { it.name in AGENT_PERSISTED_WRITE_TOOLS && it.status == ToolObservationStatus.APPLIED }
                        if (failedWrites.isNotEmpty()) {
                            // Gemini text is returned as Answer, even when it asks a question. A typed
                            // identity conflict must still lead to clarification, never a false save.
                            if (appliedWriteCount == 0 && failedWrites.all {
                                    it.status == ToolObservationStatus.CONFLICT && it.code == "person_name_ambiguous"
                                }) {
                                terminal = AgentTurnStatus.AWAITING_CLARIFICATION
                                return@withTimeout finish(turn, terminal, answer = when (turn.locale) {
                                    "ru-RU" -> "В памяти несколько людей с таким именем. Кого вы имеете в виду?"
                                    "en-US" -> "There are several people with that name. Which one do you mean?"
                                    else -> "Yaddaşda bu adda bir neçə şəxs var. Hansını nəzərdə tutursunuz?"
                                }, code = "person_name_ambiguous", observations = observations)
                            }
                            return@withTimeout finish(turn, AgentTurnStatus.PARTIAL,
                                answer = if (appliedWriteCount > 0) partialWriteFailure(turn.locale) else writeFailure(turn.locale),
                                code = "tool_write_failed", observations = observations)
                        }
                        terminal = if (failedTools) AgentTurnStatus.PARTIAL else AgentTurnStatus.COMPLETED
                        return@withTimeout finish(turn, terminal, answer = reply.text, observations = observations)
                    }
                    is AgentModelReply.Clarification -> {
                        failureStage = "clarification_validation"
                        if (!safeOutput(reply.text)) {
                            logWarning("model_reply_rejected kind=clarification")
                            return@withTimeout finish(turn, AgentTurnStatus.AWAITING_CLARIFICATION,
                                answer = "Zəhmət olmasa, istəyinizi bir az dəqiqləşdirin.",
                                code = "invalid_model_output", observations = observations)
                        }
                        // A domain conflict can be the correct reason to ask the user (for example,
                        // two scoped people with the same name). Preserve that clarification while
                        // still preventing an Answer from being reported as a successful write.
                        val hardWriteFailure = unresolvedAgentWriteFailures(observations).any {
                            it.status == ToolObservationStatus.REJECTED || it.status == ToolObservationStatus.FAILED
                        }
                        if (hardWriteFailure) {
                            terminal = AgentTurnStatus.PARTIAL
                            val hasAppliedWrite = observations.any { it.name in AGENT_PERSISTED_WRITE_TOOLS && it.status == ToolObservationStatus.APPLIED }
                            return@withTimeout finish(turn, terminal,
                                answer = if (hasAppliedWrite) partialWriteFailure(turn.locale) else writeFailure(turn.locale),
                                code = "tool_write_failed", observations = observations)
                        }
                        terminal = AgentTurnStatus.AWAITING_CLARIFICATION
                        return@withTimeout finish(turn, terminal, answer = reply.text, code = "clarification_required", observations = observations)
                    }
                    is AgentModelReply.Calls -> {
                        failureStage = "tool_call_validation"
                        if (reply.calls.isEmpty() || reply.calls.size > maxToolCalls) {
                            logWarning("model_reply_rejected kind=tool_calls count=${reply.calls.size}")
                            return@withTimeout finish(turn, AgentTurnStatus.AWAITING_CLARIFICATION,
                                answer = "Zəhmət olmasa, istəyinizi bir az dəqiqləşdirin.",
                                code = "invalid_model_tool_calls", observations = observations)
                        }
                        journal.setTurnStatus(turn.turnId, AgentTurnStatus.EXECUTING)
                        for (call in reply.calls) {
                            if (observations.size >= maxToolCalls) {
                                return@withTimeout finish(turn, AgentTurnStatus.PARTIAL, code = "tool_call_limit", observations = observations)
                            }
                            if (!validCallPayload(call) || !seenCallIds.add(call.callId)) {
                                if (call.name !in AGENT_PERSISTED_WRITE_TOOLS) failedTools = true
                                logStage(turn.turnId, "tool_validation", "rejected", "invalid_or_duplicate_call")
                                observations += rejected(call, if (validCall(call)) "invalid_or_duplicate_call" else "invalid_tool_call", turn.turnId)
                                logWarning("model_tool_rejected trace_id=${turn.turnId} name=${call.name.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "unknown"}")
                                continue
                            }
                            logStage(turn.turnId, "tool_validation", "passed", "valid_call")
                            logStage(turn.turnId, "tool_selected", "selected", call.name)
                            val handler = registry.handler(call.name, call.version)
                            if (handler == null) {
                                if (call.name !in AGENT_PERSISTED_WRITE_TOOLS) failedTools = true
                                observations += rejected(call, "tool_unavailable", turn.turnId)
                                continue
                            }
                            if (handler.descriptor.access == ToolAccess.PERSISTED_MEMORY) includesStoredMemory = true
                            val toolPermission = policy.authorizeTool(turn, handler.descriptor)
                            logStage(turn.turnId, "policy_tool", toolPermission.action.name.lowercase(), toolPermission.code)
                            if (toolPermission.action != PolicyAction.ALLOW) {
                                if (call.name !in AGENT_PERSISTED_WRITE_TOOLS) failedTools = true
                                observations += rejected(call, toolPermission.code, turn.turnId)
                                continue
                            }

                            if (handler.descriptor.effect == ToolEffect.READ) {
                                failureStage = "tool_read"
                                val readResult = try {
                                    handler.read(call, turn)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    ToolCommitResult(ToolObservationStatus.FAILED, "read_failed")
                                }
                                failureStage = "tool_read_result_validation"
                                val validatedReadData = runCatching { validateModelData(readResult.modelData) }.getOrNull()
                                if (validatedReadData == null) {
                                    failedTools = true
                                    logStage(turn.turnId, "tool_read_result_validation", "rejected", "tool_result_invalid")
                                    observations += AgentToolObservation(call.callId, call.name, ToolObservationStatus.FAILED,
                                        "tool_result_invalid")
                                    logToolOutcome(call.name, ToolObservationStatus.FAILED, "tool_result_invalid", turn.turnId)
                                    logWarning("tool_read_result_rejected name=${call.name.take(64)}")
                                    continue
                                }
                                logStage(turn.turnId, "tool_read_result_validation", "passed", "valid_result")
                                logToolOutcome(call.name, readResult.status, readResult.code, turn.turnId)
                                val observation = AgentToolObservation(call.callId, call.name, readResult.status,
                                    safeCode(readResult.code), validatedReadData)
                                if (!withinResultBudget(observations, observation)) {
                                    failedTools = true
                                    observations += rejected(call, "tool_result_limit_exceeded", turn.turnId)
                                    continue
                                }
                                observations += observation
                                if (readResult.status != ToolObservationStatus.APPLIED) failedTools = true
                                else logStage(turn.turnId, "database_readback", "verified", observation.code)
                                continue
                            }

                            failureStage = "tool_prepare"
                            val commandId = idFactory()
                            require(isUuid(commandId)) { "Application ID factory returned invalid command ID" }
                            val preparation = try {
                                handler.prepare(call, turn, commandId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                ToolPreparation.Failed("prepare_failed")
                            }
                            when (val prepared = preparation) {
                                is ToolPreparation.Rejected -> {
                                    logStage(turn.turnId, "tool_prepare", "rejected", prepared.code)
                                    observations += rejected(call, prepared.code, turn.turnId)
                                }
                                is ToolPreparation.Conflict -> {
                                    logStage(turn.turnId, "tool_prepare", "conflict", prepared.code)
                                    logToolOutcome(call.name, ToolObservationStatus.CONFLICT, prepared.code, turn.turnId)
                                    observations += AgentToolObservation(call.callId, call.name, ToolObservationStatus.CONFLICT,
                                        safeCode(prepared.code), operationKey = writeOperationKey(call))
                                }
                                is ToolPreparation.Failed -> {
                                    logStage(turn.turnId, "tool_prepare", "failed", prepared.code)
                                    observations += AgentToolObservation(call.callId, call.name, ToolObservationStatus.FAILED,
                                        safeCode(prepared.code), operationKey = writeOperationKey(call))
                                    logToolOutcome(call.name, ToolObservationStatus.FAILED, prepared.code, turn.turnId)
                                }
                                is ToolPreparation.Prepared -> {
                                    logStage(turn.turnId, "tool_prepare", "prepared", "ready")
                                    failureStage = "tool_command_validation"
                                    validatePrepared(prepared.command, commandId)
                                    val requiresConfirmation = prepared.command.requiresConfirmation ||
                                        handler.descriptor.access == ToolAccess.EXTERNAL
                                    if (requiresConfirmation && !policy.confirmationGranted(turn, prepared.command)) {
                                        journal.setTurnStatus(turn.turnId, AgentTurnStatus.AWAITING_CONFIRMATION)
                                        terminal = AgentTurnStatus.AWAITING_CONFIRMATION
                                        val needed = AgentToolObservation(call.callId, call.name, ToolObservationStatus.NEEDS_CONFIRMATION,
                                            "confirmation_required", operationKey = writeOperationKey(call))
                                        logToolOutcome(call.name, needed.status, needed.code, turn.turnId)
                                        observations += needed
                                        return@withTimeout finish(turn, terminal, code = "confirmation_required", observations = observations)
                                    }
                                    val commitPermission = policy.authorizeCommit(turn, prepared.command)
                                    logStage(turn.turnId, "policy_commit", commitPermission.action.name.lowercase(), commitPermission.code)
                                    if (commitPermission.action == PolicyAction.NEEDS_CONFIRMATION) {
                                        journal.setTurnStatus(turn.turnId, AgentTurnStatus.AWAITING_CONFIRMATION)
                                        terminal = AgentTurnStatus.AWAITING_CONFIRMATION
                                        observations += AgentToolObservation(call.callId, call.name,
                                            ToolObservationStatus.NEEDS_CONFIRMATION, "confirmation_required",
                                            operationKey = writeOperationKey(call))
                                        logToolOutcome(call.name, ToolObservationStatus.NEEDS_CONFIRMATION, "confirmation_required", turn.turnId)
                                        return@withTimeout finish(turn, terminal, code = "confirmation_required", observations = observations)
                                    }
                                    if (commitPermission.action != PolicyAction.ALLOW) {
                                        if (call.name !in AGENT_PERSISTED_WRITE_TOOLS) failedTools = true
                                        observations += rejected(call, commitPermission.code, turn.turnId)
                                        continue
                                    }
                                    val entry = AgentCommandJournalEntry(commandId, prepared.command.payloadDigest, "prepared",
                                        prepared.command.targetVersions)
                                    journal.recordCommand(entry)
                                    journal.setCommandStatus(commandId, "prepared", "applying")
                                    failureStage = "tool_commit"
                                    val committed = try {
                                        handler.commit(prepared.command)
                                    } catch (cancelled: CancellationException) {
                                        journal.setCommandStatus(commandId, "applying", "reconciling")
                                        uncertainEffect = true
                                        throw cancelled
                                    } catch (error: Exception) {
                                        journal.setCommandStatus(commandId, "applying", "reconciling")
                                        failedTools = true
                                        uncertainEffect = true
                                        logStage(turn.turnId, "tool_commit", "unknown", "effect_outcome_unknown")
                                        observations += AgentToolObservation(call.callId, call.name, ToolObservationStatus.UNKNOWN,
                                            "effect_outcome_unknown", operationKey = writeOperationKey(call))
                                        logToolOutcome(call.name, ToolObservationStatus.UNKNOWN, "effect_outcome_unknown", turn.turnId)
                                        terminal = AgentTurnStatus.RECOVERY_REQUIRED
                                        return@withTimeout finish(turn, terminal, code = "effect_outcome_unknown", observations = observations)
                                    }
                                    val nextStatus = when (committed.status) {
                                        ToolObservationStatus.APPLIED -> "committed"
                                        ToolObservationStatus.CONFLICT -> "conflict"
                                        ToolObservationStatus.REJECTED -> "rejected"
                                        ToolObservationStatus.NEEDS_CONFIRMATION -> "rejected"
                                        ToolObservationStatus.FAILED -> "failed"
                                        ToolObservationStatus.UNKNOWN -> "reconciling"
                                    }
                                    journal.setCommandStatus(commandId, "applying", nextStatus)
                                    logStage(turn.turnId, "tool_commit", committed.status.name.lowercase(), committed.code)
                                    val validatedModelData = runCatching { validateModelData(committed.modelData) }.getOrNull()
                                    if (validatedModelData == null) {
                                        failedTools = true
                                        observations += AgentToolObservation(call.callId, call.name, committed.status,
                                            "tool_result_invalid", operationKey = writeOperationKey(call))
                                        terminal = if (committed.status == ToolObservationStatus.UNKNOWN)
                                            AgentTurnStatus.RECOVERY_REQUIRED else AgentTurnStatus.PARTIAL
                                        return@withTimeout finish(turn, terminal, code = "tool_result_invalid", observations = observations)
                                    }
                                    failureStage = "tool_result_validation"
                                    val observation = AgentToolObservation(call.callId, call.name, committed.status,
                                        safeCode(committed.code), validatedModelData, writeOperationKey(call))
                                    logToolOutcome(call.name, observation.status, observation.code, turn.turnId)
                                    if (observation.status == ToolObservationStatus.APPLIED)
                                        logStage(turn.turnId, "database_readback", "verified", observation.code)
                                    if (!withinResultBudget(observations, observation)) {
                                        failedTools = true
                                        observations += AgentToolObservation(call.callId, call.name, committed.status,
                                            "tool_result_limit_exceeded", operationKey = writeOperationKey(call))
                                        terminal = if (committed.status == ToolObservationStatus.UNKNOWN)
                                            AgentTurnStatus.RECOVERY_REQUIRED else AgentTurnStatus.PARTIAL
                                        return@withTimeout finish(turn, terminal, code = "tool_result_limit_exceeded", observations = observations)
                                    }
                                    observations += observation
                                    if (committed.status != ToolObservationStatus.APPLIED && call.name !in AGENT_PERSISTED_WRITE_TOOLS)
                                        failedTools = true
                                    if (committed.status == ToolObservationStatus.UNKNOWN) {
                                        terminal = AgentTurnStatus.RECOVERY_REQUIRED
                                        return@withTimeout finish(turn, terminal, code = "effect_outcome_unknown", observations = observations)
                                    }
                                }
                            }
                        }
                        journal.setTurnStatus(turn.turnId, AgentTurnStatus.THINKING)
                    }
                }
                if (round == maxModelRounds - 1) {
                    terminal = AgentTurnStatus.FAILED
                    return@withTimeout finish(turn, terminal, code = "model_round_limit", observations = observations)
                }
            }
            return@withTimeout finish(turn, AgentTurnStatus.FAILED, code = "model_round_limit", observations = observations)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            terminal = if (uncertainEffect) AgentTurnStatus.RECOVERY_REQUIRED else AgentTurnStatus.FAILED
            return withContext(NonCancellable) {
                finish(turn, terminal, code = if (uncertainEffect) "effect_outcome_unknown" else "turn_timeout", observations = observations)
            }
        } catch (cancelled: CancellationException) {
            terminal = if (uncertainEffect) AgentTurnStatus.RECOVERY_REQUIRED else AgentTurnStatus.CANCELLED
            withContext(NonCancellable) {
                finish(turn, terminal, code = if (uncertainEffect) "effect_outcome_unknown" else "cancelled", observations = observations)
            }
            throw AgentTurnCancelledException(uncertainEffect).also { it.initCause(cancelled) }
        } catch (error: Exception) {
            val gatewayCode = (error as? AgentGatewayFailure)?.safeCode
                ?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
            val failureCode = gatewayCode ?: "agent_execution_failed"
            logWarning("runtime_failed stage=$failureStage type=${error.javaClass.simpleName.take(64)} code=$failureCode")
            terminal = AgentTurnStatus.FAILED
            return finish(turn, terminal, code = failureCode, observations = observations)
        }
    }

    suspend fun recoverInterruptedTurns(): List<AgentTurnJournalEntry> {
        val interrupted = journal.interruptedTurns()
        interrupted.forEach { journal.setTurnStatus(it.turnId, AgentTurnStatus.RECOVERY_REQUIRED) }
        return interrupted.map { it.copy(status = AgentTurnStatus.RECOVERY_REQUIRED, updatedAtMillis = clockMillis()) }
    }

    /** Resolves durable command outcomes by reading canonical state. Never retries an effect. */
    suspend fun recoverUnresolvedCommands(recovery: AgentCommandRecovery): List<AgentCommandJournalEntry> {
        return journal.unresolvedCommands().map { command ->
            val outcome = if (command.status == "prepared") CommandRecoveryOutcome.NOT_APPLIED
                else recovery.reconcile(command)
            when (outcome) {
                CommandRecoveryOutcome.COMMITTED -> {
                    journal.setCommandStatus(command.commandId, command.status, "committed")
                    command.copy(status = "committed")
                }
                CommandRecoveryOutcome.NOT_APPLIED -> {
                    journal.setCommandStatus(command.commandId, command.status, "rejected")
                    command.copy(status = "rejected")
                }
                CommandRecoveryOutcome.UNKNOWN -> command
            }
        }
    }

    /** Preserve verified local writes when Gemini cannot provide the next turn; never call that a full success. */
    private suspend fun finishAfterModelFailure(
        turn: AgentTurnRequest,
        observations: List<AgentToolObservation>,
        failureCode: String,
    ): AgentRunResult? {
        val writes = observations.filter { it.name in AGENT_PERSISTED_WRITE_TOOLS }
        if (writes.isEmpty()) return null
        val applied = writes.any { it.status == ToolObservationStatus.APPLIED }
        val unresolved = unresolvedAgentWriteFailures(observations)
        if (unresolved.isNotEmpty()) {
            return finish(turn, if (applied) AgentTurnStatus.PARTIAL else AgentTurnStatus.FAILED,
                answer = if (applied) partialWriteFailure(turn.locale) else writeFailure(turn.locale),
                code = "tool_write_failed", observations = observations)
        }
        if (!applied) return null
        val anotherToolFailed = observations.any {
            it.name !in AGENT_PERSISTED_WRITE_TOOLS && it.status in WRITE_FAILURES
        }
        val answer = when (turn.locale) {
            "ru-RU" -> if (anotherToolFailed)
                "Запись сохранена, но один из дополнительных шагов не выполнен. Проверьте результат."
            else "Запись сохранена, но Gemini не подтвердил, завершены ли дополнительные действия. Проверьте результат."
            else -> if (anotherToolFailed)
                "Qeyd yadda saxlanıldı, amma əlavə addımlardan biri tamamlanmadı. Nəticəni yoxlayın."
            else "Qeyd yadda saxlanıldı, amma Gemini əlavə addımların tamamlandığını təsdiqləyə bilmədi. Nəticəni yoxlayın."
        }
        val safeCode = if (anotherToolFailed) "tool_operation_failed_after_write"
            else if (failureCode == "model_timeout") "reply_timeout_after_write" else "reply_unavailable_after_write"
        return finish(turn, AgentTurnStatus.PARTIAL, answer = answer, code = safeCode, observations = observations)
    }

    private suspend fun finish(
        turn: AgentTurnRequest,
        status: AgentTurnStatus,
        answer: String? = null,
        code: String? = null,
        observations: List<AgentToolObservation> = emptyList(),
    ): AgentRunResult {
        journal.finishTurn(turn.turnId, status)
        logStage(turn.turnId, "final_answer", status.name.lowercase(), code ?: if (answer.isNullOrBlank()) "empty" else "ready")
        return AgentRunResult(turn.turnId, status.toResult(), answer, code, observations.toList())
    }

    private fun writeFailure(locale: String) = if (locale == "ru-RU")
        "Не удалось сохранить изменение. Повторите попытку."
    else "Məlumatı yadda saxlaya bilmədim. Yenidən cəhd edin."

    private fun partialWriteFailure(locale: String) = if (locale == "ru-RU")
        "Часть операции выполнена, но одно изменение не сохранилось. Проверьте запись."
    else "Əməliyyat qismən tamamlandı, amma bir dəyişiklik yadda saxlanmadı. Yazını yoxlayın."

    private fun logStage(traceId: String, stage: String, status: String, code: String) {
        val safeStage = stage.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "unknown"
        val safeStatus = status.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "unknown"
        runCatching { Log.i("MemoraAgent", "stage trace_id=$traceId name=$safeStage status=$safeStatus code=${safeCode(code)}") }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w("MemoraAgent", message) }
    }

    private fun validateTurn(turn: AgentTurnRequest) {
        require(isUuid(turn.turnId) && isUuid(turn.scopeId) && isUuid(turn.actorId))
        require(turn.sourceRevisionId == null || isUuid(turn.sourceRevisionId))
        require(turn.locale in setOf("az-AZ", "ru-RU", "en-US", "und"))
        require(turn.text.isNotBlank() && turn.text.length <= 20_000 && wellFormedUnicode(turn.text))
    }

    private fun validatePrepared(command: PreparedAgentCommand, appCommandId: String) {
        require(command.commandId == appCommandId && isUuid(command.commandId))
        require(command.payloadDigest.matches(Regex("[0-9a-f]{64}")))
        require(command.targetVersions.size <= 100 && command.targetVersions.all { isUuid(it.key) && it.value >= 0 })
    }

    private fun validCall(call: AgentModelToolCall): Boolean = call.callId.isNotBlank() && call.callId.length <= 128 &&
        call.name.matches(Regex("[a-z][a-z0-9_]{0,63}")) && call.version > 0 && call.arguments.size <= 64

    private fun validCallPayload(call: AgentModelToolCall): Boolean = validCall(call) &&
        call.arguments.keys.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) } &&
        call.arguments.values.all { safeValue(it, 0) } && runCatching { AgentCanonical.bytes(call.arguments).size <= 32_768 }.getOrDefault(false)

    /** Hash stable target identity so a later successful retry can resolve its earlier failed attempt. */
    private fun writeOperationKey(call: AgentModelToolCall): String? {
        if (call.name !in AGENT_PERSISTED_WRITE_TOOLS) return null
        val stableFields = when (call.name) {
            "correct_money_event", "reverse_money_event", "annotate_money_event" -> listOf("event_id")
            "settle_debt" -> listOf("obligation_id")
            "edit_task", "set_task_completion" -> listOf("task_id")
            "edit_memory_object", "delete_memory_object", "restore_memory_object" -> listOf("object_id")
            "update_table_row", "delete_table_row", "restore_table_row" -> listOf("table_id", "row_id")
            "rename_table", "delete_table", "restore_table" -> listOf("table_id")
            "edit_calendar_event", "cancel_calendar_event" -> listOf("event_id")
            else -> emptyList()
        }
        val stableTarget = stableFields.mapNotNull { field -> (call.arguments[field] as? String)?.takeIf(String::isNotBlank) }
        val identity: Map<String, Any?> = if (stableTarget.isNotEmpty()) {
            mapOf("tool" to call.name, "target" to stableTarget)
        } else {
            mapOf("tool" to call.name, "arguments" to call.arguments)
        }
        return runCatching { AgentCanonical.commandDigest(identity) }.getOrNull()
            ?: call.callId.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) }
    }

    private fun rejected(call: AgentModelToolCall, code: String, traceId: String): AgentToolObservation {
        val safeName = call.name.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "unknown"
        val safe = safeCode(code)
        logToolOutcome(safeName, ToolObservationStatus.REJECTED, safe, traceId)
        return AgentToolObservation(call.callId.take(128), call.name.take(64), ToolObservationStatus.REJECTED, safe,
            operationKey = writeOperationKey(call))
    }

    private fun logToolOutcome(name: String, status: ToolObservationStatus, code: String, traceId: String = "unknown") {
        val safeName = name.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "unknown"
        runCatching { Log.i("MemoraAgent", "tool_outcome trace_id=$traceId name=$safeName status=${status.name.lowercase()} code=${safeCode(code)}") }
    }

    private fun validateModelData(data: Map<String, Any?>): Map<String, Any?> {
        require(data.size <= 32 && data.keys.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) })
        data.forEach { (key, value) -> require(safeValue(value, 0)) { "Unsafe tool result field: $key" } }
        require(AgentCanonical.bytes(data).size <= 12_288)
        return data.toMap()
    }

    private fun withinResultBudget(existing: List<AgentToolObservation>, candidate: AgentToolObservation): Boolean {
        val total = (existing + candidate).sumOf { result ->
            AgentCanonical.bytes(mapOf(
                "callId" to result.callId, "name" to result.name, "status" to result.status.name,
                "code" to result.code, "modelData" to result.modelData,
            )).size
        }
        return total <= 32_768
    }

    private fun safeValue(value: Any?, depth: Int): Boolean {
        if (depth > 6) return false
        return when (value) {
            null, is Boolean, is Int, is Long -> true
            is String -> value.length <= 2_000 && wellFormedUnicode(value)
            is List<*> -> value.size <= 100 && value.all { safeValue(it, depth + 1) }
            is Map<*, *> -> value.size <= 32 && value.keys.all { it is String } && value.values.all { safeValue(it, depth + 1) }
            else -> false
        }
    }

    private fun safeCode(value: String) = value.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "tool_error"

    private fun safeOutput(output: String) = output.isNotBlank() && output.length <= 20_000 && wellFormedUnicode(output)

    private fun isUuid(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    private fun wellFormedUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            if (Character.isHighSurrogate(value[i])) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(value[i])) return false
                i++
            }
        }
        return true
    }

    private fun AgentTurnStatus.toResult() = when (this) {
        AgentTurnStatus.COMPLETED -> AgentRunResultStatus.COMPLETED
        AgentTurnStatus.PARTIAL -> AgentRunResultStatus.PARTIAL
        AgentTurnStatus.AWAITING_CONFIRMATION -> AgentRunResultStatus.AWAITING_CONFIRMATION
        AgentTurnStatus.AWAITING_CLARIFICATION -> AgentRunResultStatus.AWAITING_CLARIFICATION
        AgentTurnStatus.CANCELLED -> AgentRunResultStatus.CANCELLED
        AgentTurnStatus.RECOVERY_REQUIRED -> AgentRunResultStatus.RECOVERY_REQUIRED
        AgentTurnStatus.THINKING, AgentTurnStatus.EXECUTING, AgentTurnStatus.FAILED -> AgentRunResultStatus.FAILED
    }
}

/** UI adapter: one active job per turn, explicit cancellation, and status-only StateFlow. */
class TurnController(
    private val scope: CoroutineScope,
    private val orchestrator: AgentOrchestrator,
    private val maxActiveTurns: Int = 1,
) {
    init { require(maxActiveTurns in 1..4) }
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, AgentTurnStatus>>(emptyMap())
    val states: StateFlow<Map<String, AgentTurnStatus>> = mutableStates.asStateFlow()

    fun submit(turn: AgentTurnRequest, onResult: (AgentRunResult) -> Unit = {}, onFailure: (String) -> Unit = {}): Boolean = synchronized(lock) {
        if (jobs.containsKey(turn.turnId) || jobs.size >= maxActiveTurns) return false
        mutableStates.value = mutableStates.value + (turn.turnId to AgentTurnStatus.THINKING)
        val job = scope.launch {
            try {
                val result = orchestrator.run(turn)
                synchronized(lock) { mutableStates.value = mutableStates.value + (turn.turnId to result.status.toTurnStatus()) }
                onResult(result)
            } catch (cancelled: CancellationException) {
                val recovery = (cancelled as? AgentTurnCancelledException)?.recoveryRequired == true
                synchronized(lock) { mutableStates.value = mutableStates.value + (turn.turnId to
                    if (recovery) AgentTurnStatus.RECOVERY_REQUIRED else AgentTurnStatus.CANCELLED) }
                onFailure(if (recovery) "effect_outcome_unknown" else "cancelled")
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { mutableStates.value = mutableStates.value + (turn.turnId to AgentTurnStatus.FAILED) }
                onFailure("agent_execution_failed")
            } finally {
                synchronized(lock) { jobs.remove(turn.turnId) }
            }
        }
        jobs[turn.turnId] = job
        true
    }

    fun cancel(turnId: String): Boolean = synchronized(lock) {
        val job = jobs[turnId] ?: return@synchronized false
        if (!job.isActive) return@synchronized false
        job.cancel()
        true
    }

    private fun AgentRunResultStatus.toTurnStatus() = when (this) {
        AgentRunResultStatus.COMPLETED -> AgentTurnStatus.COMPLETED
        AgentRunResultStatus.PARTIAL -> AgentTurnStatus.PARTIAL
        AgentRunResultStatus.AWAITING_CONFIRMATION -> AgentTurnStatus.AWAITING_CONFIRMATION
        AgentRunResultStatus.AWAITING_CLARIFICATION -> AgentTurnStatus.AWAITING_CLARIFICATION
        AgentRunResultStatus.FAILED -> AgentTurnStatus.FAILED
        AgentRunResultStatus.CANCELLED -> AgentTurnStatus.CANCELLED
        AgentRunResultStatus.RECOVERY_REQUIRED -> AgentTurnStatus.RECOVERY_REQUIRED
    }
}

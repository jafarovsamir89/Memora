package ai.memora.app.agent

/** Pure P0 transition contract; persistent commit, access checks and recovery belong to P1/P2. */
enum class CommandPhase { PROPOSED, VALIDATED, AWAITING_CONFIRMATION, READY, APPLYING, RECONCILING, COMMITTED, CANCELLED, CONFLICT, REJECTED }

data class CommandLifecycleState(val phase: CommandPhase, val cancelRequested: Boolean = false)

sealed interface CommandEvent {
    data object Validate : CommandEvent
    data object RequireConfirmation : CommandEvent
    data object Ready : CommandEvent
    data object ConfirmationGranted : CommandEvent
    data object StartCommit : CommandEvent
    data object CommitConfirmed : CommandEvent
    data object OutcomeUnknown : CommandEvent
    data object Conflict : CommandEvent
    data object Reject : CommandEvent
    data object Cancel : CommandEvent
    data class Reconcile(val committed: Boolean) : CommandEvent
}

sealed interface LifecycleDecision {
    data class Changed(val state: CommandLifecycleState) : LifecycleDecision
    data class NoChange(val state: CommandLifecycleState) : LifecycleDecision
    data object InvalidTransition : LifecycleDecision
}

object CommandLifecycle {
    fun apply(state: CommandLifecycleState, event: CommandEvent): LifecycleDecision {
        val phase = state.phase
        if ((state.cancelRequested && phase !in setOf(CommandPhase.RECONCILING, CommandPhase.COMMITTED, CommandPhase.CANCELLED)) ||
            (phase == CommandPhase.CANCELLED && !state.cancelRequested)) return LifecycleDecision.InvalidTransition
        fun to(next: CommandPhase, cancelRequested: Boolean = state.cancelRequested) = LifecycleDecision.Changed(CommandLifecycleState(next, cancelRequested))
        return when (event) {
            CommandEvent.Validate -> if (phase == CommandPhase.PROPOSED) to(CommandPhase.VALIDATED) else LifecycleDecision.InvalidTransition
            CommandEvent.RequireConfirmation -> if (phase == CommandPhase.VALIDATED) to(CommandPhase.AWAITING_CONFIRMATION) else LifecycleDecision.InvalidTransition
            CommandEvent.Ready -> if (phase == CommandPhase.VALIDATED) to(CommandPhase.READY) else LifecycleDecision.InvalidTransition
            CommandEvent.ConfirmationGranted -> if (phase == CommandPhase.AWAITING_CONFIRMATION) to(CommandPhase.READY) else LifecycleDecision.InvalidTransition
            CommandEvent.StartCommit -> if (phase == CommandPhase.READY && !state.cancelRequested) to(CommandPhase.APPLYING) else LifecycleDecision.InvalidTransition
            CommandEvent.CommitConfirmed -> if (phase == CommandPhase.APPLYING) to(CommandPhase.COMMITTED) else LifecycleDecision.InvalidTransition
            CommandEvent.OutcomeUnknown -> if (phase == CommandPhase.APPLYING) to(CommandPhase.RECONCILING) else LifecycleDecision.InvalidTransition
            CommandEvent.Conflict -> if (phase in setOf(CommandPhase.VALIDATED, CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY, CommandPhase.APPLYING)) to(CommandPhase.CONFLICT) else LifecycleDecision.InvalidTransition
            CommandEvent.Reject -> if (phase in setOf(CommandPhase.PROPOSED, CommandPhase.VALIDATED, CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY)) to(CommandPhase.REJECTED) else LifecycleDecision.InvalidTransition
            CommandEvent.Cancel -> when (phase) {
                CommandPhase.PROPOSED, CommandPhase.VALIDATED, CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY -> to(CommandPhase.CANCELLED, true)
                CommandPhase.APPLYING -> to(CommandPhase.RECONCILING, true)
                CommandPhase.RECONCILING -> if (state.cancelRequested) LifecycleDecision.NoChange(state) else to(CommandPhase.RECONCILING, true)
                CommandPhase.COMMITTED, CommandPhase.CANCELLED -> LifecycleDecision.NoChange(state)
                else -> LifecycleDecision.InvalidTransition
            }
            is CommandEvent.Reconcile -> if (phase != CommandPhase.RECONCILING) LifecycleDecision.InvalidTransition
                else if (event.committed) to(CommandPhase.COMMITTED, state.cancelRequested)
                else if (state.cancelRequested) to(CommandPhase.CANCELLED, true)
                else to(CommandPhase.READY, false)
        }
    }
}

enum class ReplayCheck { REPLAY_SAME_COMMAND, IDEMPOTENCY_CONFLICT, DIFFERENT_SCOPE, INVALID }

/** This only compares trusted records; persisted results must be re-authorized before replay. */
object CommandReplay {
    private val digest = Regex("^[0-9a-f]{64}$")
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun compare(storedScope: String, storedId: String, storedDigest: String, scope: String, id: String, payloadDigest: String): ReplayCheck {
        if (!uuid.matches(storedScope) || !uuid.matches(storedId) || !uuid.matches(scope) || !uuid.matches(id) ||
            !digest.matches(storedDigest) || !digest.matches(payloadDigest)) return ReplayCheck.INVALID
        if (storedScope != scope) return ReplayCheck.DIFFERENT_SCOPE
        if (storedId != id) return ReplayCheck.INVALID
        return if (storedDigest == payloadDigest) ReplayCheck.REPLAY_SAME_COMMAND else ReplayCheck.IDEMPOTENCY_CONFLICT
    }
}

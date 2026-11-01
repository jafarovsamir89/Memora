package ai.memora.app.agent

import org.junit.Assert.*
import org.junit.Test

class CommandLifecycleTest {
    private fun change(state: CommandLifecycleState, event: CommandEvent): CommandLifecycleState =
        (CommandLifecycle.apply(state, event) as LifecycleDecision.Changed).state

    @Test fun normalConfirmationAndCommit() {
        var state = CommandLifecycleState(CommandPhase.PROPOSED)
        state = change(state, CommandEvent.Validate)
        state = change(state, CommandEvent.RequireConfirmation)
        state = change(state, CommandEvent.ConfirmationGranted)
        state = change(state, CommandEvent.StartCommit)
        state = change(state, CommandEvent.CommitConfirmed)
        assertEquals(CommandPhase.COMMITTED, state.phase)
        assertTrue(CommandLifecycle.apply(state, CommandEvent.Cancel) is LifecycleDecision.NoChange)
    }

    @Test fun cancellationBeforeCommitNeverStartsCommit() {
        for (phase in listOf(CommandPhase.PROPOSED, CommandPhase.VALIDATED, CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY)) {
            val cancelled = change(CommandLifecycleState(phase), CommandEvent.Cancel)
            assertEquals(CommandPhase.CANCELLED, cancelled.phase)
            assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(cancelled, CommandEvent.StartCommit))
            assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(cancelled, CommandEvent.CommitConfirmed))
        }
    }

    @Test fun cancellationDuringCommitRequiresRecoveryAndPreservesEitherTruth() {
        val pending = change(CommandLifecycleState(CommandPhase.APPLYING), CommandEvent.Cancel)
        assertEquals(CommandPhase.RECONCILING, pending.phase)
        assertEquals(CommandPhase.COMMITTED, change(pending, CommandEvent.Reconcile(true)).phase)
        assertEquals(CommandPhase.CANCELLED, change(pending, CommandEvent.Reconcile(false)).phase)
        assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(pending, CommandEvent.CommitConfirmed))
    }

    @Test fun unknownOutcomeRequiresReconciliationBeforeRetry() {
        val pending = change(CommandLifecycleState(CommandPhase.APPLYING), CommandEvent.OutcomeUnknown)
        assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(pending, CommandEvent.StartCommit))
        assertEquals(CommandPhase.COMMITTED, change(pending, CommandEvent.Reconcile(true)).phase)
        assertEquals(CommandPhase.READY, change(pending, CommandEvent.Reconcile(false)).phase)
    }

    @Test fun cannotSkipValidationOrPretendPreparedWasCommitted() {
        for (phase in listOf(CommandPhase.PROPOSED, CommandPhase.VALIDATED, CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY)) {
            assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(CommandLifecycleState(phase), CommandEvent.CommitConfirmed))
        }
        assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(CommandLifecycleState(CommandPhase.PROPOSED), CommandEvent.Ready))
        assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(CommandLifecycleState(CommandPhase.AWAITING_CONFIRMATION), CommandEvent.Ready))
        assertEquals(LifecycleDecision.InvalidTransition, CommandLifecycle.apply(CommandLifecycleState(CommandPhase.READY, true), CommandEvent.StartCommit))
    }

    @Test fun replayRequiresSameScopeIdAndPayload() {
        val a = "00000000-0000-4000-8000-000000000001"
        val b = "00000000-0000-4000-8000-000000000002"
        val digest = "a".repeat(64)
        assertEquals(ReplayCheck.REPLAY_SAME_COMMAND, CommandReplay.compare(a, b, digest, a, b, digest))
        assertEquals(ReplayCheck.IDEMPOTENCY_CONFLICT, CommandReplay.compare(a, b, digest, a, b, "b".repeat(64)))
        assertEquals(ReplayCheck.DIFFERENT_SCOPE, CommandReplay.compare(a, b, digest, b, b, digest))
        assertEquals(ReplayCheck.INVALID, CommandReplay.compare(a, b, digest, a, a, digest))
    }
}

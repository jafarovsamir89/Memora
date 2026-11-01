package ai.memora.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ConfirmationGateTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val scope = "00000000-0000-4000-8000-000000000002"
    private val actor = "00000000-0000-4000-8000-000000000003"
    private val target = "00000000-0000-4000-8000-000000000004"
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private val digest = "a".repeat(64)
    private val proof = ConfirmationProof(id, scope, actor, digest, mapOf(target to 5L), 7L,
        time, time.plusSeconds(600), ConfirmationState.ACTIVE)
    private val current = ConfirmationContext(scope, actor, digest, mapOf(target to 5L), 7L, time)

    @Test fun activeExactProofWorksOnlyBeforeExpiry() {
        assertEquals(ConfirmationCheck.VALID, ConfirmationGate.check(proof, current))
        assertEquals(ConfirmationCheck.VALID, ConfirmationGate.check(proof, current.copy(now = time.plusSeconds(599))))
        assertEquals(ConfirmationCheck.EXPIRED, ConfirmationGate.check(proof, current.copy(now = time.plusSeconds(600))))
        assertEquals(ConfirmationCheck.NOT_YET_VALID, ConfirmationGate.check(proof, current.copy(now = time.minusSeconds(1))))
    }

    @Test fun alteredScopeActorActionOrTargetsCannotReuseProof() {
        assertEquals(ConfirmationCheck.SCOPE_MISMATCH, ConfirmationGate.check(proof, current.copy(scopeId = id)))
        assertEquals(ConfirmationCheck.ACTOR_MISMATCH, ConfirmationGate.check(proof, current.copy(actorId = id)))
        assertEquals(ConfirmationCheck.ACTION_CHANGED, ConfirmationGate.check(proof, current.copy(actionDigest = "b".repeat(64))))
        assertEquals(ConfirmationCheck.TARGET_CHANGED, ConfirmationGate.check(proof, current.copy(targetVersions = mapOf(target to 6L))))
        assertEquals(ConfirmationCheck.TARGET_CHANGED, ConfirmationGate.check(proof, current.copy(targetVersions = emptyMap())))
    }

    @Test fun revokedConsumedAndPermissionChangesFailClosed() {
        assertEquals(ConfirmationCheck.INACTIVE, ConfirmationGate.check(proof.copy(state = ConfirmationState.REVOKED), current))
        assertEquals(ConfirmationCheck.INACTIVE, ConfirmationGate.check(proof.copy(state = ConfirmationState.CONSUMED), current))
        assertEquals(ConfirmationCheck.PERMISSION_CHANGED, ConfirmationGate.check(proof, current.copy(permissionVersion = 8L)))
    }

    @Test fun invalidProofOrOversizedLifetimeIsNeverValid() {
        assertEquals(ConfirmationCheck.INVALID_PROOF, ConfirmationGate.check(proof.copy(id = "not-uuid"), current))
        assertEquals(ConfirmationCheck.INVALID_PROOF, ConfirmationGate.check(proof.copy(expiresAt = time.plusSeconds(601)), current))
        assertEquals(ConfirmationCheck.INVALID_PROOF, ConfirmationGate.check(proof.copy(expiresAt = time), current))
        assertEquals(ConfirmationCheck.INVALID_PROOF, ConfirmationGate.check(proof.copy(actionDigest = "a"), current))
        assertEquals(ConfirmationCheck.INVALID_PROOF, ConfirmationGate.check(proof.copy(targetVersions = mapOf(target to -1L)), current))
    }
}

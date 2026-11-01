package ai.memora.app.agent

import java.time.Duration
import java.time.Instant

/**
 * Pure P0 preflight. The caller must load the proof from trusted storage and repeat
 * this check atomically with one-time consumption and commit. Not an authorization API.
 */
enum class ConfirmationState { ACTIVE, CONSUMED, REVOKED }

data class ConfirmationProof(
    val id: String,
    val scopeId: String,
    val actorId: String,
    val actionDigest: String,
    val targetVersions: Map<String, Long>,
    val permissionVersion: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val state: ConfirmationState,
)

data class ConfirmationContext(
    val scopeId: String,
    val actorId: String,
    val actionDigest: String,
    val targetVersions: Map<String, Long>,
    val permissionVersion: Long,
    val now: Instant,
)

enum class ConfirmationCheck { VALID, INVALID_PROOF, INACTIVE, EXPIRED, NOT_YET_VALID, SCOPE_MISMATCH, ACTOR_MISMATCH, ACTION_CHANGED, TARGET_CHANGED, PERMISSION_CHANGED }

object ConfirmationGate {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private const val MAX_LIFETIME_SECONDS = 600L

    fun check(proof: ConfirmationProof, current: ConfirmationContext): ConfirmationCheck {
        val duration = Duration.between(proof.issuedAt, proof.expiresAt)
        if (!uuid.matches(proof.id) || !uuid.matches(proof.scopeId) || !uuid.matches(proof.actorId) ||
            !uuid.matches(current.scopeId) || !uuid.matches(current.actorId) ||
            !sha256.matches(proof.actionDigest) || !sha256.matches(current.actionDigest) ||
            proof.permissionVersion < 0 || current.permissionVersion < 0 ||
            proof.targetVersions.size > 100 || current.targetVersions.size > 100 ||
            proof.targetVersions.any { !uuid.matches(it.key) || it.value < 0 } ||
            current.targetVersions.any { !uuid.matches(it.key) || it.value < 0 } ||
            duration.isNegative || duration.isZero || duration > Duration.ofSeconds(MAX_LIFETIME_SECONDS)) return ConfirmationCheck.INVALID_PROOF
        if (proof.state != ConfirmationState.ACTIVE) return ConfirmationCheck.INACTIVE
        if (current.now < proof.issuedAt) return ConfirmationCheck.NOT_YET_VALID
        if (current.now >= proof.expiresAt) return ConfirmationCheck.EXPIRED
        if (proof.scopeId != current.scopeId) return ConfirmationCheck.SCOPE_MISMATCH
        if (proof.actorId != current.actorId) return ConfirmationCheck.ACTOR_MISMATCH
        if (proof.actionDigest != current.actionDigest) return ConfirmationCheck.ACTION_CHANGED
        if (proof.targetVersions != current.targetVersions) return ConfirmationCheck.TARGET_CHANGED
        if (proof.permissionVersion != current.permissionVersion) return ConfirmationCheck.PERMISSION_CHANGED
        return ConfirmationCheck.VALID
    }
}

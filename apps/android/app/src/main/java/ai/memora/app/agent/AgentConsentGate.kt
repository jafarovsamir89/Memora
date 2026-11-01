package ai.memora.app.agent

/**
 * Pure design-time preflight for the future agent. The caller owns trusted storage,
 * access checks, synchronization and cancellation; this does not send or authorize data.
 */
enum class ConsentCategory { VOICE_OR_TEXT_TURN, PERSISTED_MEMORY }
enum class ConsentStatus { GRANTED, REVOKED }
enum class AgentProvider { GEMINI }
enum class CloudPayloadKind { CURRENT_TURN, MEMORY_EXCERPT }

data class ConsentRecord(
    val id: String,
    val scopeId: String,
    val provider: AgentProvider,
    val category: ConsentCategory,
    val policyVersion: Long,
    val revision: Long,
    val status: ConsentStatus,
)

data class ConsentContext(
    val scopeId: String,
    val provider: AgentProvider,
    val kind: CloudPayloadKind,
    val payloadDigest: String,
    val sessionGeneration: Long,
    val requiredPolicyVersion: Long,
    val records: List<ConsentRecord>,
)

data class ConsentRef(val id: String, val category: ConsentCategory, val revision: Long)
data class ConsentTicket(
    val scopeId: String,
    val provider: AgentProvider,
    val kind: CloudPayloadKind,
    val payloadDigest: String,
    val sessionGeneration: Long,
    val policyVersion: Long,
    val records: Set<ConsentRef>,
)

sealed interface ConsentDecision {
    data class Allowed(val ticket: ConsentTicket) : ConsentDecision
    data object Invalid : ConsentDecision
    data object MissingOrRevoked : ConsentDecision
    data object Changed : ConsentDecision
}

object AgentConsentGate {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val digest = Regex("^[0-9a-f]{64}$")

    fun prepare(context: ConsentContext): ConsentDecision {
        if (!uuid.matches(context.scopeId) || !digest.matches(context.payloadDigest) || context.sessionGeneration < 0 ||
            context.requiredPolicyVersion < 0 || context.records.size > 2 ||
            context.records.any { !uuid.matches(it.id) || !uuid.matches(it.scopeId) || it.revision < 0 || it.policyVersion < 0 } ||
            context.records.map { it.id }.toSet().size != context.records.size ||
            context.records.map { it.category }.toSet().size != context.records.size) return ConsentDecision.Invalid
        val required = when (context.kind) {
            CloudPayloadKind.CURRENT_TURN -> setOf(ConsentCategory.VOICE_OR_TEXT_TURN)
            CloudPayloadKind.MEMORY_EXCERPT -> setOf(ConsentCategory.VOICE_OR_TEXT_TURN, ConsentCategory.PERSISTED_MEMORY)
        }
        if (context.records.map { it.category }.toSet() != required || context.records.any {
                it.scopeId != context.scopeId || it.provider != context.provider || it.policyVersion != context.requiredPolicyVersion || it.status != ConsentStatus.GRANTED
            }) return ConsentDecision.MissingOrRevoked
        return ConsentDecision.Allowed(ConsentTicket(context.scopeId, context.provider, context.kind, context.payloadDigest,
            context.sessionGeneration, context.requiredPolicyVersion,
            context.records.map { ConsentRef(it.id, it.category, it.revision) }.toSet()))
    }

    /** Re-read records and generation immediately before each send; stale tickets never pass. */
    fun recheck(ticket: ConsentTicket, current: ConsentContext): ConsentDecision {
        val fresh = prepare(current)
        if (fresh !is ConsentDecision.Allowed) return fresh
        return if (fresh.ticket == ticket) fresh else ConsentDecision.Changed
    }
}

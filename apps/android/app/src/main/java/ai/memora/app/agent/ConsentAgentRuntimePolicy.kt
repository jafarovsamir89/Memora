package ai.memora.app.agent

internal object AgentCloudConsentKeys {
    const val VOICE = "google-agent-audio-and-answer-v2"
    const val TEXT = "gemini-agent-text-and-answer-v2"
}

/** Re-checks cloud and persisted-memory permissions at every boundary of a turn. */
internal class ConsentAgentRuntimePolicy(
    private val cloudConsent: (AgentInputChannel) -> Boolean,
    private val memoryConsent: () -> Boolean,
) : AgentRuntimePolicy {
    override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean): PolicyDecision = when {
        !cloudConsent(turn.channel) -> PolicyDecision(PolicyAction.DENY, "cloud_consent_required")
        includesStoredMemory && !memoryConsent() -> PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        else -> PolicyDecision(PolicyAction.ALLOW, "allowed")
    }

    override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor): PolicyDecision = when {
        descriptor.access == ToolAccess.PERSISTED_MEMORY && !memoryConsent() ->
            PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        descriptor.access == ToolAccess.EXTERNAL -> PolicyDecision(PolicyAction.DENY, "external_action_not_enabled")
        else -> PolicyDecision(PolicyAction.ALLOW, "allowed")
    }

    override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand): PolicyDecision =
        if (memoryConsent()) PolicyDecision(PolicyAction.ALLOW, "allowed")
        else PolicyDecision(PolicyAction.DENY, "memory_consent_required")

    override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand): Boolean = false
}

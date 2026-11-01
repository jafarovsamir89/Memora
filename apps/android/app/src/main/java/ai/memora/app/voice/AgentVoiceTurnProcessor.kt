package ai.memora.app.voice

import android.content.Context
import ai.memora.app.agent.*
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import java.util.UUID

/** Routes one recognized utterance through Gemini function calling and the encrypted local domain store. */
internal class AgentVoiceTurnProcessor(context: Context) : VoiceTurnProcessor {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
    private val database by lazy { AgentDatabase.open(appContext) }
    private val policy = object : AgentRuntimePolicy {
        private fun cloudTurnAllowed() = preferences.getBoolean("google-audio-and-answer-v1", false)
        private fun memoryAllowed() = preferences.getBoolean("gemini-persisted-memory-v1", false)
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) =
            if (cloudTurnAllowed() && (!includesStoredMemory || memoryAllowed())) PolicyDecision(PolicyAction.ALLOW, "allowed")
            else PolicyDecision(PolicyAction.DENY, if (includesStoredMemory) "memory_consent_required" else "cloud_consent_required")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) =
            if (descriptor.access == ToolAccess.PERSISTED_MEMORY && memoryAllowed()) PolicyDecision(PolicyAction.ALLOW, "allowed")
            else PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) =
            if (memoryAllowed()) PolicyDecision(PolicyAction.ALLOW, "allowed") else PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = false
    }

    override suspend fun handle(transcript: String): VoiceFirstResult {
        if (transcript.isBlank() || transcript.length > 20_000) return VoiceFirstResult.Ready("Zəhmət olmasa, bir daha deyin.", false)
        return try {
            val scope = stableId("scope-id")
            val actor = stableId("actor-id")
            val repo = ProtectedDomainRepository(database)
            val handlers = if (preferences.getBoolean("gemini-persisted-memory-v1", false))
                LocalMemoryTools(repo, ProtectedMemoryRepository(database)).handlers() else emptyList()
            val orchestrator = AgentOrchestrator(GeminiAgentModelGateway(), DefaultAgentToolRegistry(handlers), policy,
                RoomAgentRunJournal(database))
            val result = orchestrator.run(AgentTurnRequest(UUID.randomUUID().toString(), scope, actor, null,
                locale = "az-AZ", text = transcript))
            when (result.status) {
                AgentRunResultStatus.COMPLETED, AgentRunResultStatus.PARTIAL -> result.answer?.let {
                    VoiceFirstResult.Ready(it, result.toolResults.any { tool -> tool.status == ToolObservationStatus.APPLIED && tool.name == "save_note" })
                } ?: VoiceFirstResult.Unavailable
                AgentRunResultStatus.AWAITING_CLARIFICATION -> VoiceFirstResult.Ready(result.answer ?: "Zəhmət olmasa, istəyinizi dəqiqləşdirin.", false)
                AgentRunResultStatus.AWAITING_CONFIRMATION -> VoiceFirstResult.Ready("Bu əməliyyat üçün təsdiq lazımdır.", false)
                else -> VoiceFirstResult.Unavailable
            }
        } catch (_: Exception) {
            VoiceFirstResult.Unavailable
        }
    }

    private fun stableId(key: String): String {
        val existing = preferences.getString(key, null)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        check(preferences.edit().putString(key, created).commit())
        return created
    }
}

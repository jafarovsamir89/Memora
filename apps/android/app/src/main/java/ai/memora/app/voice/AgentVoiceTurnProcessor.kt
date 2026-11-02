package ai.memora.app.voice

import android.content.Context
import android.util.Log
import ai.memora.app.agent.*
import ai.memora.app.data.VoiceMoneyKey
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import ai.memora.app.reminders.TaskReminders
import java.io.File
import java.time.Instant
import java.util.UUID

/** Routes one recognized utterance through Gemini function calling and the encrypted local domain store. */
internal class AgentVoiceTurnProcessor(context: Context) : VoiceTurnProcessor {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
    private val financeStore = VoiceMoneyStore(File(appContext.filesDir, "voice-memory.bin"), VoiceMoneyKey::get)
    private val database by lazy { AgentDatabase.open(appContext) }
    private val conversations by lazy { ProtectedConversationRepository(database) }
    private val domainRepository by lazy { ProtectedDomainRepository(database) }
    private val scopeId by lazy { stableId("scope-id") }
    private val actorId by lazy { stableId("actor-id") }
    private val policy = object : AgentRuntimePolicy {
        private fun cloudTurnAllowed(turn: AgentTurnRequest) = when (turn.channel) {
            AgentInputChannel.VOICE -> preferences.getBoolean("google-audio-and-answer-v1", false)
            AgentInputChannel.TEXT -> preferences.getBoolean("gemini-text-and-answer-v1", false)
        }
        private fun memoryAllowed() = preferences.getBoolean("gemini-persisted-memory-v1", false)
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) =
            if (cloudTurnAllowed(turn) && (!includesStoredMemory || memoryAllowed())) PolicyDecision(PolicyAction.ALLOW, "allowed")
            else PolicyDecision(PolicyAction.DENY, if (includesStoredMemory) "memory_consent_required" else "cloud_consent_required")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) =
            if (descriptor.access == ToolAccess.PERSISTED_MEMORY && memoryAllowed()) PolicyDecision(PolicyAction.ALLOW, "allowed")
            else PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) =
            if (memoryAllowed()) PolicyDecision(PolicyAction.ALLOW, "allowed") else PolicyDecision(PolicyAction.DENY, "memory_consent_required")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = false
    }

    override suspend fun handle(transcript: String): VoiceFirstResult = process(transcript, AgentInputChannel.VOICE)

    suspend fun handleText(message: String): VoiceFirstResult = process(message, AgentInputChannel.TEXT)
    fun conversationScopeId(): String = scopeId
    suspend fun conversationMessages(limit: Int = 80) = conversations.recent(scopeId, limit)
    suspend fun migrateLegacyFinance(): Boolean = runCatching {
        if (domainRepository.hasVerifiedLegacyFinance(scopeId)) return@runCatching true
        val snapshot = financeStore.migrationSnapshot()
        val file = File(appContext.filesDir, "voice-memory.bin")
        val digest = if (!file.exists()) ai.memora.app.data.protectedstore.sha256(ByteArray(0)) else {
            val ciphertext = file.readBytes()
            try { ai.memora.app.data.protectedstore.sha256(ciphertext) } finally { ciphertext.fill(0) }
        }
        domainRepository.migrateLegacyFinance(scopeId, snapshot.snapshot, digest, snapshot.sourceSchema)
    }.isSuccess

    private suspend fun process(message: String, channel: AgentInputChannel): VoiceFirstResult {
        if (message.isBlank() || message.length > 20_000) return VoiceFirstResult.Ready("Zəhmət olmasa, bir daha deyin.", false)
        return try {
            val history = if (preferences.getBoolean("gemini-persisted-memory-v1", false))
                conversations.recentContext(scopeId, 12).mapNotNull { row ->
                    row.message.takeLast(4_000).takeIf(String::isNotBlank)?.let { AgentDialogueMessage(row.role, it) }
                }
            else emptyList()
            val repo = domainRepository
            val handlers = if (preferences.getBoolean("gemini-persisted-memory-v1", false))
                LocalMemoryTools(repo, ProtectedMemoryRepository(database), financeEnabled = migrateLegacyFinance(),
                    onTaskChanged = { task -> TaskReminders.schedule(appContext, task).name.lowercase() },
                    conversations = conversations).handlers() else emptyList()
            val orchestrator = AgentOrchestrator(GeminiAgentModelGateway(), DefaultAgentToolRegistry(handlers), policy,
                RoomAgentRunJournal(database))
            val turnId = UUID.randomUUID().toString()
            val result = orchestrator.run(AgentTurnRequest(turnId, scopeId, actorId, null,
                locale = "az-AZ", text = message, channel = channel, conversationHistory = history))
            if (result.status == AgentRunResultStatus.FAILED || result.status == AgentRunResultStatus.RECOVERY_REQUIRED) {
                Log.w("MemoraAgent", "turn_unavailable status=${result.status.name.lowercase()} code=${result.code ?: "unknown"}")
            }
            when (result.status) {
                AgentRunResultStatus.COMPLETED, AgentRunResultStatus.PARTIAL -> result.answer?.let {
                    conversations.appendTurn(scopeId, turnId, message, it, Instant.now().toEpochMilli())
                    VoiceFirstResult.Ready(it, result.toolResults.any { tool -> tool.status == ToolObservationStatus.APPLIED &&
                        tool.name in setOf("save_note", "record_money_event", "create_table", "add_table_row", "update_table_row",
                            "delete_table_row", "restore_table_row", "rename_table", "delete_table", "restore_table",
                            "create_task", "set_task_completion", "create_calendar_event", "edit_calendar_event", "cancel_calendar_event",
                            "link_objects", "edit_memory_object",
                            "delete_memory_object", "restore_memory_object") })
                } ?: VoiceFirstResult.Unavailable
                AgentRunResultStatus.AWAITING_CLARIFICATION -> (result.answer ?: "Zəhmət olmasa, istəyinizi dəqiqləşdirin.").let {
                    conversations.appendTurn(scopeId, turnId, message, it, Instant.now().toEpochMilli())
                    VoiceFirstResult.Ready(it, false)
                }
                AgentRunResultStatus.AWAITING_CONFIRMATION -> VoiceFirstResult.Ready("Bu əməliyyat üçün təsdiq lazımdır.", false)
                else -> VoiceFirstResult.Unavailable
            }
        } catch (error: Exception) {
            Log.w("MemoraAgent", "turn_exception type=${error.javaClass.simpleName.take(64)}")
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

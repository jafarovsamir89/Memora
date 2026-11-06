package ai.memora.app.voice

import android.content.Context
import android.util.Log
import ai.memora.app.agent.*
import ai.memora.app.data.ProtectedLocalProfileStore
import ai.memora.app.data.VoiceMoneyKey
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.data.GeminiAgentModelSelectionStore
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import ai.memora.app.reminders.TaskReminders
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Routes one recognized utterance through Gemini function calling and the encrypted local domain store. */
internal class AgentVoiceTurnProcessor(context: Context, private val databaseOverride: AgentDatabase? = null) : VoiceTurnProcessor {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
    private val identityPreferences = appContext.getSharedPreferences("agent-runtime-identity", Context.MODE_PRIVATE)
    private val financeStore = VoiceMoneyStore(File(appContext.filesDir, "voice-memory.bin"), VoiceMoneyKey::get)
    private val database by lazy { databaseOverride ?: AgentDatabase.open(appContext) }
    private val conversations by lazy { ProtectedConversationRepository(database) }
    private val domainRepository by lazy { ProtectedDomainRepository(database) }
    private val scopeId by lazy { stableId("scope-id") }
    private val actorId by lazy { stableId("actor-id") }
    private val policy = ConsentAgentRuntimePolicy(
        cloudConsent = { channel -> when (channel) {
            AgentInputChannel.VOICE -> preferences.getBoolean(AgentCloudConsentKeys.VOICE, false)
            AgentInputChannel.TEXT -> preferences.getBoolean(AgentCloudConsentKeys.TEXT, false)
        } },
        memoryConsent = { preferences.getBoolean("gemini-persisted-memory-v1", false) },
    )

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
            // Retention is a local user setting; apply it even when cloud-memory consent is disabled.
            conversations.applyRetention(scopeId)
            val history = if (preferences.getBoolean("gemini-persisted-memory-v1", false))
                conversations.recentContext(scopeId, 12).mapNotNull { row ->
                    row.message.takeLast(4_000).takeIf(String::isNotBlank)?.let { AgentDialogueMessage(row.role, it) }
                }
            else emptyList()
            val repo = domainRepository
            val profile = ProtectedLocalProfileStore(database).read()
            val responsePreferences = profile?.takeIf { it.onboardingCompleted }?.let {
                AgentResponsePreferences(it.language, it.toneStyle, it.responseStyle)
            }
            val handlers = if (preferences.getBoolean("gemini-persisted-memory-v1", false))
                LocalMemoryTools(repo, ProtectedMemoryRepository(database), financeEnabled = migrateLegacyFinance(),
                    onTaskChanged = { task -> TaskReminders.schedule(appContext, task).code() },
                    conversations = conversations, profileMemory = ProtectedProfileMemoryRepository(database),
                    personAliases = ai.memora.app.data.protectedstore.PersonAliasRepository(database),
                    userTimeZoneId = profile?.timeZoneId).handlers() else emptyList()
            val selectedModel = GeminiAgentModelSelectionStore(appContext).selectedModel()
            val orchestrator = AgentOrchestrator(GeminiAgentModelGateway(selectedModel), DefaultAgentToolRegistry(handlers), policy,
                RoomAgentRunJournal(database))
            val turnId = UUID.randomUUID().toString()
            val result = orchestrator.run(AgentTurnRequest(turnId, scopeId, actorId, null,
                locale = responsePreferences?.language?.let(::languageTag) ?: "az-AZ",
                text = message, channel = channel, conversationHistory = history,
                responsePreferences = responsePreferences))
            val completedAt = Instant.now().toEpochMilli()
            val actionResults = AgentActionResultMapper.map(scopeId, turnId, result.toolResults, completedAt)
            if (result.status == AgentRunResultStatus.FAILED || result.status == AgentRunResultStatus.RECOVERY_REQUIRED) {
                Log.w("MemoraAgent", "turn_unavailable status=${result.status.name.lowercase()} code=${result.code ?: "unknown"}")
            }
            when (result.status) {
                AgentRunResultStatus.COMPLETED, AgentRunResultStatus.PARTIAL -> result.answer?.let {
                    conversations.appendTurn(scopeId, turnId, message, it, completedAt, actionResults)
                    VoiceFirstResult.Ready(it, result.toolResults.any { tool ->
                        tool.status == ToolObservationStatus.APPLIED && tool.name in AGENT_PERSISTED_WRITE_TOOLS
                    })
                } ?: VoiceFirstResult.Unavailable
                AgentRunResultStatus.AWAITING_CLARIFICATION -> (result.answer ?: "Zəhmət olmasa, istəyinizi dəqiqləşdirin.").let {
                    conversations.appendTurn(scopeId, turnId, message, it, completedAt, actionResults)
                    VoiceFirstResult.Ready(it, false)
                }
                AgentRunResultStatus.AWAITING_CONFIRMATION -> {
                    val answer = awaitingConfirmation(responsePreferences?.language)
                    conversations.appendTurn(scopeId, turnId, message, answer, completedAt, actionResults)
                    VoiceFirstResult.Ready(answer, false)
                }
                AgentRunResultStatus.FAILED, AgentRunResultStatus.RECOVERY_REQUIRED -> {
                    if (actionResults.isEmpty()) VoiceFirstResult.Failed(safeFailureCode(result.code))
                    else {
                        val answer = if (result.status == AgentRunResultStatus.RECOVERY_REQUIRED)
                            recoveryAnswer(responsePreferences?.language) else failureAnswer(responsePreferences?.language)
                        conversations.appendTurn(scopeId, turnId, message, answer, completedAt, actionResults)
                        VoiceFirstResult.Ready(answer, false)
                    }
                }
                AgentRunResultStatus.CANCELLED -> VoiceFirstResult.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("MemoraAgent", "turn_exception type=${error.javaClass.simpleName.take(64)}")
            val gatewayCode = (error as? AgentGatewayFailure)?.safeCode
            if (gatewayCode == null) VoiceFirstResult.Unavailable
            else VoiceFirstResult.Failed(safeFailureCode(gatewayCode))
        }
    }

    private fun safeFailureCode(code: String?): String = code
        ?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
        ?: "request_failed"

    private fun stableId(key: String): String {
        return synchronized(identityLock) {
            val existing = identityPreferences.getString(key, null)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            if (existing != null) return@synchronized existing

            // Preserve IDs written by earlier versions before consent and identity had separate stores.
            val legacy = preferences.getString(key, null)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            if (legacy != null) {
                check(identityPreferences.edit().putString(key, legacy).commit())
                preferences.edit().remove(key).commit()
                return@synchronized legacy
            }

            UUID.randomUUID().toString().also { created ->
                check(identityPreferences.edit().putString(key, created).commit())
            }
        }
    }

    private fun languageTag(language: String): String = when (language) {
        "ru" -> "ru-RU"
        "en" -> "en-US"
        else -> "az-AZ"
    }

    private fun awaitingConfirmation(language: String?) = when (language) {
        "ru" -> "Это действие не выполнено: требуется подтверждение. Проверьте карточку результата перед продолжением."
        "en" -> "This action was not applied because it needs confirmation. Review its result card before continuing."
        else -> "Bu əməl təsdiq tələb etdiyi üçün icra olunmadı. Davam etməzdən əvvəl nəticə kartına baxın."
    }

    private fun failureAnswer(language: String?) = when (language) {
        "ru" -> "Не удалось выполнить изменение. Подробный статус показан в карточке действия."
        "en" -> "The change could not be completed. Its status is shown in the action card."
        else -> "Dəyişikliyi tamamlamaq mümkün olmadı. Əməliyyatın vəziyyəti nəticə kartında göstərilir."
    }

    private fun recoveryAnswer(language: String?) = when (language) {
        "ru" -> "Не удалось подтвердить, завершилась ли операция. Не повторяйте её, пока мы не проверим запись."
        "en" -> "I could not confirm whether the operation finished. Do not repeat it until we verify the record."
        else -> "Əməliyyatın tamamlandığını təsdiqləyə bilmədim. Qeydi yoxlamadan onu təkrarlamayın."
    }

    private companion object {
        val identityLock = Any()
    }
}

/** Keeps only scoped write status, a safe code and app-owned target IDs for local result cards. */
internal object AgentActionResultMapper {
    fun map(scope: String, turn: String, observations: List<AgentToolObservation>, at: Long): List<AgentActionResultRow> {
        require(scope.isNotBlank() && turn.isNotBlank() && at >= 0)
        val latest = observations.withIndex().filter { it.value.name in AGENT_PERSISTED_WRITE_TOOLS }
            .groupBy { (_, observation) -> observation.name to (observation.operationKey ?: observation.callId) }
            .values.mapNotNull { group -> group.maxByOrNull { it.index } }
            .sortedBy { it.index }
        return latest.mapIndexed { index, (_, observation) ->
            val target = actionTarget(observation)
            AgentActionResultRow(
                resultId = UUID.nameUUIDFromBytes("$turn:${observation.name}:$index".toByteArray(Charsets.UTF_8)).toString(),
                scopeId = scope,
                turnId = turn,
                toolName = observation.name,
                status = observation.status.name,
                code = observation.code.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "result_unavailable",
                targetKind = target.first,
                targetId = target.second,
                createdAtMillis = at,
            )
        }
    }

    private fun actionTarget(observation: AgentToolObservation): Pair<String?, String?> {
        val (kind, keys) = when (observation.name) {
            "save_note", "save_memory_episode", "delete_memory_episode", "edit_memory_object", "delete_memory_object", "restore_memory_object" ->
                "memory" to listOf("object_id", "episode_id", "id")
            "save_profile_fact", "edit_profile_fact", "delete_profile_fact" -> "memory" to listOf("fact_id", "id")
            "record_money_event", "correct_money_event", "reverse_money_event", "annotate_money_event" -> "finance" to listOf("event_id", "id")
            "settle_debt" -> "finance" to listOf("payment_event_id", "obligation_id")
            "create_case", "update_case", "add_case_participant", "link_case_source" -> "case" to listOf("case_id", "id")
            "create_task", "edit_task", "set_task_completion" -> "task" to listOf("id", "task_id")
            "create_calendar_event", "edit_calendar_event", "cancel_calendar_event" -> "calendar" to listOf("id", "event_id")
            "create_table", "rename_table", "delete_table", "restore_table", "add_table_row", "update_table_row",
            "delete_table_row", "restore_table_row" -> "table" to listOf("table_id", "id")
            else -> null to emptyList()
        }
        val id = keys.asSequence().mapNotNull { observation.modelData[it] as? String }
            .firstOrNull { value -> runCatching { UUID.fromString(value) }.isSuccess }
        return kind to id
    }
}

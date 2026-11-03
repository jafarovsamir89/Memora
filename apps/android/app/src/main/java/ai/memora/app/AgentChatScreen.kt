package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.agent.AgentCloudConsentKeys
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstScreen
import ai.memora.app.voice.VoiceFirstResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Text chat and push-to-talk share the same agent, encrypted memory, and bounded dialogue context. */
@Composable
fun AgentChatScreen(language: String = "az", showVoiceControl: Boolean = false, onOpenArea: (String) -> Unit = {},
    textTurnOverride: (suspend (String) -> VoiceFirstResult)? = null) {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val repository = remember(database) { ProtectedConversationRepository(database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val coroutineScope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<AgentConversationMessageRow>>(emptyList()) }
    var actionResults by remember { mutableStateOf<Map<String, List<AgentActionResultRow>>>(emptyMap()) }
    var sourceTurnId by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var correctionDraftPrepared by rememberSaveable { mutableStateOf(false) }
    var pendingMessage by rememberSaveable { mutableStateOf("") }
    var textConsent by remember { mutableStateOf(settings.getBoolean(AgentCloudConsentKeys.TEXT, false)) }
    var memoryConsent by remember { mutableStateOf(settings.getBoolean("gemini-persisted-memory-v1", false)) }
    var textConsentDialog by remember { mutableStateOf(false) }
    var memoryConsentDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failureCode by remember { mutableStateOf<String?>(null) }
    var draftRestoredAfterFailure by rememberSaveable { mutableStateOf(false) }

    suspend fun refreshMessages() {
        val recent = repository.recent(scopeId, 80)
        messages = recent
        actionResults = repository.actionResultsForTurns(scopeId, recent.map { it.turnId }.distinct())
    }
    LaunchedEffect(scopeId) { refreshMessages() }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || busy) return
        if (!textConsent) {
            pendingMessage = trimmed
            textConsentDialog = true
            return
        }
        input = ""
        correctionDraftPrepared = false
        failed = false
        failureCode = null
        draftRestoredAfterFailure = false
        busy = true
        coroutineScope.launch {
            try {
                val result = withTimeout(65_000) { textTurnOverride?.invoke(trimmed) ?: processor.handleText(trimmed) }
                when (result) {
                    is VoiceFirstResult.Ready -> refreshMessages()
                    is VoiceFirstResult.Failed -> {
                        input = trimmed
                        draftRestoredAfterFailure = true
                        failed = true
                        failureCode = result.code
                    }
                    VoiceFirstResult.Unavailable -> {
                        input = trimmed
                        draftRestoredAfterFailure = true
                        failed = true
                        failureCode = "request_failed"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                input = trimmed
                draftRestoredAfterFailure = true
                failed = true
                failureCode = "request_failed"
            } finally {
                busy = false
            }
        }
    }

    if (textConsentDialog) AlertDialog(
        onDismissRequest = { textConsentDialog = false; pendingMessage = "" },
        title = { Text(s(ai.memora.app.R.string.ai_consent_title)) },
        text = { Text(s(ai.memora.app.R.string.ai_consent_body) + "\n\n" + pendingMessage) },
        confirmButton = { TextButton(onClick = {
            val message = pendingMessage
            textConsentDialog = false
            pendingMessage = ""
            if (settings.edit().putBoolean(AgentCloudConsentKeys.TEXT, true).commit()) {
                textConsent = true
                send(message)
            } else failed = true
        }, modifier = Modifier.testTag("agent_text_consent_confirm")) { Text(s(ai.memora.app.R.string.ai_send_confirm)) } },
        dismissButton = { TextButton(onClick = { textConsentDialog = false; pendingMessage = "" }) { Text(s(ai.memora.app.R.string.task_cancel)) } },
    )

    if (memoryConsentDialog) AlertDialog(
        onDismissRequest = { memoryConsentDialog = false },
        title = { Text(s(ai.memora.app.R.string.agent_memory_consent_title)) },
        text = { Text(s(ai.memora.app.R.string.agent_memory_consent_body)) },
        confirmButton = { TextButton(onClick = {
            memoryConsentDialog = false
            if (settings.edit().putBoolean("gemini-persisted-memory-v1", true).commit()) memoryConsent = true
            else failed = true
        }) { Text(s(ai.memora.app.R.string.agent_memory_allow)) } },
        dismissButton = { TextButton(onClick = { memoryConsentDialog = false }) { Text(s(ai.memora.app.R.string.task_cancel)) } },
    )

    if (clearDialog) AlertDialog(
        onDismissRequest = { clearDialog = false },
        title = { Text(s(ai.memora.app.R.string.agent_chat_clear)) },
        text = { Text(s(ai.memora.app.R.string.agent_chat_clear_confirmation)) },
        confirmButton = { TextButton(onClick = {
            clearDialog = false
            coroutineScope.launch { repository.clear(scopeId); refreshMessages() }
        }, modifier = Modifier.testTag("agent_chat_clear_confirm")) { Text(s(ai.memora.app.R.string.agent_chat_clear_yes)) } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text(s(ai.memora.app.R.string.task_cancel)) } },
    )

    sourceTurnId?.let { turnId ->
        val source = messages.filter { it.turnId == turnId }
        AlertDialog(
            onDismissRequest = { sourceTurnId = null },
            title = { Text(s(ai.memora.app.R.string.action_source_title)) },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    source.firstOrNull { it.role == "user" }?.let {
                        Text(s(ai.memora.app.R.string.chat_speaker_user), color = colors.accent)
                        Text(it.message, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp)
                            .testTag("action_source_user_message"))
                    }
                    source.firstOrNull { it.role == "model" }?.let {
                        Text(s(ai.memora.app.R.string.chat_speaker_secretary), color = colors.accent)
                        Text(it.message, color = colors.ink, modifier = Modifier.testTag("action_source_assistant_message"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sourceTurnId = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }

    ScreenColumn {
        Text(s(ai.memora.app.R.string.chat_title), Modifier.fillMaxWidth(), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = colors.ink, textAlign = TextAlign.Center)
        Text(s(ai.memora.app.R.string.agent_chat_intro), Modifier.fillMaxWidth(), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.muted, textAlign = TextAlign.Center)
        if (showVoiceControl) MemoraCard(tint = colors.hero, modifier = Modifier.testTag("secretary_voice_card")) {
            Text(s(ai.memora.app.R.string.secretary_voice_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = colors.ink)
            Text(s(ai.memora.app.R.string.secretary_voice_hint), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.muted)
            VoiceFirstScreen()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (memoryConsent) {
                    settings.edit().putBoolean("gemini-persisted-memory-v1", false).commit()
                    memoryConsent = false
                } else memoryConsentDialog = true
            }, modifier = Modifier.weight(1f).testTag("agent_memory_consent_toggle")) {
                Text(s(if (memoryConsent) ai.memora.app.R.string.agent_memory_revoke else ai.memora.app.R.string.agent_memory_allow))
            }
            TextButton(onClick = { clearDialog = true }, enabled = messages.isNotEmpty(), modifier = Modifier.testTag("agent_chat_clear")) {
                Text(s(ai.memora.app.R.string.agent_chat_clear))
            }
        }
        if (messages.isEmpty()) Text(s(ai.memora.app.R.string.agent_chat_empty), Modifier.fillMaxWidth().padding(vertical = 20.dp),
            color = colors.muted, textAlign = TextAlign.Center)
        messages.forEach { message ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement =
                if (message.role == "user") Arrangement.End else Arrangement.Start) {
                MemoraCard(tint = if (message.role == "user") colors.softAccent else colors.card,
                    modifier = Modifier.fillMaxWidth(.88f).testTag("chat_turn_${message.turnId}_${message.role}")) {
                    Text(s(if (message.role == "user") ai.memora.app.R.string.chat_speaker_user else ai.memora.app.R.string.chat_speaker_secretary),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.accent)
                    Text(message.message, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                }
            }
            if (message.role == "model") actionResults[message.turnId].orEmpty().forEachIndexed { index, result ->
                ActionResultCard(
                    result = result,
                    onOpenArea = { route -> onOpenArea(route) },
                    onOpenSource = { sourceTurnId = message.turnId },
                sourceMessage = messages.firstOrNull { it.turnId == message.turnId && it.role == "user" }?.message.orEmpty(),
                onPrepareCorrection = { prompt -> input = prompt.take(20_000); correctionDraftPrepared = true },
                    modifier = Modifier.testTag("action_result_${message.turnId}_$index"),
                )
            }
        }
        if (busy) MemoraCard { Text(s(ai.memora.app.R.string.ai_loading), color = colors.muted) }
        if (failed) MemoraCard {
            val message = failureCode?.let { LocalStrings.current.getString(ai.memora.app.R.string.ai_failed_code, it) }
                ?: s(ai.memora.app.R.string.ai_failed)
            Text(message, color = colors.muted)
            if (draftRestoredAfterFailure) Text(s(ai.memora.app.R.string.agent_failed_draft_kept),
                color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("agent_failed_draft_note"))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20_000) {
                input = it
                if (draftRestoredAfterFailure) { draftRestoredAfterFailure = false; failed = false; failureCode = null }
            } }, Modifier.weight(1f).testTag("chat_input"),
                label = { Text(s(ai.memora.app.R.string.question)) }, shape = RoundedCornerShape(20.dp), maxLines = 4,
                enabled = !busy)
            FilledIconButton({ send(input) }, Modifier.testTag("chat_send"), enabled = input.isNotBlank() && !busy) {
                Icon(Glyph.Arrow, s(ai.memora.app.R.string.send))
            }
        }
        if (correctionDraftPrepared) Text(s(ai.memora.app.R.string.action_correction_draft_note),
            color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("action_correction_draft_note"))
        if (textConsent) TextButton(onClick = {
            if (settings.edit().putBoolean(AgentCloudConsentKeys.TEXT, false).commit()) textConsent = false
            else failed = true
        }, modifier = Modifier.testTag("agent_text_consent_toggle")) { Text(s(ai.memora.app.R.string.agent_text_revoke)) }
    }
}

@Composable
private fun ActionResultCard(result: AgentActionResultRow, onOpenArea: (String) -> Unit,
    onOpenSource: () -> Unit, sourceMessage: String, onPrepareCorrection: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val statusLabel = when (result.status) {
        "APPLIED" -> ai.memora.app.R.string.action_status_applied
        "NEEDS_CONFIRMATION" -> ai.memora.app.R.string.action_status_confirmation
        "UNKNOWN" -> ai.memora.app.R.string.action_status_unknown
        else -> ai.memora.app.R.string.action_status_failed
    }
    val statusColor = when (result.status) {
        "APPLIED" -> colors.green
        "NEEDS_CONFIRMATION", "UNKNOWN" -> colors.amber
        else -> androidx.compose.material3.MaterialTheme.colorScheme.error
    }
    val areaLabel = when (result.targetKind) {
        "finance" -> ai.memora.app.R.string.action_area_finance
        "case" -> ai.memora.app.R.string.action_area_cases
        "task" -> ai.memora.app.R.string.action_area_tasks
        "calendar" -> ai.memora.app.R.string.action_area_calendar
        "table" -> ai.memora.app.R.string.action_area_tables
        "memory" -> ai.memora.app.R.string.action_area_memory
        else -> null
    }
    val areaRoute = when (result.targetKind) {
        "finance" -> "finance"
        "case" -> "cases"
        "task" -> "tasks"
        "calendar" -> "calendar"
        "table" -> "tables"
        "memory" -> "memory"
        else -> null
    }
    val correctionTemplate = s(ai.memora.app.R.string.action_correction_draft_template)
    val targetId = result.targetId
    MemoraCard(tint = if (result.status == "APPLIED") colors.softAccent else colors.card,
        modifier = modifier.testTag("action_card_${result.status.lowercase()}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (result.status == "APPLIED") Glyph.Check else Glyph.Folder, null, tint = statusColor)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(s(ai.memora.app.R.string.action_result_title), style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.muted)
                Text(s(statusLabel), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = statusColor, modifier = Modifier.testTag("action_status_label"))
            }
        }
        if (result.status == "NEEDS_CONFIRMATION")
            Text(s(ai.memora.app.R.string.action_not_applied), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.muted)
        if (result.status != "APPLIED" && result.toolName in setOf(
                "delete_profile_fact", "delete_memory_episode", "delete_memory_object", "delete_table_row", "delete_table")) {
            Text(s(ai.memora.app.R.string.action_delete_not_confirmed),
                modifier = Modifier.testTag("action_delete_not_confirmed"),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            areaLabel?.let { label ->
                OutlinedButton(onClick = { areaRoute?.let(onOpenArea) }, modifier = Modifier.testTag("action_open_related")) {
                    Text("${s(ai.memora.app.R.string.action_open_related)} ${s(label)}")
                }
            }
            TextButton(onClick = onOpenSource, modifier = Modifier.testTag("action_open_source")) {
                Text(s(ai.memora.app.R.string.action_open_source))
            }
        }
        if (result.status == "APPLIED" && targetId != null) {
            OutlinedButton(onClick = {
                val prompt = String.format(java.util.Locale.ROOT, correctionTemplate, sourceMessage.take(12_000), targetId)
                onPrepareCorrection(prompt)
            }, modifier = Modifier.testTag("action_prepare_correction")) {
                Text(s(ai.memora.app.R.string.action_prepare_correction))
            }
        }
    }
}

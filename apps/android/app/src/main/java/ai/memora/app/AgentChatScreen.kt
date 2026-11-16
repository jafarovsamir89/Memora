package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.agent.AgentCloudConsentKeys
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ActionResultCard as SharedActionResultCard
import ai.memora.app.ui.ActionResultPresentation
import ai.memora.app.ui.MemoraStatusTone
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstScreen
import ai.memora.app.voice.VoiceFirstResult
import ai.memora.app.voice.VoiceTurnActionResult
import ai.memora.app.voice.VoiceTurnProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text chat and push-to-talk share the same agent, encrypted memory, and bounded dialogue context. */
@Composable
internal fun AgentChatScreen(language: String = "az", showVoiceControl: Boolean = false, onOpenArea: (String) -> Unit = {},
    onOpenTarget: (AppRoute) -> Unit = {},
    textTurnOverride: (suspend (String) -> VoiceFirstResult)? = null,
    textTurnProgressOverride: (suspend (String, (VoiceTurnProgress) -> Unit) -> VoiceFirstResult)? = null) {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val managedTurns = textTurnOverride == null && textTurnProgressOverride == null
    val turnModelFactory = remember(processor) { AgentChatTurnViewModel.Factory(processor) }
    val turnModel: AgentChatTurnViewModel = viewModel(factory = turnModelFactory)
    val turnState by turnModel.state.collectAsState()
    val inputFocusRequester = remember { FocusRequester() }
    val repository = remember(database) { ProtectedConversationRepository(database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val coroutineScope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<AgentConversationMessageRow>>(emptyList()) }
    var actionResults by remember { mutableStateOf<Map<String, List<AgentActionResultRow>>>(emptyMap()) }
    var pendingActionCards by remember { mutableStateOf<List<AgentActionCardData>>(emptyList()) }
    var activeRequestMessage by remember { mutableStateOf("") }
    var activeTurnId by remember { mutableStateOf<String?>(null) }
    var progressState by remember { mutableStateOf("thinking") }
    var sourceTurnId by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var correctionDraftPrepared by rememberSaveable { mutableStateOf(false) }
    var pendingMessage by rememberSaveable { mutableStateOf("") }
    var textConsent by remember { mutableStateOf(settings.getBoolean(AgentCloudConsentKeys.TEXT, false)) }
    var memoryConsent by remember { mutableStateOf(settings.getBoolean("gemini-persisted-memory-v1", false)) }
    var textConsentDialog by remember { mutableStateOf(false) }
    var memoryConsentDialog by remember { mutableStateOf(false) }
    var memoryRevokeDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failureCode by remember { mutableStateOf<String?>(null) }
    var draftRestoredAfterFailure by rememberSaveable { mutableStateOf(false) }
    var historyLoaded by rememberSaveable { mutableStateOf(false) }
    var savedHistoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedHistoryOffset by rememberSaveable { mutableIntStateOf(0) }
    var restoreHistoryPosition by remember { mutableStateOf(historyLoaded) }
    val historyState = rememberLazyListState()
    val requestBusy = busy || (managedTurns && turnState.busy)
    val requestMessage = if (managedTurns) turnState.pendingMessage else activeRequestMessage
    val requestTurnId = if (managedTurns) turnState.turnId else activeTurnId
    val requestActions = if (managedTurns) turnState.actions.map { AgentActionCardData.from(it, scopeId) } else pendingActionCards
    val requestProgress = if (managedTurns) turnState.stage else progressState
    val requestFailureCode = if (managedTurns) turnState.failureCode else failureCode
    val requestFailed = failed || (managedTurns && turnState.failureCode != null)

    suspend fun refreshMessages() {
        val recent = repository.recent(scopeId, 80)
        messages = recent
        actionResults = repository.actionResultsForTurns(scopeId, recent.map { it.turnId }.distinct())
        historyLoaded = true
    }
    LaunchedEffect(scopeId) { refreshMessages() }
    LaunchedEffect(managedTurns, turnState.completionVersion) {
        if (managedTurns && turnState.completionVersion > 0) refreshMessages()
    }
    var seenFailureVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(managedTurns, turnState.failureVersion) {
        if (managedTurns && turnState.failureVersion > seenFailureVersion) {
            seenFailureVersion = turnState.failureVersion
            turnState.failedDraft?.let {
                input = it
                draftRestoredAfterFailure = true
            }
            failed = true
            failureCode = turnState.failureCode
        }
    }
    LaunchedEffect(messages, requestBusy, requestFailed, requestMessage, requestActions, requestTurnId) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (restoreHistoryPosition) {
            val messageItemCount = messages.size * 2
            historyState.scrollToItem(savedHistoryIndex.coerceAtMost((messageItemCount - 1).coerceAtLeast(0)), savedHistoryOffset)
            restoreHistoryPosition = false
        } else if (historyState.firstVisibleItemIndex == 0 || requestBusy || requestMessage.isNotBlank() || requestActions.isNotEmpty()) {
            // In reverse layout, item zero is the newest turn. Follow it only when already at
            // the latest turn or while this conversation is actively adding a new one.
            historyState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(historyState, messages.isNotEmpty(), restoreHistoryPosition) {
        if (messages.isNotEmpty()) {
            androidx.compose.runtime.snapshotFlow {
                historyState.firstVisibleItemIndex to historyState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                if (!restoreHistoryPosition) {
                    savedHistoryIndex = index
                    savedHistoryOffset = offset
                }
            }
        }
    }

    fun onTurnProgress(progress: VoiceTurnProgress) {
        when (progress) {
            is VoiceTurnProgress.Stage -> progressState = progress.key
            is VoiceTurnProgress.ConfirmedActions -> {
                activeTurnId = progress.turnId
                pendingActionCards = progress.results.map { AgentActionCardData.from(it, scopeId) }
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || requestBusy) return
        if (!textConsent) {
            pendingMessage = trimmed
            textConsentDialog = true
            return
        }
        input = ""
        correctionDraftPrepared = false
        failed = false
        failureCode = null
        if (managedTurns) turnModel.clearFailure()
        draftRestoredAfterFailure = false
        if (managedTurns) {
            turnModel.sendText(trimmed)
            return
        }
        activeRequestMessage = trimmed
        activeTurnId = null
        pendingActionCards = emptyList()
        progressState = "thinking"
        busy = true
        coroutineScope.launch {
            try {
                val result = withTimeout(65_000) {
                    textTurnProgressOverride?.invoke(trimmed, ::onTurnProgress)
                        ?: textTurnOverride?.invoke(trimmed)
                        ?: processor.handleText(trimmed, ::onTurnProgress)
                }
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
                activeRequestMessage = ""
                activeTurnId = null
                pendingActionCards = emptyList()
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

    if (memoryRevokeDialog) AlertDialog(
        onDismissRequest = { memoryRevokeDialog = false },
        title = { Text(s(ai.memora.app.R.string.agent_memory_revoke_title)) },
        text = { Text(s(ai.memora.app.R.string.agent_memory_revoke_body)) },
        confirmButton = { TextButton(onClick = {
            memoryRevokeDialog = false
            if (settings.edit().putBoolean("gemini-persisted-memory-v1", false).commit()) memoryConsent = false
            else failed = true
        }, modifier = Modifier.testTag("agent_memory_revoke_confirm")) { Text(s(ai.memora.app.R.string.agent_memory_revoke)) } },
        dismissButton = { TextButton(onClick = { memoryRevokeDialog = false }, modifier = Modifier.testTag("agent_memory_revoke_cancel")) {
            Text(s(ai.memora.app.R.string.task_cancel))
        } },
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
        val sourceUserMessage = source.firstOrNull { it.role == "user" }?.message
            ?: requestMessage.takeIf { requestTurnId == turnId }
        AlertDialog(
            onDismissRequest = { sourceTurnId = null },
            title = { Text(s(ai.memora.app.R.string.action_source_title)) },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    sourceUserMessage?.let {
                        Text(s(ai.memora.app.R.string.chat_speaker_user), color = colors.accent)
                        Text(it, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp)
                            .testTag("action_source_user_message"))
                    }
                    source.firstOrNull { it.role == "model" }?.let {
                        Text(s(ai.memora.app.R.string.chat_speaker_secretary), color = colors.accent)
                        Text(it.message, color = colors.ink, modifier = Modifier.testTag("action_source_assistant_message"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sourceTurnId = null }, modifier = Modifier.testTag("action_source_close")) {
                Text(s(ai.memora.app.R.string.close))
            } },
        )
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s(ai.memora.app.R.string.agent_chat_intro), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.muted, maxLines = 2)
            }
            TextButton(onClick = { clearDialog = true }, enabled = messages.isNotEmpty(), modifier = Modifier.testTag("agent_chat_clear")) {
                Text(s(ai.memora.app.R.string.agent_chat_clear), maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (memoryConsent) {
                    memoryRevokeDialog = true
                } else memoryConsentDialog = true
            }, modifier = Modifier.weight(1f).testTag("agent_memory_consent_toggle")) {
                Text(s(if (memoryConsent) ai.memora.app.R.string.agent_memory_revoke else ai.memora.app.R.string.agent_memory_allow))
            }
            if (textConsent) TextButton(onClick = {
                if (settings.edit().putBoolean(AgentCloudConsentKeys.TEXT, false).commit()) textConsent = false
                else failed = true
            }, modifier = Modifier.weight(1f).testTag("agent_text_consent_toggle")) {
                Text(s(ai.memora.app.R.string.agent_text_revoke))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_history"),
            state = historyState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (requestBusy) item(key = "busy") {
                val stageLabel = s(when (requestProgress) {
                    "executing_tool" -> ai.memora.app.R.string.voice_first_executing_tool
                    "responding" -> ai.memora.app.R.string.voice_first_responding
                    "transcribing" -> ai.memora.app.R.string.voice_first_transcribing
                    else -> ai.memora.app.R.string.voice_first_thinking
                })
                MemoraCard(modifier = Modifier.testTag("chat_busy_status")) { Text(stageLabel, color = colors.muted) }
            }
            if (requestFailed) item(key = "failure") { MemoraCard {
                val message = requestFailureCode?.let { LocalStrings.current.getString(ai.memora.app.R.string.ai_failed_code, it) }
                    ?: s(ai.memora.app.R.string.ai_failed)
                Text(message, color = colors.muted)
                if (requestFailureCode == "model_rate_limited") {
                    Text(s(ai.memora.app.R.string.ai_model_quota_reached), color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenArea("settings") }, modifier = Modifier.testTag("agent_open_ai_model_settings")) {
                        Text(s(ai.memora.app.R.string.ai_model_change))
                    }
                }
                if (draftRestoredAfterFailure || (managedTurns && turnState.failedDraft != null)) Text(s(ai.memora.app.R.string.agent_failed_draft_kept),
                    color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("agent_failed_draft_note"))
            } }
            if (requestActions.isNotEmpty()) item(key = "pending-actions-${requestTurnId.orEmpty()}") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    requestActions.forEachIndexed { index, result ->
                        ActionResultCard(
                            database = database,
                            result = result,
                            actionsEnabled = !requestBusy,
                            onOpenArea = onOpenArea,
                            onOpenTarget = onOpenTarget,
                            onOpenSource = { sourceTurnId = result.turnId },
                            sourceMessage = requestMessage,
                            onPrepareCorrection = { prompt -> input = prompt.take(20_000); correctionDraftPrepared = true },
                            modifier = Modifier.testTag("action_result_${result.turnId}_$index"),
                        )
                    }
                }
            }
            if (requestMessage.isNotBlank()) item(key = "pending-user-${requestTurnId.orEmpty()}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MemoraCard(tint = colors.softAccent, contentPadding = 14.dp,
                        modifier = Modifier.fillMaxWidth(.88f).testTag("chat_pending_user_message")) {
                        Text(s(ai.memora.app.R.string.chat_speaker_user),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.accent)
                        Text(requestMessage, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (messages.isEmpty() && requestMessage.isBlank() && !requestBusy && !requestFailed) item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.TopCenter) {
                    Text(s(ai.memora.app.R.string.agent_chat_empty), modifier = Modifier.fillMaxWidth(.86f),
                        color = colors.muted, textAlign = TextAlign.Center)
                }
            }
            items(messages.asReversed(), key = { "${it.turnId}:${it.role}" }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoraCard(tint = if (message.role == "user") colors.softAccent else colors.card, contentPadding = 14.dp,
                        modifier = Modifier.fillMaxWidth(.88f).testTag("chat_turn_${message.turnId}_${message.role}")) {
                        Text(s(if (message.role == "user") ai.memora.app.R.string.chat_speaker_user else ai.memora.app.R.string.chat_speaker_secretary),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.accent)
                        Text(message.message, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    }
                    if (message.role == "model") actionResults[message.turnId].orEmpty().forEachIndexed { index, result ->
                        ActionResultCard(
                            database = database,
                            result = AgentActionCardData.from(result),
                            actionsEnabled = !requestBusy || message.turnId != requestTurnId,
                            onOpenArea = onOpenArea,
                            onOpenTarget = onOpenTarget,
                            onOpenSource = { sourceTurnId = message.turnId },
                            sourceMessage = messages.firstOrNull { it.turnId == message.turnId && it.role == "user" }?.message
                                ?: requestMessage,
                            onPrepareCorrection = { prompt -> input = prompt.take(20_000); correctionDraftPrepared = true },
                            modifier = Modifier.testTag("action_result_${message.turnId}_$index"),
                        )
                    }
                }
            }
        }
        if (correctionDraftPrepared) Text(s(ai.memora.app.R.string.action_correction_draft_note),
            color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("action_correction_draft_note"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showVoiceControl) VoiceFirstScreen(compact = true, processorOverride = processor,
                onOpenSettings = { onOpenArea("settings") },
                onTurnStarted = { text ->
                    activeRequestMessage = text
                    activeTurnId = null
                    pendingActionCards = emptyList()
                    progressState = "thinking"
                },
                onTurnProgress = ::onTurnProgress,
                onShortTap = { inputFocusRequester.requestFocus() },
                onTurnSettled = { if (!managedTurns) coroutineScope.launch {
                    refreshMessages()
                    activeRequestMessage = ""
                    activeTurnId = null
                    pendingActionCards = emptyList()
                } },
                enabled = !requestBusy,
                turnExecutor = if (managedTurns) ({ text, report -> turnModel.handleVoice(text, report) }) else null,
                modifier = Modifier.testTag("secretary_voice_card"))
            OutlinedTextField(input, { if (it.length <= 20_000) {
                input = it
                if (draftRestoredAfterFailure) {
                    draftRestoredAfterFailure = false; failed = false; failureCode = null
                    if (managedTurns) turnModel.clearFailure()
                }
            } }, Modifier.weight(1f).focusRequester(inputFocusRequester).testTag("chat_input"),
                label = { Text(s(ai.memora.app.R.string.question)) }, shape = RoundedCornerShape(20.dp), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                enabled = !requestBusy)
            FilledIconButton({ send(input) }, Modifier.testTag("chat_send"), enabled = input.isNotBlank() && !requestBusy) {
                Icon(Glyph.Arrow, s(ai.memora.app.R.string.send))
            }
        }
    }
}

private data class AgentActionCardData(
    val scopeId: String,
    val resultId: String,
    val turnId: String,
    val toolName: String,
    val status: String,
    val code: String,
    val targetKind: String?,
    val targetId: String?,
) {
    companion object {
        fun from(row: AgentActionResultRow) = AgentActionCardData(row.scopeId, row.resultId, row.turnId, row.toolName,
            row.status, row.code, row.targetKind, row.targetId)
        fun from(row: VoiceTurnActionResult, scopeId: String) = AgentActionCardData(scopeId, row.resultId, row.turnId, row.toolName,
            row.status, row.code, row.targetKind, row.targetId)
    }
}

@Composable
private fun ActionResultCard(database: AgentDatabase, result: AgentActionCardData, actionsEnabled: Boolean, onOpenArea: (String) -> Unit,
    onOpenTarget: (AppRoute) -> Unit,
    onOpenSource: () -> Unit, sourceMessage: String, onPrepareCorrection: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val statusLabel = when (result.status) {
        "APPLIED" -> ai.memora.app.R.string.action_status_applied
        "NEEDS_CONFIRMATION" -> ai.memora.app.R.string.action_status_confirmation
        "UNKNOWN" -> ai.memora.app.R.string.action_status_unknown
        else -> ai.memora.app.R.string.action_status_failed
    }
    val statusTone = when (result.status) {
        "APPLIED" -> MemoraStatusTone.SUCCESS
        "NEEDS_CONFIRMATION", "UNKNOWN" -> MemoraStatusTone.WARNING
        else -> MemoraStatusTone.ERROR
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
    val targetRoute = AppTargetKind.fromAction(result.targetKind, targetId)
    val relatedLabel = areaLabel?.let { "${s(ai.memora.app.R.string.action_open_related)} ${s(it)}" }
    val resources = LocalStrings.current
    val detailLines by produceState(
        initialValue = emptyList(),
        database,
        result.scopeId,
        result.targetKind,
        targetId,
        resources,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                loadActionCardSummary(database, result.scopeId, result.targetKind, targetId, resources).orEmpty()
            }.getOrDefault(emptyList())
        }
    }
    SharedActionResultCard(
        presentation = ActionResultPresentation(
            statusLabel = s(statusLabel),
            statusTone = statusTone,
            relatedActionLabel = relatedLabel,
            sourceActionLabel = s(ai.memora.app.R.string.action_open_source),
            correctionActionLabel = if (actionsEnabled && result.status == "APPLIED" && targetId != null)
                s(ai.memora.app.R.string.action_prepare_correction) else null,
            supportingText = if (result.status == "NEEDS_CONFIRMATION") s(ai.memora.app.R.string.action_not_applied) else null,
            warningText = if (result.status != "APPLIED" && result.toolName in setOf(
                    "delete_profile_fact", "delete_memory_episode", "delete_memory_object", "delete_table_row", "delete_table"))
                s(ai.memora.app.R.string.action_delete_not_confirmed) else null,
            detailLines = detailLines,
        ),
        onOpenRelated = if (areaLabel == null || !actionsEnabled) null else ({
            when {
                targetRoute != null -> onOpenTarget(targetRoute)
                else -> areaRoute?.let(onOpenArea)
            }
        }),
        onOpenSource = onOpenSource,
        onPrepareCorrection = if (actionsEnabled && result.status == "APPLIED" && targetId != null) ({
            val prompt = String.format(java.util.Locale.ROOT, correctionTemplate, sourceMessage.take(12_000), targetId)
            onPrepareCorrection(prompt)
        }) else null,
        modifier = modifier,
        testTag = "action_card_${result.status.lowercase()}",
    )
}

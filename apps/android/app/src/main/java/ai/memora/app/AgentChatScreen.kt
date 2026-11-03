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
import ai.memora.app.agent.AgentCloudConsentKeys
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Text chat and push-to-talk share the same agent, encrypted memory, and bounded dialogue context. */
@Composable
fun AgentChatScreen(language: String = "az") {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val repository = remember(context) { ProtectedConversationRepository(AgentDatabase.open(context)) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val coroutineScope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<AgentConversationMessageRow>>(emptyList()) }
    var input by rememberSaveable { mutableStateOf("") }
    var pendingMessage by rememberSaveable { mutableStateOf("") }
    var textConsent by remember { mutableStateOf(settings.getBoolean(AgentCloudConsentKeys.TEXT, false)) }
    var memoryConsent by remember { mutableStateOf(settings.getBoolean("gemini-persisted-memory-v1", false)) }
    var textConsentDialog by remember { mutableStateOf(false) }
    var memoryConsentDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    suspend fun refreshMessages() { messages = repository.recent(scopeId, 80) }
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
        failed = false
        busy = true
        coroutineScope.launch {
            try {
                when (val result = withTimeout(60_000) { processor.handleText(trimmed) }) {
                    is VoiceFirstResult.Ready -> refreshMessages()
                    VoiceFirstResult.Unavailable -> failed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
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

    ScreenColumn {
        Text(s(ai.memora.app.R.string.chat_title), Modifier.fillMaxWidth(), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = colors.ink, textAlign = TextAlign.Center)
        Text(s(ai.memora.app.R.string.agent_chat_intro), Modifier.fillMaxWidth(), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.muted, textAlign = TextAlign.Center)
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
            MemoraCard(tint = if (message.role == "user") colors.softAccent else colors.card) {
                Text(message.message, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            }
        }
        if (busy) MemoraCard { Text(s(ai.memora.app.R.string.ai_loading), color = colors.muted) }
        if (failed) MemoraCard { Text(s(ai.memora.app.R.string.ai_failed), color = colors.muted) }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20_000) input = it }, Modifier.weight(1f).testTag("chat_input"),
                label = { Text(s(ai.memora.app.R.string.question)) }, shape = RoundedCornerShape(20.dp), maxLines = 4,
                enabled = !busy)
            FilledIconButton({ send(input) }, Modifier.testTag("chat_send"), enabled = input.isNotBlank() && !busy) {
                Icon(Glyph.Arrow, s(ai.memora.app.R.string.send))
            }
        }
        if (textConsent) TextButton(onClick = {
            if (settings.edit().putBoolean(AgentCloudConsentKeys.TEXT, false).commit()) textConsent = false
            else failed = true
        }, modifier = Modifier.testTag("agent_text_consent_toggle")) { Text(s(ai.memora.app.R.string.agent_text_revoke)) }
    }
}

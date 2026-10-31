package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ai.memora.app.ui.*
import ai.memora.app.voice.VoiceInput
import ai.memora.app.voice.ResponseSpeech
import ai.memora.app.voice.CloudVoiceInput
import ai.memora.app.ai.FirebaseAssistantGateway
import ai.memora.app.ai.AssistantGateway
import ai.memora.app.data.CaptureSessionState
import ai.memora.app.data.CaptureImportPreview
import kotlinx.coroutines.launch

@Composable
fun CaptureImportDialog(preview: CaptureImportPreview.Ready, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(s(R.string.capture_import)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(LocalStrings.current.getString(R.string.capture_import_summary, preview.newSources, preview.existingSources))
            Text(s(if (preview.restoresDraft) R.string.capture_import_draft_yes else R.string.capture_import_draft_no))
            Text(s(R.string.capture_import_warning))
            preview.draftSample?.let { Text(s(R.string.capture_import_draft_sample) + " " + it) }
            preview.samples.forEach { Text("• $it") }
        } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("capture_import_confirm")) { Text(s(R.string.capture_import_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("capture_import_cancel")) { Text(s(R.string.task_cancel)) } })
}

@Composable
fun CaptureSheet(voice: Boolean, setVoice: (Boolean) -> Unit, state: CaptureSessionState, setDraft: (String) -> Unit,
    onSave: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit, language: String) {
    val draft = state.draft
    val editable = state.ready && !state.submitting && !state.retrySubmission && !state.managementBusy
    val canClose = !state.dirty && !state.saving && !state.submitting && !state.retrySubmission
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.new_memory), style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.weight(1f))
            IconButton(onClose, enabled = canClose) { Icon(Glyph.Close, s(R.string.close), tint = colors.muted) }
        }
        Text(s(R.string.capture_synthetic_only), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (state.loading) Text(s(R.string.capture_loading), modifier = Modifier.testTag("capture_status"))
        if (state.error) {
            Text(s(if (state.retrySubmission) R.string.capture_retry_submission else if (state.limitReached) R.string.capture_limit else R.string.capture_storage_error),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capture_error"))
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("capture_retry")) { Text(s(R.string.capture_retry)) }
        }
        if (state.ready && !state.error && (state.saving || state.dirty || state.submitting))
            Text(s(if (state.submitting) R.string.capture_submitting else R.string.capture_saving), modifier = Modifier.testTag("capture_status"))
        if (state.ready && !state.error && !state.dirty && !state.saving && !state.submitting)
            Text(s(R.string.capture_saved_local), modifier = Modifier.testTag("capture_status"))
        if (editable) Filters(listOf("text" to s(R.string.text_tab), "voice" to s(R.string.voice_tab)), if (voice) "voice" else "text", { setVoice(it == "voice") })
        if (voice && editable) {
            VoiceInput(language, draft, setDraft, { setVoice(false) })
        } else {
            OutlinedTextField(
                value = draft, onValueChange = { if (it.length <= 2000) setDraft(it) },
                modifier = Modifier.fillMaxWidth().testTag("capture_input"),
                label = { Text(s(R.string.capture_label)) }, placeholder = { Text(s(R.string.capture_placeholder)) },
                enabled = editable, minLines = 4, maxLines = 8, shape = RoundedCornerShape(20.dp),
                supportingText = { Text("${draft.length}/2000") },
            )
            Text(s(R.string.draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Button(onSave, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("save_draft"), enabled = editable && draft.isNotBlank() && !state.error) {
                Icon(Glyph.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.add_draft))
            }
        }
    }
}

@Composable
fun MemoryDetail(item: ExampleMemory, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(s(item.category), item.tone)
            Spacer(Modifier.weight(1f))
            IconButton(onClose) { Icon(Glyph.Close, s(R.string.close), tint = colors.muted) }
        }
        Text(s(item.title), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
        Text(s(item.body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        Text(s(R.string.time_recent), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        MemoraCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Note, null, Modifier.size(19.dp), tint = colors.accent)
                Text(s(R.string.source), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
            Text(s(item.source), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            Text(s(R.string.source_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(s(R.string.sample_only), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
fun ChatScreen(language: String = "ru", gatewayOverride: AssistantGateway? = null) {
    var question by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var response by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val textFocus = remember { FocusRequester() }
    val defaultGateway = remember(context) { FirebaseAssistantGateway(context) }
    val gateway = gatewayOverride ?: defaultGateway
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(s(R.string.ai_consent_title)) },
        text = { Text(s(R.string.ai_consent_body) + "\n\n" + question.trim()) },
        confirmButton = { TextButton(onClick = {
            val prompt = question.trim()
            confirm = false
            submitted = prompt
            response = ""
            failed = false
            loading = true
            scope.launch {
                try { response = kotlinx.coroutines.withTimeout(30_000) { gateway.answer(prompt) } }
                catch (_: kotlinx.coroutines.TimeoutCancellationException) { failed = true }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // Never record prompts, provider responses, tokens or exception messages.
                    val status = Regex("\\b[45]\\d\\d\\b").find(error.message.orEmpty())?.value ?: "unknown"
                    val category = listOf("quota", "region", "location", "permission", "billing", "not found", "key", "app check", "internal", "unavailable", "unsupported", "model", "server", "exhausted", "credentials", "token", "network", "failed")
                        .filter { error.message.orEmpty().contains(it, ignoreCase = true) }.joinToString(",")
                    android.util.Log.w("MemoraAI", "Request failed: ${error.javaClass.simpleName}; cause: ${error.cause?.javaClass?.simpleName ?: "none"}; HTTP status: $status; categories: $category")
                    failed = true
                }
                finally { loading = false }
            }
        }) { Text(s(R.string.ai_send_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(s(R.string.task_cancel)) } },
    )
    ScreenColumn {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { IconBubble(Glyph.Spark, size = 72) }
        Text(s(R.string.chat_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium,
            color = colors.ink, textAlign = TextAlign.Center)
        Text(s(R.string.ai_chat_body), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
            color = colors.muted, textAlign = TextAlign.Center)
        val one = s(R.string.suggestion_one)
        val two = s(R.string.suggestion_two)
        OutlinedButton({ question = one }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(one) }
        OutlinedButton({ question = two }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(two) }
        if (submitted.isNotBlank()) {
            MemoraCard(tint = colors.softAccent) { Text(submitted, color = colors.ink, style = MaterialTheme.typography.bodyLarge) }
            if (loading) MemoraCard { Text(s(R.string.ai_loading), color = colors.muted) }
            if (failed) MemoraCard { Text(s(R.string.ai_failed), color = colors.muted) }
            if (response.isNotBlank()) MemoraCard {
                Text(response, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                ResponseSpeech(response, language)
            }
        }
        CloudVoiceInput(onText = { textFocus.requestFocus() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(question, { if (it.length <= 2000) question = it }, Modifier.weight(1f).focusRequester(textFocus).testTag("chat_input"),
                label = { Text(s(R.string.question)) }, shape = RoundedCornerShape(20.dp), maxLines = 4)
            FilledIconButton({ confirm = true }, Modifier.size(52.dp).testTag("chat_send"), enabled = question.isNotBlank() && !loading) {
                Icon(Glyph.Arrow, s(R.string.send))
            }
        }
    }
}

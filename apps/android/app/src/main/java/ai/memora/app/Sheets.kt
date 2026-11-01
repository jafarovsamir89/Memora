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
import ai.memora.app.voice.AudioNoteInput
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
    var voiceMode by rememberSaveable { mutableStateOf("cloud") }
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
            Filters(listOf("cloud" to "Google Gemini", "local" to s(R.string.voice_title)), voiceMode, { voiceMode = it })
            if (voiceMode == "cloud") AudioNoteInput(draft, setDraft, { setVoice(false) })
            else VoiceInput(language, draft, setDraft, { setVoice(false) })
        } else {
            OutlinedTextField(
                value = draft, onValueChange = { if (ai.memora.app.domain.TextSourceRules.validDraft(it)) setDraft(it) },
                modifier = Modifier.fillMaxWidth().testTag("capture_input"),
                label = { Text(s(R.string.capture_label)) }, placeholder = { Text(s(R.string.capture_placeholder)) },
                enabled = editable, minLines = 4, maxLines = 8, shape = RoundedCornerShape(20.dp),
                supportingText = { Text("${draft.length}/20000") },
            )
            Text(s(R.string.draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Button(onSave, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("save_draft"), enabled = editable && draft.isNotBlank() && !state.error) {
                Icon(Glyph.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.add_draft))
            }
        }
    }
}

package ai.memora.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ai.memora.app.R
import ai.memora.app.domain.TextSourceRules
import ai.memora.app.ui.s
import kotlinx.coroutines.*

@Composable
fun AudioNoteInput(draft: String, onDraft: (String) -> Unit, showText: () -> Unit,
    gatewayOverride: AudioNoteGateway? = null, recorderOverride: AudioNoteRecorder? = null) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val gateway = gatewayOverride ?: remember(context) { FirebaseAudioNoteGateway(context) }
    val recorder = recorderOverride ?: remember { AndroidAudioNoteRecorder() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var state by remember { mutableStateOf("idle") }
    var consent by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var transcript by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var writingFailed by remember { mutableStateOf(false) }
    val currentDraft by rememberUpdatedState(draft)

    fun cancel() {
        pendingPermission = false; consent = false; state = "idle"; job?.cancel(); recorder.stop()
    }
    fun start() {
        if (!visible || job != null) return
        transcript = ""; original = ""; writingFailed = false; state = "recording"
        recorder.prepare()
        job = scope.launch(start = CoroutineStart.LAZY) {
            var audio: ByteArray? = null
            try {
                val wav = recorder.record()
                audio = wav
                ensureActive()
                if (!visible) return@launch
                state = "processing"
                val raw = withTimeout(30_000) { gateway.transcribe(wav) }
                audio?.fill(0) // audio is no longer needed by the separate text-only writing stage
                ensureActive()
                val baseline = TranscriptOrthography.localSuggestion(raw)
                val result = if (raw.isBlank()) raw else try { withTimeout(15_000) { gateway.suggestWriting(baseline) } }
                    catch (_: TimeoutCancellationException) { writingFailed = true; baseline }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { writingFailed = true; baseline }
                ensureActive()
                if (visible) {
                    original = raw
                    transcript = result
                    state = if (result.isBlank()) "error" else "review"
                }
            } catch (_: TimeoutCancellationException) { if (visible) state = "error" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (visible) state = "error" }
            finally { recorder.stop(); audio?.fill(0); job = null }
        }
        job?.start()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingPermission; pendingPermission = false
        if (requested && granted && visible && (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) start()
        else if (requested) state = "denied"
    }
    DisposableEffect(lifecycle, recorder) {
        visible = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle?.addObserver(observer)
        onDispose { visible = false; lifecycle?.removeObserver(observer); cancel() }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text(s(R.string.audio_note_consent_title)) }, text = { Text(s(R.string.audio_note_consent_body)) },
        confirmButton = { TextButton(onClick = {
            consent = false
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else { pendingPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }, modifier = Modifier.testTag("audio_note_confirm")) { Text(s(R.string.cloud_voice_confirm)) } },
        dismissButton = { TextButton(onClick = { consent = false }, modifier = Modifier.testTag("audio_note_decline")) { Text(s(R.string.task_cancel)) } })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s(R.string.audio_note_title), style = MaterialTheme.typography.titleMedium)
        Text(s(R.string.audio_note_hint))
        if (state == "recording") OutlinedButton(onClick = { state = "processing"; recorder.stop() },
            modifier = Modifier.fillMaxWidth().testTag("audio_note_finish")) { Text(s(R.string.audio_note_finish)) }
        if (state == "processing") Text(s(R.string.voice_processing), modifier = Modifier.testTag("audio_note_processing"))
        if (state == "recording" || state == "processing") TextButton(onClick = { cancel() },
            modifier = Modifier.testTag("audio_note_cancel")) { Text(s(R.string.task_cancel)) }
        else OutlinedButton(onClick = { consent = true }, modifier = Modifier.fillMaxWidth().testTag("audio_note_start"), enabled = job == null) {
            Text(s(R.string.audio_note_start))
        }
        if (state == "denied" || state == "error") Text(s(if (state == "denied") R.string.voice_denied else R.string.voice_error),
            modifier = Modifier.testTag("audio_note_error"))
        if (state == "review") {
            Text(s(R.string.audio_note_review_notice))
            if (writingFailed) Text(s(R.string.audio_note_writing_failed))
            OutlinedTextField(transcript, { if (TextSourceRules.validDraft(it)) transcript = it },
                modifier = Modifier.fillMaxWidth().testTag("audio_note_review"), label = { Text(s(R.string.capture_label)) }, minLines = 2, maxLines = 6)
            if (original != transcript) {
                Text(s(R.string.audio_note_original))
                Text(original, modifier = Modifier.testTag("audio_note_original"))
                TextButton(onClick = { transcript = original }, modifier = Modifier.testTag("audio_note_use_original")) {
                    Text(s(R.string.audio_note_use_original))
                }
            }
            val combined = if (currentDraft.isBlank()) transcript else currentDraft.trimEnd() + "\n" + transcript
            Button(onClick = { onDraft(combined); transcript = ""; showText() },
                modifier = Modifier.testTag("audio_note_apply"), enabled = transcript.isNotBlank() && TextSourceRules.validDraft(combined)) {
                Text(s(R.string.audio_note_apply))
            }
        }
    }
}

package ai.memora.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ai.memora.app.R
import ai.memora.app.data.VoiceMoneyKey
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.s
import java.io.File
import kotlinx.coroutines.*

/** One hold-to-talk control. No microphone or cloud request begins before consent and permission. */
@Composable
fun VoiceFirstScreen(gatewayOverride: AudioNoteGateway? = null, recorderOverride: AudioNoteRecorder? = null,
    processorOverride: VoiceTurnProcessor? = null, speechOverride: SpeechOutput? = null, onOpenMenu: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val settings = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    val recorder = recorderOverride ?: remember { AndroidAudioNoteRecorder() }
    val gateway = gatewayOverride ?: remember(context) { FirebaseAudioNoteGateway(context) }
    val processor = processorOverride ?: remember(context) {
        VoiceFirstAssistant(VoiceMoneyStore(File(context.filesDir, "voice-memory.bin"), VoiceMoneyKey::get), GeminiSpokenIntentParser())
    }
    val speech = speechOverride ?: remember { GeminiSpeechOutput() }
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(settings.getBoolean("google-audio-and-answer-v1", false)) }
    var consentDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("idle") }
    var transcript by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var release by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var visible by remember { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        state = if (allowed) "idle" else "permission"
    }

    fun cancel() {
        release?.complete(false)
        job?.cancel()
        recorder.stop()
        state = "idle"
    }
    fun begin(): Boolean {
        if (!visible || job != null) return false
        if (!consent) { consentDialog = true; return false }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        transcript = ""; answer = ""; state = "recording"
        recorder.prepare()
        val released = CompletableDeferred<Boolean>()
        release = released
        job = scope.launch(start = CoroutineStart.LAZY) {
            var wav: ByteArray? = null
            try {
                wav = recorder.record()
                if (!released.isCompleted && visible) state = "release"
                if (!released.await()) return@launch
                ensureActive()
                if (!visible) return@launch
                state = "processing"
                val spoken = withTimeout(30_000) { gateway.transcribe(wav) }
                wav.fill(0); wav = null
                ensureActive()
                if (spoken.isBlank()) { state = "error"; return@launch }
                transcript = spoken
                val result = withTimeout(30_000) { processor.handle(spoken) }
                ensureActive()
                if (result !is VoiceFirstResult.Ready) { state = "error"; return@launch }
                answer = result.spoken
                state = "speaking"
                try { withTimeout(30_000) { speech.speak(result.spoken) }; state = "idle" }
                catch (_: TimeoutCancellationException) { state = "sound_error" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state = "sound_error" }
            } catch (_: TimeoutCancellationException) { if (visible) state = "error" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (visible) state = "error" }
            finally { recorder.stop(); wav?.fill(0); release = null; job = null }
        }
        job?.start()
        return true
    }
    fun finish() {
        if (state != "recording" && state != "release") return
        state = "processing"
        release?.complete(true)
        recorder.stop()
    }

    DisposableEffect(lifecycle, recorder) {
        visible = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle?.addObserver(observer)
        onDispose { visible = false; lifecycle?.removeObserver(observer); cancel() }
    }
    if (consentDialog) AlertDialog(onDismissRequest = { consentDialog = false },
        title = { Text(s(R.string.voice_first_consent_title)) }, text = { Text(s(R.string.voice_first_consent_body)) },
        confirmButton = { TextButton(onClick = {
            consentDialog = false
            if (settings.edit().putBoolean("google-audio-and-answer-v1", true).commit()) {
                consent = true
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.RECORD_AUDIO)
            } else state = "error"
        }, modifier = Modifier.testTag("voice_first_consent")) { Text(s(R.string.cloud_voice_confirm)) } },
        dismissButton = { TextButton(onClick = { consentDialog = false }) { Text(s(R.string.task_cancel)) } })

    val label = s(R.string.voice_first_button)
    val menuLabel = s(R.string.voice_first_menu_hint)
    Column(Modifier.fillMaxSize().testTag("voice_first_home").pointerInput(onOpenMenu) {
        var distance = 0f
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { _, amount -> distance += amount },
            onDragEnd = { if (distance < -120f) onOpenMenu() })
    }.semantics { customActions = listOf(CustomAccessibilityAction(menuLabel) { onOpenMenu(); true }) }
        .padding(horizontal = 28.dp).statusBarsPadding().navigationBarsPadding(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Text(s(when (state) {
            "recording" -> R.string.voice_first_listening
            "release" -> R.string.voice_first_release
            "processing" -> R.string.voice_first_processing
            "speaking" -> R.string.voice_first_speaking
            "permission" -> R.string.voice_denied
            "error" -> R.string.voice_first_error
            "sound_error" -> R.string.voice_first_sound_error
            else -> R.string.voice_first_hint
        }), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Text(menuLabel, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.size(220.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            .testTag("voice_first_button")
            .semantics {
                contentDescription = label; role = Role.Button
                onClick { if (state == "recording" || state == "release") finish() else begin(); true }
            }
            .pointerInput(recorder) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val started = begin()
                    val up = waitForUpOrCancellation()
                    if (started) { if (up == null) cancel() else { up.consume(); finish() } }
                }
            }, contentAlignment = Alignment.Center) {
            Icon(Glyph.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(76.dp))
        }
        Spacer(Modifier.height(28.dp))
        if (transcript.isNotBlank()) Text(transcript, modifier = Modifier.testTag("voice_first_transcript"), textAlign = TextAlign.Center)
        if (answer.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(answer, modifier = Modifier.testTag("voice_first_answer"), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

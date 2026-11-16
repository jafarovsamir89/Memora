package ai.memora.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ai.memora.app.R
import ai.memora.app.agent.AgentCloudConsentKeys
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import kotlinx.coroutines.*

/** One hold-to-talk control. No microphone or cloud request begins before consent and permission. */
@Composable
fun VoiceFirstScreen(gatewayOverride: AudioNoteGateway? = null, recorderOverride: AudioNoteRecorder? = null,
    processorOverride: VoiceTurnProcessor? = null, speechOverride: SpeechOutput? = null,
    onOpenSettings: (() -> Unit)? = null, compact: Boolean = false, onTurnSettled: () -> Unit = {},
    modifier: Modifier = Modifier, onTurnProgress: (VoiceTurnProgress) -> Unit = {},
    onTurnStarted: (String) -> Unit = {}, onShortTap: () -> Unit = {}, enabled: Boolean = true,
    turnExecutor: (suspend (String, (VoiceTurnProgress) -> Unit) -> VoiceFirstResult)? = null) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val settings = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    val recorder = recorderOverride ?: remember { AndroidAudioNoteRecorder() }
    val gateway = gatewayOverride ?: remember(context) { FirebaseAudioNoteGateway(context) }
    val processor = processorOverride ?: remember(context) { MemoraVoiceTurnProcessor(context) }
    val speech = speechOverride ?: remember { GeminiSpeechOutput() }
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(settings.getBoolean(AgentCloudConsentKeys.VOICE, false)) }
    var consentDialog by remember { mutableStateOf(false) }
    var memoryConsent by remember { mutableStateOf(settings.getBoolean("gemini-persisted-memory-v1", false)) }
    var memoryConsentDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("idle") }
    var transcript by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var failureCode by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var release by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var releaseFeedbackStartedAt by remember { mutableLongStateOf(0L) }
    var visible by remember { mutableStateOf(true) }
    val currentOnTurnSettled by rememberUpdatedState(onTurnSettled)
    val currentOnTurnProgress by rememberUpdatedState(onTurnProgress)
    val currentOnTurnStarted by rememberUpdatedState(onTurnStarted)
    val currentOnShortTap by rememberUpdatedState(onShortTap)
    val currentEnabled by rememberUpdatedState(enabled)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        state = if (allowed) "idle" else "permission"
    }

    fun cancel(showFeedback: Boolean = false) {
        release?.complete(false)
        job?.cancel()
        recorder.stop()
        state = if (showFeedback) "cancelled" else "idle"
        if (showFeedback) scope.launch {
            delay(1_000)
            if (state == "cancelled") state = "idle"
        }
    }
    fun begin(): Boolean {
        if (!visible || !currentEnabled || job != null) return false
        if (!consent) { consentDialog = true; return false }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        transcript = ""; answer = ""; failureCode = null; state = "recording"
        recorder.prepare()
        val released = CompletableDeferred<Boolean>()
        release = released
        job = scope.launch(start = CoroutineStart.LAZY) {
            var wav: ByteArray? = null
            var stage = "audio"
            try {
                wav = recorder.record()
                if (!released.isCompleted && visible) state = "release"
                if (!released.await()) return@launch
                val releaseStartedAt = releaseFeedbackStartedAt
                withFrameNanos { }
                if (visible && releaseStartedAt > 0) {
                    val elapsedMillis = (SystemClock.elapsedRealtime() - releaseStartedAt).coerceAtLeast(0)
                    Log.d("MemoraVoiceUI", "release_to_processing_frame_ms=$elapsedMillis")
                    releaseFeedbackStartedAt = 0
                }
                ensureActive()
                if (!visible) return@launch
                state = "transcribing"
                stage = "stt"
                val spoken = withTimeout(VoiceLanguagePolicy.AUDIO_TRANSCRIPTION_TIMEOUT_MS) { gateway.transcribe(wav) }
                wav.fill(0); wav = null
                ensureActive()
                if (spoken.isBlank()) { failureCode = "stt_empty_transcript"; state = "error"; return@launch }
                transcript = spoken
                if (visible) currentOnTurnStarted(spoken)
                // AgentRuntime allows 60 seconds for multi-round tool calling; leave a small
                // margin so the screen does not cancel the turn before the runtime reports its result.
                stage = "assistant"
                state = "thinking"
                val reportProgress: (VoiceTurnProgress) -> Unit = { progress ->
                    if (visible) {
                        if (progress is VoiceTurnProgress.Stage) state = when (progress.key) {
                            "executing_tool" -> "executing_tool"
                            "responding" -> "responding"
                            else -> "thinking"
                        }
                        currentOnTurnProgress(progress)
                    }
                }
                reportProgress(VoiceTurnProgress.Stage("thinking"))
                val result = if (turnExecutor != null) turnExecutor.invoke(spoken, reportProgress)
                    else withTimeout(65_000) { processor.handle(spoken, reportProgress) }
                ensureActive()
                if (visible) currentOnTurnSettled()
                val spokenAnswer = when (result) {
                    is VoiceFirstResult.Ready -> result.spoken
                    is VoiceFirstResult.Failed -> { failureCode = result.code; state = "error"; return@launch }
                    VoiceFirstResult.Unavailable -> { failureCode = "assistant_unavailable"; state = "error"; return@launch }
                }
                answer = spokenAnswer
                state = "speaking"
                try { withTimeout(30_000) { speech.speak(spokenAnswer) }; state = "idle" }
                catch (_: TimeoutCancellationException) { state = "sound_error" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state = "sound_error" }
            } catch (error: TimeoutCancellationException) {
                if (visible) { failureCode = "${stage}_timeout"; state = "error" }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (visible) { failureCode = VoiceFailureDiagnostics.code(stage, error); state = "error" }
            }
            finally { recorder.stop(); wav?.fill(0); release = null; job = null }
        }
        job?.start()
        return true
    }
    fun finish() {
        if (state != "recording" && state != "release") return
        releaseFeedbackStartedAt = SystemClock.elapsedRealtime()
        state = "processing"
        release?.complete(true)
        recorder.stop()
    }
    val currentBegin by rememberUpdatedState<() -> Boolean>({ begin() })
    val currentFinish by rememberUpdatedState<() -> Unit>({ finish() })
    val currentCancel by rememberUpdatedState<() -> Unit>({ cancel(showFeedback = true) })

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
            if (settings.edit().putBoolean(AgentCloudConsentKeys.VOICE, true).commit()) {
                consent = true
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.RECORD_AUDIO)
            } else state = "error"
        }, modifier = Modifier.testTag("voice_first_consent")) { Text(s(R.string.cloud_voice_confirm)) } },
        dismissButton = { TextButton(onClick = { consentDialog = false }) { Text(s(R.string.task_cancel)) } })

    if (memoryConsentDialog) AlertDialog(onDismissRequest = { memoryConsentDialog = false },
        title = { Text(s(R.string.agent_memory_consent_title)) },
        text = { Text(s(R.string.agent_memory_consent_body)) },
        confirmButton = { TextButton(onClick = {
            memoryConsentDialog = false
            if (settings.edit().putBoolean("gemini-persisted-memory-v1", true).commit()) memoryConsent = true
            else state = "error"
        }) { Text(s(R.string.agent_memory_allow)) } },
        dismissButton = { TextButton(onClick = { memoryConsentDialog = false }) { Text(s(R.string.task_cancel)) } })

    val label = s(R.string.voice_first_button)
    val compactControl = compact
    Column(modifier.then(if (compactControl) Modifier.width(62.dp) else Modifier.fillMaxWidth())
        .testTag("voice_first_home").padding(vertical = if (compactControl) 0.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val statusText = if (state == "error" && failureCode != null) {
            LocalStrings.current.getString(R.string.voice_first_error_code, failureCode ?: "request_failed")
        } else s(when (state) {
            "recording" -> R.string.voice_first_listening
            "release" -> R.string.voice_first_release
            "processing" -> R.string.voice_first_processing
            "transcribing" -> R.string.voice_first_transcribing
            "thinking" -> R.string.voice_first_thinking
            "executing_tool" -> R.string.voice_first_executing_tool
            "responding" -> R.string.voice_first_responding
            "cancelled" -> R.string.voice_first_cancelled
            "speaking" -> R.string.voice_first_speaking
            "permission" -> R.string.voice_denied
            "error" -> R.string.voice_first_error
            "sound_error" -> R.string.voice_first_sound_error
            else -> R.string.voice_first_hint
        })
        Text(statusText, textAlign = TextAlign.Center,
            style = if (compactControl) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyLarge,
            maxLines = if (compactControl) 2 else Int.MAX_VALUE,
            modifier = Modifier.testTag("voice_first_status"))
        if (state == "error" && failureCode == "model_rate_limited" && onOpenSettings != null) {
            Text(s(R.string.ai_model_quota_reached), color = colors.muted,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("voice_open_ai_model_settings")) {
                Text(s(R.string.ai_model_change))
            }
        }
        Spacer(Modifier.height(if (compactControl) 4.dp else 16.dp))
        val outerSize = if (compactControl) 58.dp else 176.dp
        val innerSize = if (compactControl) 52.dp else 144.dp
        val buttonDescription = if (state == "recording" || state == "release")
            s(R.string.voice_first_accessible_stop) else s(R.string.voice_first_accessible_start)
        Box(Modifier.size(outerSize).alpha(if (enabled) 1f else .55f)
            .background(colors.accent.copy(alpha = if (state == "recording") .19f else .08f), CircleShape)
            .border(1.dp, colors.accent.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(innerSize).background(Brush.linearGradient(listOf(colors.accent, colors.accent.copy(alpha = .78f))), CircleShape)
            .testTag("voice_first_button")
            .semantics {
                contentDescription = buttonDescription
                role = Role.Button
                stateDescription = statusText
                if (!enabled) disabled()
                onClick {
                    if (!enabled) false else {
                        if (state == "recording" || state == "release") finish() else begin()
                        true
                    }
                }
            }
            .pointerInput(recorder) {
                var started = false
                var longPressTriggered = false
                detectTapGestures(
                    onPress = {
                        started = false
                        longPressTriggered = false
                        val released = tryAwaitRelease()
                        when {
                            started -> if (released) currentFinish() else currentCancel()
                            released && !longPressTriggered && currentEnabled -> currentOnShortTap()
                        }
                    },
                    onLongPress = {
                        longPressTriggered = true
                        started = currentBegin()
                    },
                )
            }, contentAlignment = Alignment.Center) {
            Icon(Glyph.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(if (compactControl) 24.dp else 58.dp))
        }
        }
        Spacer(Modifier.height(if (compactControl) 2.dp else 16.dp))
        if (transcript.isNotBlank()) Text(transcript, modifier = Modifier.testTag("voice_first_transcript"),
            textAlign = TextAlign.Center, maxLines = if (compactControl) 1 else Int.MAX_VALUE,
            style = if (compactControl) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium)
        if (!compactControl && answer.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(answer, modifier = Modifier.testTag("voice_first_answer"), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
        }
        if (!compactControl) TextButton(onClick = {
            if (memoryConsent) {
                settings.edit().putBoolean("gemini-persisted-memory-v1", false).apply()
                memoryConsent = false
            } else memoryConsentDialog = true
        }) { Text(if (memoryConsent) s(R.string.agent_memory_revoke) else s(R.string.agent_memory_allow)) }
    }
}

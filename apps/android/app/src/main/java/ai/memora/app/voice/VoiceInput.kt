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
import ai.memora.app.ui.colors
import ai.memora.app.ui.s

@Composable
fun VoiceInput(language: String, draft: String, onDraft: (String) -> Unit, showText: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val engine = remember(context) { AndroidVoiceEngine(context) }
    var state by remember { mutableStateOf(VoiceState.Idle) }
    var denied by remember { mutableStateOf(false) }
    var tooLong by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraft by rememberUpdatedState(onDraft)
    val currentShowText by rememberUpdatedState(showText)
    val session = remember(engine) {
        VoiceSession(engine, { state = it }, { text ->
            val combined = if (currentDraft.isBlank()) text else currentDraft.trimEnd() + "\n" + text
            if (combined.length > 2000) tooLong = true
            else { currentOnDraft(combined); currentShowText() }
        })
    }
    val locale = when (language) { "az" -> "az-AZ"; "en" -> "en-US"; else -> "ru-RU" }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted && visible && (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) session.start(locale)
    }
    DisposableEffect(session, lifecycle) {
        visible = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.cancel() }
        lifecycle?.addObserver(observer)
        onDispose { visible = false; lifecycle?.removeObserver(observer); session.cancel() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s(R.string.voice_title), style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Text(s(R.string.voice_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        val busy = state == VoiceState.Starting || state == VoiceState.Listening || state == VoiceState.Processing
        val status = when {
            denied -> R.string.voice_denied
            tooLong -> R.string.voice_too_long
            state == VoiceState.Unavailable -> R.string.voice_unavailable
            state == VoiceState.Error -> R.string.voice_error
            state == VoiceState.Starting -> R.string.voice_starting
            state == VoiceState.Listening -> R.string.voice_listening
            state == VoiceState.Processing -> R.string.voice_processing
            else -> null
        }
        if (status != null) Text(s(status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("voice_status"))
        if (!busy) Button(onClick = {
            denied = false; tooLong = false
            if (!runCatching { engine.available() }.getOrDefault(false)) session.start(locale)
            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) session.start(locale)
            else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("voice_start")) { Text(s(R.string.voice_start)) }
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ session.stop() }, Modifier.weight(1f).testTag("voice_stop"), enabled = state != VoiceState.Processing) { Text(s(R.string.voice_stop)) }
                OutlinedButton({ session.cancel() }, Modifier.weight(1f).testTag("voice_cancel")) { Text(s(R.string.task_cancel)) }
            }
        }
        TextButton(showText, Modifier.fillMaxWidth()) { Text(s(R.string.go_text)) }
    }
}

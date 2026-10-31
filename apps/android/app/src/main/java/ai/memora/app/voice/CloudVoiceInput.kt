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
import ai.memora.app.ui.s
import kotlinx.coroutines.*

@Composable
fun CloudVoiceInput(gatewayOverride: CloudVoiceGateway? = null, onText: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val defaultGateway = remember(context) { FirebaseCloudVoiceGateway(context) }
    val gateway = gatewayOverride ?: defaultGateway
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("idle") }
    var job by remember { mutableStateOf<Job?>(null) }
    var visible by remember { mutableStateOf(true) }
    var permissionPending by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var inputTranscript by remember { mutableStateOf("") }
    var outputTranscript by remember { mutableStateOf("") }

    fun stop() {
        permissionPending = false
        if (job != null) {
            state = "stopping"
            job?.cancel()
        } else state = "idle"
    }
    fun start() {
        if (job != null || !visible) return
        val attempt = ++generation
        inputTranscript = ""
        outputTranscript = ""
        state = "connecting"
        // Store the job before it can complete: a synchronous connection failure must
        // not leave a completed job blocking all subsequent attempts.
        job = scope.launch(start = CoroutineStart.LAZY) {
            var handle: CloudVoiceHandle? = null
            var outcome = "idle"
            try {
                withTimeout(60_000) {
                    val connected = gateway.connect { input, output ->
                        scope.launch {
                            if (attempt == generation && (state == "connecting" || state == "active")) {
                                if (input != null) inputTranscript = (inputTranscript + input).takeLast(4000)
                                if (output != null) outputTranscript = (outputTranscript + output).takeLast(4000)
                            }
                        }
                    }
                    handle = connected
                    ensureActive()
                    if (attempt != generation || !visible) return@withTimeout
                    connected.begin()
                    ensureActive()
                    if (attempt != generation || !visible) return@withTimeout
                    state = "active"
                    while (isActive && !connected.isClosed()) delay(500)
                    if (connected.isClosed()) outcome = "error"
                }
            } catch (_: TimeoutCancellationException) { outcome = "ended" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { outcome = "error" }
            finally {
                val connection = handle
                if (connection != null) {
                    runCatching { connection.stop() }
                    withContext(NonCancellable) { runCatching { connection.close() } }
                }
                if (attempt == generation) { job = null; state = outcome }
            }
        }
        job?.start()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = permissionPending
        permissionPending = false
        if (requested && granted && visible && (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) start()
        else if (requested && !granted) state = "denied"
    }
    DisposableEffect(lifecycle) {
        visible = true
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { confirmation = false; stop() } }
        lifecycle?.addObserver(observer)
        onDispose { visible = false; lifecycle?.removeObserver(observer); stop() }
    }
    if (confirmation) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text(s(R.string.cloud_voice_consent_title)) },
        text = { Text(s(R.string.cloud_voice_consent_body)) },
        confirmButton = { TextButton(onClick = {
            confirmation = false
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else { permissionPending = true; launcher.launch(Manifest.permission.RECORD_AUDIO) }
        }, modifier = Modifier.testTag("cloud_voice_confirm")) { Text(s(R.string.cloud_voice_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation = false }, modifier = Modifier.testTag("cloud_voice_cancel")) { Text(s(R.string.task_cancel)) } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (inputTranscript.isNotBlank()) Text(s(R.string.cloud_voice_you) + ": " + inputTranscript, modifier = Modifier.testTag("cloud_voice_input_transcript"))
        if (outputTranscript.isNotBlank()) Text(s(R.string.cloud_voice_assistant) + ": " + outputTranscript, modifier = Modifier.testTag("cloud_voice_output_transcript"))
        when (state) {
            "connecting" -> Text(s(R.string.cloud_voice_connecting), modifier = Modifier.testTag("cloud_voice_status"))
            "active" -> Text(s(R.string.cloud_voice_active), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("cloud_voice_status"))
            "stopping" -> Text(s(R.string.cloud_voice_stopping), modifier = Modifier.testTag("cloud_voice_status"))
            "denied" -> Text(s(R.string.voice_denied), modifier = Modifier.testTag("cloud_voice_status"))
            "ended" -> Text(s(R.string.cloud_voice_timeout), modifier = Modifier.testTag("cloud_voice_status"))
            "error" -> Text(s(R.string.cloud_voice_error), modifier = Modifier.testTag("cloud_voice_status"))
        }
        if (state == "denied" && onText != null) {
            TextButton(onClick = onText, modifier = Modifier.fillMaxWidth().testTag("cloud_voice_text_alternative")) { Text(s(R.string.go_text)) }
        }
        if (state == "connecting" || state == "active" || state == "stopping") {
            OutlinedButton(onClick = { stop() }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cloud_voice_stop"), enabled = state != "stopping") { Text(s(R.string.cloud_voice_stop)) }
        } else {
            OutlinedButton(onClick = { confirmation = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cloud_voice_start")) { Text(s(R.string.cloud_voice_start)) }
        }
    }
}

package ai.memora.app

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ai.memora.app.reminders.TaskReminders
import ai.memora.app.reminders.AttentionNotifications
import ai.memora.app.data.protectedstore.AttentionSettingsRepository
import ai.memora.app.data.protectedstore.AttentionSettingsRow
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.ui.SectionHeading
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class DevicePermissionState(val microphone: Boolean, val notifications: Boolean, val exactAlarms: Boolean)

fun readDevicePermissionState(context: Context): DevicePermissionState = DevicePermissionState(
    microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    notifications = TaskReminders.available(context),
    exactAlarms = TaskReminders.exactAlarmAvailable(context),
)

/** Permission changes are made in Android; returning refreshes rather than assuming success. */
@Composable
fun DevicePermissionSettings() {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    var state by remember { mutableStateOf<DevicePermissionState?>(null) }
    var openError by remember { mutableStateOf(false) }
    fun refresh() { state = runCatching { readDevicePermissionState(context) }.getOrNull() }
    DisposableEffect(context, lifecycle) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    fun openNotifications() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        openError = runCatching { context.startActivity(intent); false }.getOrDefault(true)
    }
    fun openExactAlarms() {
        val intent = if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val opened = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        openError = if (opened) false else runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
            false
        }.getOrDefault(true)
    }
    fun openMicrophone() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        openError = runCatching { context.startActivity(intent); false }.getOrDefault(true)
    }
    DevicePermissionControls(state, openError, ::openMicrophone, ::openNotifications, ::openExactAlarms)
}

/** Independent, disabled-by-default consent for private, content-free daily local attention. */
@Composable
fun AttentionSettingsControls() {
    val context = LocalContext.current
    val database = rememberOptionalUiAgentDatabase()
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val coroutineScope = rememberCoroutineScope()
    var settings by remember(scopeId) { mutableStateOf(defaultAttentionSettings(scopeId)) }
    var ready by remember(scopeId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var notificationsAvailable by remember { mutableStateOf(false) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }

    fun load() {
        coroutineScope.launch {
            busy = true
            error = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val db = database ?: error("protected_attention_unavailable")
                    val repository = AttentionSettingsRepository(db)
                    repository.settings(scopeId) ?: defaultAttentionSettings(scopeId)
                }
                settings = result
                ready = true
                error = false
                notificationsAvailable = AttentionNotifications.available(context)
                if (notificationsAvailable) notificationPermissionDenied = false
                withContext(Dispatchers.IO) { AttentionNotifications.schedule(context, scopeId, databaseOverride = database) }
            } catch (_: Exception) {
                error = true
            } finally { busy = false }
        }
    }

    fun save(enabled: Boolean = settings.enabled, time: String = settings.briefingTimeLocal,
        quietStart: Int = settings.quietStartMinute, quietEnd: Int = settings.quietEndMinute) {
        coroutineScope.launch {
            busy = true
            error = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val db = database ?: error("protected_attention_unavailable")
                    AttentionSettingsRepository(db).savePreferences(scopeId, enabled, time, quietStart, quietEnd)
                }
                settings = result
                ready = true
                notificationsAvailable = AttentionNotifications.available(context)
                withContext(Dispatchers.IO) {
                    if (enabled) AttentionNotifications.schedule(context, scopeId, databaseOverride = database)
                    else AttentionNotifications.cancel(context)
                }
            } catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save(enabled = true)
        else notificationPermissionDenied = true
    }
    LaunchedEffect(scopeId) { load() }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(context, lifecycle, scopeId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) load() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    SectionHeading(s(R.string.attention_settings_title))
    Text(s(R.string.attention_settings_explainer), style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(s(R.string.attention_settings_enabled), modifier = Modifier.weight(1f))
        Switch(
            checked = settings.enabled,
            enabled = ready && !busy,
            onCheckedChange = { enable ->
                if (!enable) save(enabled = false)
                else if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionDenied = false
                    AttentionNotifications.ensureChannel(context)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else save(enabled = true)
            },
            modifier = Modifier.testTag("attention_enabled"),
        )
    }
    if (notificationPermissionDenied) Text(s(R.string.attention_settings_permission_denied), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("attention_permission_denied"))
    if (settings.enabled) {
        if (!notificationsAvailable) Text(s(R.string.attention_settings_notifications_unavailable),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("attention_notifications_unavailable"))
        val briefing = formatMinute(settings.briefingTimeLocal.let { runCatching { java.time.LocalTime.parse(it) }.getOrDefault(java.time.LocalTime.of(9, 0)) })
        val quietStart = formatMinute(minuteTime(settings.quietStartMinute))
        val quietEnd = formatMinute(minuteTime(settings.quietEndMinute))
        OutlinedButton(onClick = {
            val time = java.time.LocalTime.parse(settings.briefingTimeLocal)
            TimePickerDialog(context, { _, hour, minute -> save(time = formatMinute(java.time.LocalTime.of(hour, minute))) },
                time.hour, time.minute, true).show()
        }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("attention_briefing_time")) {
            Text(LocalStrings.current.getString(R.string.attention_settings_time, briefing))
        }
        OutlinedButton(onClick = {
            val time = minuteTime(settings.quietStartMinute)
            TimePickerDialog(context, { _, hour, minute -> save(quietStart = hour * 60 + minute) }, time.hour, time.minute, true).show()
        }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("attention_quiet_start")) {
            Text(LocalStrings.current.getString(R.string.attention_settings_quiet_start, quietStart))
        }
        OutlinedButton(onClick = {
            val time = minuteTime(settings.quietEndMinute)
            TimePickerDialog(context, { _, hour, minute -> save(quietEnd = hour * 60 + minute) }, time.hour, time.minute, true).show()
        }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("attention_quiet_end")) {
            Text(LocalStrings.current.getString(R.string.attention_settings_quiet_end, quietEnd))
        }
    }
    if (!ready && !error) Text(s(R.string.attention_settings_loading), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("attention_settings_loading"))
    if (error) {
        Text(s(R.string.attention_settings_error), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("attention_settings_error"))
        OutlinedButton(onClick = { load() }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("attention_settings_retry")) {
            Text(s(R.string.settings_retry))
        }
    }
}

private fun defaultAttentionSettings(scopeId: String) = AttentionSettingsRow(scopeId, false, "09:00", 22 * 60, 8 * 60,
    null, null, null, null, 0)

private fun minuteTime(minutes: Int) = java.time.LocalTime.of((minutes.coerceIn(0, 1439)) / 60, minutes.coerceIn(0, 1439) % 60)

private fun formatMinute(time: java.time.LocalTime) = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

@Composable
fun DevicePermissionControls(state: DevicePermissionState?, openError: Boolean, openMicrophone: () -> Unit,
    openNotifications: () -> Unit, openExactAlarms: () -> Unit) {
    SectionHeading(s(R.string.permission_settings_title))
    Text(s(R.string.permission_settings_explanation), style = MaterialTheme.typography.bodySmall)
    val unknown = s(R.string.permission_unknown)
    val allowed = s(R.string.permission_allowed)
    val unavailable = s(R.string.permission_unavailable)
    Text(s(R.string.permission_microphone) + ": " + (state?.let { if (it.microphone) allowed else unavailable } ?: unknown), modifier = Modifier.testTag("permission_microphone_status"))
    OutlinedButton(onClick = openMicrophone, modifier = Modifier.fillMaxWidth().testTag("permission_microphone_settings")) { Text(s(R.string.permission_open_app)) }
    Text(s(R.string.permission_notifications) + ": " + (state?.let { if (it.notifications) allowed else unavailable } ?: unknown), modifier = Modifier.testTag("permission_notifications_status"))
    OutlinedButton(onClick = openNotifications, modifier = Modifier.fillMaxWidth().testTag("permission_notification_settings")) { Text(s(R.string.permission_open_notifications)) }
    Text(s(R.string.permission_exact_alarms) + ": " + (state?.let { if (it.exactAlarms) allowed else unavailable } ?: unknown),
        modifier = Modifier.testTag("permission_exact_alarms_status"))
    if (state?.exactAlarms != true) {
        OutlinedButton(onClick = openExactAlarms, modifier = Modifier.fillMaxWidth().testTag("permission_exact_alarms_settings")) {
            Text(s(R.string.permission_open_exact_alarms))
        }
    }
    Text(s(R.string.permission_exact_alarm_explanation), style = MaterialTheme.typography.bodySmall)
    Text(s(R.string.permission_notification_limits), style = MaterialTheme.typography.bodySmall)
    if (openError) Text(s(R.string.permission_open_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("permission_settings_error"))
}

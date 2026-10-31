package ai.memora.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import ai.memora.app.reminders.TaskReminders
import ai.memora.app.ui.SectionHeading
import ai.memora.app.ui.s

data class DevicePermissionState(val microphone: Boolean, val notifications: Boolean)

fun readDevicePermissionState(context: Context): DevicePermissionState = DevicePermissionState(
    microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    notifications = TaskReminders.available(context),
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
    fun openMicrophone() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        openError = runCatching { context.startActivity(intent); false }.getOrDefault(true)
    }
    DevicePermissionControls(state, openError, ::openMicrophone, ::openNotifications)
}

@Composable
fun DevicePermissionControls(state: DevicePermissionState?, openError: Boolean, openMicrophone: () -> Unit, openNotifications: () -> Unit) {
    SectionHeading(s(R.string.permission_settings_title))
    Text(s(R.string.permission_settings_explanation), style = MaterialTheme.typography.bodySmall)
    val unknown = s(R.string.permission_unknown)
    val allowed = s(R.string.permission_allowed)
    val unavailable = s(R.string.permission_unavailable)
    Text(s(R.string.permission_microphone) + ": " + (state?.let { if (it.microphone) allowed else unavailable } ?: unknown), modifier = Modifier.testTag("permission_microphone_status"))
    OutlinedButton(onClick = openMicrophone, modifier = Modifier.fillMaxWidth().testTag("permission_microphone_settings")) { Text(s(R.string.permission_open_app)) }
    Text(s(R.string.permission_notifications) + ": " + (state?.let { if (it.notifications) allowed else unavailable } ?: unknown), modifier = Modifier.testTag("permission_notifications_status"))
    OutlinedButton(onClick = openNotifications, modifier = Modifier.fillMaxWidth().testTag("permission_notification_settings")) { Text(s(R.string.permission_open_notifications)) }
    Text(s(R.string.permission_notification_limits), style = MaterialTheme.typography.bodySmall)
    if (openError) Text(s(R.string.permission_open_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("permission_settings_error"))
}

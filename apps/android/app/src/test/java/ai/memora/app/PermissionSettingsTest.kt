package ai.memora.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.reminders.TaskReminders
import ai.memora.app.reminders.ReminderStatus
import ai.memora.app.domain.Task
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ru-w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PermissionSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusReflectsRuntimePermissionsAndBlockedReminderChannel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOf(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadow.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(readDevicePermissionState(app).microphone)
        assertTrue(readDevicePermissionState(app).notifications)
        manager.createNotificationChannel(NotificationChannel(TaskReminders.CHANNEL, "Test", NotificationManager.IMPORTANCE_NONE))
        assertFalse(readDevicePermissionState(app).notifications)
        val task = Task("synthetic", "local", "Synthetic", false, 1, "2090-01-01T09:00", "Asia/Baku", true)
        assertEquals(ReminderStatus.Denied, TaskReminders.schedule(app, task, 0))
        shadow.denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(readDevicePermissionState(app).microphone)
        manager.deleteNotificationChannel(TaskReminders.CHANNEL)
        shadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(readDevicePermissionState(app).notifications)
    }

    @Test fun controlsShowUncertaintyAndOpenOnlyWhenRequested() {
        val status = mutableStateOf<DevicePermissionState?>(null)
        var microphoneOpened = 0
        var notificationsOpened = 0
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) {
                MemoraTheme("light") { Column { DevicePermissionControls(status.value, true, { microphoneOpened++ }, { notificationsOpened++ }) } }
            }
        }
        compose.onNodeWithTag("permission_microphone_status").assertTextContains("Не удалось определить", substring = true)
        compose.onNodeWithTag("permission_settings_error").assertExists()
        compose.runOnIdle { assertEquals(0, microphoneOpened); assertEquals(0, notificationsOpened); status.value = DevicePermissionState(false, true) }
        compose.onNodeWithTag("permission_microphone_status").assertTextContains("Не разрешено", substring = true)
        compose.onNodeWithTag("permission_notifications_status").assertTextContains("Разрешено", substring = true)
        compose.onNodeWithTag("permission_microphone_settings").performClick()
        compose.onNodeWithTag("permission_notification_settings").performClick()
        compose.runOnIdle { assertEquals(1, microphoneOpened); assertEquals(1, notificationsOpened) }
    }
}

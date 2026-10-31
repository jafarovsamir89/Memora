package ai.memora.app.reminders

import ai.memora.app.domain.Task
import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskReminderTest {
    @Test fun futureReminderIsScheduledAndCompletionCancelsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val task = Task("abc", "local", "Synthetic", false, 1, "2030-10-31T09:00", "Asia/Baku", true)
        assertEquals(ReminderStatus.Scheduled, TaskReminders.schedule(context, task, 0, true))
        assertEquals(1, alarm.scheduledAlarms.size)
        assertEquals(ReminderStatus.None, TaskReminders.schedule(context, task.copy(completed = true), 0, true))
        assertEquals(0, alarm.scheduledAlarms.size)
    }

    @Test fun deniedOrPastReminderDoesNotSchedule() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val task = Task("past", "local", "Synthetic", false, 1, "2020-01-01T09:00", "Asia/Baku", true)
        assertEquals(ReminderStatus.Past, TaskReminders.schedule(context, task, System.currentTimeMillis(), true))
        assertEquals(ReminderStatus.Denied, TaskReminders.schedule(context, task.copy(dueLocal = "2090-01-01T09:00"), 0, false))
    }
}

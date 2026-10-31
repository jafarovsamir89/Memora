package ai.memora.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import ai.memora.app.R
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.StoreRead
import ai.memora.app.data.TaskStoreKey
import ai.memora.app.domain.Task
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

enum class ReminderStatus { None, Scheduled, Past, Denied, Invalid }

object TaskReminders {
    private const val CHANNEL = "task-reminders"
    private const val MAX_LATE_MS = 60 * 60 * 1000L
    private fun pending(context: Context, id: String, due: Long = 0): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java)
            .setData(Uri.Builder().scheme("memora").authority("task").appendPath(id).build())
            .putExtra("due", due)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun available(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun dueAt(task: Task): Long? {
        return try {
            val local = LocalDateTime.parse(task.dueLocal ?: return null)
            val zone = ZoneId.of(task.dueZone ?: return null)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) null else local.toInstant(offsets.single()).toEpochMilli()
        } catch (_: Exception) { null }
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
    }

    fun schedule(context: Context, task: Task, now: Long = System.currentTimeMillis(), permitted: Boolean = available(context)): ReminderStatus {
        cancel(context, task.id)
        if (!task.remind || task.completed) return ReminderStatus.None
        val due = dueAt(task) ?: return ReminderStatus.Invalid
        if (due <= now) return ReminderStatus.Past
        if (!permitted) return ReminderStatus.Denied
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(context, task.id, due))
        return ReminderStatus.Scheduled
    }

    fun reschedule(context: Context, tasks: List<Task>) { tasks.forEach { schedule(context, it) } }

    fun deliver(context: Context, task: Task, due: Long, now: Long = System.currentTimeMillis()) {
        if (!available(context) || task.completed || !task.remind || dueAt(task) != due || now < due || now - due > MAX_LATE_MS) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, ai.memora.app.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_memora)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(task.id, 0, notification)
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val due = intent.getLongExtra("due", 0)
        val pending = goAsync()
        Thread {
            try {
                val store = EncryptedTaskStore(File(context.filesDir, "tasks-prototype.bin"), TaskStoreKey::get)
                val task = (store.load() as? StoreRead.Ready)?.tasks?.find { it.id == id }
                if (task != null) TaskReminders.deliver(context, task, due)
            } catch (_: Exception) { /* Unavailable storage or revoked permission: no notification. */ }
            finally { pending.finish() }
        }.start()
    }
}

class TaskRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                val store = EncryptedTaskStore(File(context.filesDir, "tasks-prototype.bin"), TaskStoreKey::get)
                val tasks = (store.load() as? StoreRead.Ready)?.tasks ?: return@Thread
                TaskReminders.reschedule(context, tasks)
            } catch (_: Exception) { /* Retry when the application is opened. */ }
            finally { pending.finish() }
        }.start()
    }
}

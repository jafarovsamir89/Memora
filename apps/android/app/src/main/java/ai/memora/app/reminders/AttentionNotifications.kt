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
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import ai.memora.app.R
import ai.memora.app.data.AtomicAppearanceStore
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.StoreRead
import ai.memora.app.data.TaskStoreKey
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.AttentionSettingsRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.domain.Task
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking

internal object AttentionNotifications {
    const val CHANNEL = "daily-attention"
    const val ACTION_FIRE = "ai.memora.app.action.ATTENTION_FIRE"
    const val ACTION_SNOOZE = "ai.memora.app.action.ATTENTION_SNOOZE"
    const val ACTION_DISMISS = "ai.memora.app.action.ATTENTION_DISMISS"
    const val EXTRA_KIND = "attention-kind"
    const val KIND_DAILY = "daily"
    const val KIND_SNOOZE = "snooze"
    private const val NOTIFICATION_ID = 8207
    private const val SNOOZE_MILLIS = 60 * 60 * 1000L
    private const val ALARM_WINDOW_MILLIS = 15 * 60 * 1000L

    fun notificationPermissionGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun available(context: Context): Boolean = notificationPermissionGranted(context) && channelAvailable(context)

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.attention_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun channelAvailable(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun pending(context: Context, kind: String, action: String): PendingIntent {
        val uri = Uri.parse("memora://attention/$action/$kind")
        val intent = Intent(context, AttentionNotificationReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(context, uri.toString().hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, KIND_DAILY, ACTION_FIRE))
        manager.cancel(pending(context, KIND_SNOOZE, ACTION_FIRE))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun schedule(context: Context, scopeId: String, nowMillis: Long = System.currentTimeMillis(), databaseOverride: AgentDatabase? = null) {
        val database = databaseOverride ?: runCatching { AgentDatabase.open(context) }.getOrNull() ?: return
        val row = runCatching { runBlocking { AttentionSettingsRepository(database).settings(scopeId) } }.getOrNull()
        if (row == null || !row.enabled || !notificationPermissionGranted(context) || !channelAvailable(context)) {
            cancel(context)
            return
        }
        val zone = ZoneId.systemDefault()
        val dailyAt = runCatching {
            AttentionEngine.nextTriggerMillis(nowMillis, LocalTime.parse(row.briefingTimeLocal),
                row.quietStartMinute, row.quietEndMinute, zone, row.lastNotificationDate?.let(LocalDate::parse))
        }.getOrNull() ?: return
        val snoozeAt = row.snoozedUntilMillis?.takeIf { it > nowMillis }
        val kind = if (snoozeAt != null && snoozeAt <= dailyAt) KIND_SNOOZE else KIND_DAILY
        val triggerAt = if (kind == KIND_SNOOZE) requireNotNull(snoozeAt) else dailyAt
        context.getSystemService(AlarmManager::class.java).setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            ALARM_WINDOW_MILLIS,
            pending(context, kind, ACTION_FIRE),
        )
        // Cancel the non-selected request so a previous schedule cannot create a second briefing.
        val other = if (kind == KIND_SNOOZE) KIND_DAILY else KIND_SNOOZE
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, other, ACTION_FIRE))
    }

    fun reconcileFromApp(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val scopeId = AgentVoiceTurnProcessor(app).conversationScopeId()
                schedule(app, scopeId)
            }
        }.start()
    }

    internal fun handle(context: Context, intent: Intent) {
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val database = AgentDatabase.open(context)
        val settings = AttentionSettingsRepository(database)
        val row = runBlocking { settings.settings(scopeId) } ?: return
        if (intent.action == ACTION_DISMISS) {
            val date = LocalDate.now(ZoneId.systemDefault()).toString()
            runBlocking { settings.dismissForDate(scopeId, date) }
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            schedule(context, scopeId)
            return
        }
        if (intent.action == ACTION_SNOOZE) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val deliveredDate = row.lastNotificationDate ?: return
            val candidate = now + SNOOZE_MILLIS
            val allowedAt = AttentionEngine.nextAllowedMillis(candidate, row.quietStartMinute, row.quietEndMinute, zone)
            if (runBlocking { settings.snoozeUntil(scopeId, deliveredDate, allowedAt, now) }) {
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                schedule(context, scopeId, now)
            }
            return
        }
        if (intent.action != ACTION_FIRE || !row.enabled || !notificationPermissionGranted(context) || !channelAvailable(context)) {
            schedule(context, scopeId)
            return
        }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        val date = localNow.toLocalDate().toString()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_DAILY
        val snoozeReplay = kind == KIND_SNOOZE
        if (AttentionEngine.isWithinQuietHours(localNow.toLocalTime(), row.quietStartMinute, row.quietEndMinute)) {
            if (snoozeReplay) {
                val allowedAt = AttentionEngine.nextTriggerMillis(now, localNow.toLocalTime(),
                    row.quietStartMinute, row.quietEndMinute, zone)
                row.snoozedForDate?.let { runBlocking { settings.snoozeUntil(scopeId, it, allowedAt, now) } }
            }
            schedule(context, scopeId, now)
            return
        }
        if (row.dismissedDate == date) {
            schedule(context, scopeId, now)
            return
        }
        if (snoozeReplay) {
            if (row.snoozedUntilMillis == null || row.snoozedUntilMillis > now || row.snoozedForDate != row.lastNotificationDate) {
                schedule(context, scopeId, now)
                return
            }
        } else {
            if (row.lastNotificationDate == date || row.snoozedUntilMillis?.let { it > now } == true) {
                schedule(context, scopeId, now)
                return
            }
        }

        val suggestions = loadSuggestions(context, database, scopeId, now, zone)
        if (suggestions.isEmpty()) {
            schedule(context, scopeId, now)
            return
        }
        postGenericNotification(context)
        if (!runBlocking { settings.recordNotificationDate(scopeId, date, now, snoozeReplay) }) {
            schedule(context, scopeId, now)
            return
        }
        schedule(context, scopeId, now)
    }

    private fun loadSuggestions(context: Context, database: AgentDatabase, scopeId: String, now: Long, zone: ZoneId): List<AttentionSuggestion> = runBlocking {
        val protected = ProtectedMemoryRepository(database).tasks().tasks.filter { it.scopeId == scopeId }
        val legacyRead = EncryptedTaskStore(File(context.filesDir, "tasks-prototype.bin"), TaskStoreKey::get).load()
        val legacy = (legacyRead as? StoreRead.Ready)?.tasks.orEmpty()
        check(legacyRead !is StoreRead.Unavailable) { "attention_task_source_unavailable" }
        val tasksById = legacy.associateBy { it.id }.toMutableMap().apply { protected.forEach { put(it.id, it) } }
        val cases = ProtectedCaseRepository(database).openCases(scopeId, limit = 100)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays(1).atStartOfDay().toString()
        val until = today.plusDays(9).atStartOfDay().toString()
        val events = ProtectedDomainRepository(database).calendarBetween(scopeId, from, until)
        AttentionEngine.suggest(scopeId, tasksById.values.toList(), cases, events, now, zone)
    }

    private fun postGenericNotification(context: Context) {
        ensureChannel(context)
        val language = runCatching {
            AtomicAppearanceStore(File(context.filesDir, "appearance-settings-v1")).read().language
        }.getOrDefault("az")
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, ai.memora.app.MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_memora)
            .setContentTitle(localized.getString(R.string.attention_notification_title))
            .setContentText(localized.getString(R.string.attention_notification_body))
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_memora)
            .setContentTitle(localized.getString(R.string.attention_notification_title))
            .setContentText(localized.getString(R.string.attention_notification_body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.attention_snooze),
                pending(context, KIND_DAILY, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.attention_dismiss),
                pending(context, KIND_DAILY, ACTION_DISMISS)).build())
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

class AttentionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                synchronized(AttentionNotificationReceiver::class.java) {
                    runCatching { AttentionNotifications.handle(app, intent) }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.time.LocalTime

/** User attention preferences and controls. This store never persists source titles or summaries. */
internal class AttentionSettingsRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()

    suspend fun settings(scopeId: String): AttentionSettingsRow? {
        require(scopeId.isNotBlank())
        return dao.attentionSettings(scopeId)
    }

    suspend fun savePreferences(
        scopeId: String,
        enabled: Boolean,
        briefingTimeLocal: String,
        quietStartMinute: Int,
        quietEndMinute: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): AttentionSettingsRow = db.withTransaction {
        require(scopeId.isNotBlank())
        require(LocalTime.parse(briefingTimeLocal).toString() == briefingTimeLocal) { "invalid_briefing_time" }
        require(quietStartMinute in 0..1439 && quietEndMinute in 0..1439) { "invalid_quiet_hours" }
        val prior = dao.attentionSettings(scopeId)
        val next = (prior ?: AttentionSettingsRow(
            scopeId = scopeId,
            enabled = false,
            briefingTimeLocal = "09:00",
            quietStartMinute = 22 * 60,
            quietEndMinute = 8 * 60,
            lastNotificationDate = null,
            dismissedDate = null,
            snoozedUntilMillis = null,
            snoozedForDate = null,
            updatedAtMillis = nowMillis,
        )).copy(
            enabled = enabled,
            briefingTimeLocal = briefingTimeLocal,
            quietStartMinute = quietStartMinute,
            quietEndMinute = quietEndMinute,
            snoozedUntilMillis = if (enabled) prior?.snoozedUntilMillis else null,
            snoozedForDate = if (enabled) prior?.snoozedForDate else null,
            updatedAtMillis = nowMillis,
        )
        dao.putAttentionSettings(next)
        check(dao.attentionSettings(scopeId) == next) { "attention_settings_readback_mismatch" }
        next
    }

    suspend fun recordNotificationDate(scopeId: String, localDate: String, nowMillis: Long, snoozeReplay: Boolean = false): Boolean = db.withTransaction {
        require(scopeId.isNotBlank())
        val current = dao.attentionSettings(scopeId) ?: return@withTransaction false
        if (!current.enabled || current.dismissedDate == localDate) return@withTransaction false
        if (!snoozeReplay && current.lastNotificationDate == localDate) return@withTransaction false
        if (snoozeReplay) {
            val due = current.snoozedUntilMillis ?: return@withTransaction false
            if (due > nowMillis || current.snoozedForDate != current.lastNotificationDate) return@withTransaction false
        }
        val next = current.copy(lastNotificationDate = localDate, snoozedUntilMillis = null, snoozedForDate = null, updatedAtMillis = nowMillis)
        dao.putAttentionSettings(next)
        check(dao.attentionSettings(scopeId) == next) { "attention_delivery_readback_mismatch" }
        true
    }

    suspend fun dismissForDate(scopeId: String, localDate: String, nowMillis: Long = System.currentTimeMillis()) = db.withTransaction {
        require(scopeId.isNotBlank() && localDate.isNotBlank())
        val current = dao.attentionSettings(scopeId) ?: return@withTransaction
        val next = current.copy(dismissedDate = localDate, snoozedUntilMillis = null, snoozedForDate = null, updatedAtMillis = nowMillis)
        dao.putAttentionSettings(next)
        check(dao.attentionSettings(scopeId) == next) { "attention_dismiss_readback_mismatch" }
    }

    suspend fun snoozeUntil(scopeId: String, notificationDate: String, untilMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean = db.withTransaction {
        require(scopeId.isNotBlank() && notificationDate.isNotBlank() && untilMillis > nowMillis)
        val current = dao.attentionSettings(scopeId) ?: return@withTransaction false
        if (!current.enabled || current.dismissedDate == notificationDate || current.lastNotificationDate != notificationDate) return@withTransaction false
        val next = current.copy(snoozedUntilMillis = untilMillis, snoozedForDate = notificationDate, updatedAtMillis = nowMillis)
        dao.putAttentionSettings(next)
        check(dao.attentionSettings(scopeId) == next) { "attention_snooze_readback_mismatch" }
        true
    }
}

package ai.memora.app.domain

import java.time.Instant
import java.time.ZoneId

/** Resolves an explicit duration using the device clock and the task's local time zone. */
object TaskReminderTime {
    private const val MAX_MINUTES = 525_600

    fun resolveRelative(minutes: Int, zoneId: String, now: Instant): String? {
        if (minutes !in 1..MAX_MINUTES) return null
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        val due = runCatching { now.plusSeconds(minutes * 60L).atZone(zone).toLocalDateTime() }.getOrNull() ?: return null
        if (zone.rules.getValidOffsets(due).size != 1) return null
        return due.toString()
    }
}

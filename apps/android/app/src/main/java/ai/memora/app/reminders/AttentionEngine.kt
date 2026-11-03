package ai.memora.app.reminders

import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.LocalCalendarEventRow
import ai.memora.app.domain.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal enum class AttentionKind { TASK, CASE, CALENDAR }

/** A short-lived, evidence-backed projection. It is rendered in-app but never copied into notifications. */
internal data class AttentionSuggestion(
    val kind: AttentionKind,
    val sourceId: String,
    val title: String,
    val detail: String?,
    val sourceLocalTime: String?,
    val dueAtMillis: Long?,
    val priority: Int,
    val sourceUpdatedAtMillis: Long? = null,
)

internal object AttentionEngine {
    private const val LOOKAHEAD_DAYS = 7L

    fun suggest(
        scopeId: String,
        tasks: List<Task>,
        cases: List<CaseRecordRow>,
        events: List<LocalCalendarEventRow>,
        nowMillis: Long,
        deviceZone: ZoneId,
        limit: Int = 3,
    ): List<AttentionSuggestion> {
        require(scopeId.isNotBlank() && limit in 1..10)
        val now = Instant.ofEpochMilli(nowMillis)
        val horizon = now.plusSeconds(LOOKAHEAD_DAYS * 24 * 60 * 60)
        val suggestions = buildList {
            tasks.asSequence().filter { it.scopeId == scopeId && !it.completed }.forEach { task ->
                val due = task.dueLocal?.let { local -> task.dueZone?.let { zone -> taskInstant(local, zone) } } ?: return@forEach
                if (due.isAfter(horizon)) return@forEach
                val delta = due.toEpochMilli() - nowMillis
                add(AttentionSuggestion(AttentionKind.TASK, task.id, task.title,
                    task.personLabel ?: task.projectLabel, task.dueLocal, due.toEpochMilli(),
                    priority = when { delta < 0 -> 0; delta <= 24 * 60 * 60 * 1000L -> 1; else -> 2 }))
            }
            events.asSequence().filter { it.scopeId == scopeId && it.status == "active" }.forEach { event ->
                val due = calendarInstant(event) ?: return@forEach
                val delta: Long
                val priority: Int
                if (event.timePrecision == "date") {
                    val eventDate = runCatching { LocalDate.parse(event.startLocal) }.getOrNull() ?: return@forEach
                    val eventZone = runCatching { ZoneId.of(event.zoneId) }.getOrNull() ?: return@forEach
                    val localToday = now.atZone(eventZone).toLocalDate()
                    if (eventDate.isBefore(localToday) || eventDate.isAfter(localToday.plusDays(LOOKAHEAD_DAYS))) return@forEach
                    delta = if (eventDate == localToday) 0 else due.toEpochMilli() - nowMillis
                    priority = if (eventDate == localToday || delta <= 24 * 60 * 60 * 1000L) 1 else 2
                } else {
                    if (due.isBefore(now) || due.isAfter(horizon)) return@forEach
                    delta = due.toEpochMilli() - nowMillis
                    priority = if (delta <= 24 * 60 * 60 * 1000L) 1 else 2
                }
                add(AttentionSuggestion(AttentionKind.CALENDAR, event.eventId, event.title, null, event.startLocal,
                    due.toEpochMilli(), priority = priority))
            }
            cases.asSequence().filter {
                it.scopeId == scopeId && it.deletedAt == null && it.status !in CLOSED_CASE_STATUSES
            }.forEach { case ->
                val updated = runCatching { Instant.parse(case.updatedAt).toEpochMilli() }.getOrNull()
                add(AttentionSuggestion(AttentionKind.CASE, case.caseId, case.title, case.nextAction,
                    null, null, priority = 3, sourceUpdatedAtMillis = updated))
            }
        }
        return suggestions.distinctBy { it.kind to it.sourceId }
            .sortedWith(compareBy<AttentionSuggestion> { it.priority }
                .thenBy { if (it.priority < 3) it.dueAtMillis ?: Long.MAX_VALUE else Long.MAX_VALUE }
                .thenByDescending { if (it.priority == 3) it.sourceUpdatedAtMillis ?: Long.MIN_VALUE else Long.MIN_VALUE }
                .thenBy { it.kind.ordinal }
                .thenBy { it.sourceId })
            .take(limit)
    }

    fun nextTriggerMillis(
        nowMillis: Long,
        briefingTime: LocalTime,
        quietStartMinute: Int,
        quietEndMinute: Int,
        zone: ZoneId,
        lastNotificationDate: LocalDate? = null,
    ): Long {
        require(quietStartMinute in 0..1439 && quietEndMinute in 0..1439)
        val current = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val currentDate = current.toLocalDate()
        for (dayOffset in 0L..3L) {
            val date = currentDate.plusDays(dayOffset)
            var local = LocalDateTime.of(date, briefingTime)
            var trigger = resolveRecurringLocal(local, zone)
            val briefingMissedIntoQuietHours = dayOffset == 0L && !trigger.toInstant().isAfter(current.toInstant()) &&
                lastNotificationDate != currentDate && isQuiet(current.toLocalTime(), quietStartMinute, quietEndMinute)
            if (briefingMissedIntoQuietHours) {
                val minute = current.hour * 60 + current.minute
                val endDate = if (quietStartMinute > quietEndMinute && minute >= quietStartMinute) currentDate.plusDays(1) else currentDate
                local = LocalDateTime.of(endDate, LocalTime.of(quietEndMinute / 60, quietEndMinute % 60))
                trigger = resolveRecurringLocal(local, zone)
                if (trigger.toInstant().isAfter(current.toInstant())) return trigger.toInstant().toEpochMilli()
            }
            if (isQuiet(local.toLocalTime(), quietStartMinute, quietEndMinute)) {
                val minute = local.hour * 60 + local.minute
                val endDate = if (quietStartMinute > quietEndMinute && minute >= quietStartMinute) date.plusDays(1) else date
                local = LocalDateTime.of(endDate, LocalTime.of(quietEndMinute / 60, quietEndMinute % 60))
                trigger = resolveRecurringLocal(local, zone)
            }
            if (trigger.toInstant().isAfter(current.toInstant())) return trigger.toInstant().toEpochMilli()
        }
        error("attention_schedule_unresolvable")
    }

    fun isWithinQuietHours(time: LocalTime, quietStartMinute: Int, quietEndMinute: Int): Boolean =
        isQuiet(time, quietStartMinute, quietEndMinute)

    fun nextAllowedMillis(candidateMillis: Long, quietStartMinute: Int, quietEndMinute: Int, zone: ZoneId): Long {
        require(quietStartMinute in 0..1439 && quietEndMinute in 0..1439)
        val candidate = Instant.ofEpochMilli(candidateMillis).atZone(zone)
        if (!isQuiet(candidate.toLocalTime(), quietStartMinute, quietEndMinute)) return candidateMillis
        val endDate = if (quietStartMinute > quietEndMinute &&
            candidate.hour * 60 + candidate.minute >= quietStartMinute) candidate.toLocalDate().plusDays(1)
        else candidate.toLocalDate()
        val quietEnd = LocalTime.of(quietEndMinute / 60, quietEndMinute % 60)
        return resolveRecurringLocal(LocalDateTime.of(endDate, quietEnd), zone).toInstant().toEpochMilli()
    }

    private fun taskInstant(local: String, zoneName: String): Instant? = runCatching {
        val dateTime = LocalDateTime.parse(local)
        val zone = ZoneId.of(zoneName)
        val offsets = zone.rules.getValidOffsets(dateTime)
        if (offsets.size != 1) null else dateTime.atOffset(offsets.single()).toInstant()
    }.getOrNull()

    private fun calendarInstant(event: LocalCalendarEventRow): Instant? = runCatching {
        val zone = ZoneId.of(event.zoneId)
        if (event.timePrecision == "date") {
            LocalDate.parse(event.startLocal).plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant()
        } else {
            val local = LocalDateTime.parse(event.startLocal)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) null else local.atOffset(offsets.single()).toInstant()
        }
    }.getOrNull()

    private fun resolveRecurringLocal(local: LocalDateTime, zone: ZoneId): ZonedDateTime {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.size == 1 -> ZonedDateTime.ofLocal(local, zone, offsets.single())
            offsets.size > 1 -> ZonedDateTime.ofLocal(local, zone, offsets.first())
            else -> {
                val transition = requireNotNull(rules.getTransition(local))
                ZonedDateTime.ofLocal(transition.dateTimeAfter, zone, transition.offsetAfter)
            }
        }
    }

    private fun isQuiet(time: LocalTime, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        val minute = time.hour * 60 + time.minute
        return if (startMinute < endMinute) minute in startMinute until endMinute
        else minute >= startMinute || minute < endMinute
    }

    private val CLOSED_CASE_STATUSES = setOf("completed", "archived", "cancelled")
}

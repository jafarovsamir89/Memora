package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.LocalCalendarEventRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.IconBubble
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException

private enum class CalendarFilter { UPCOMING, PAST, CANCELLED, ALL }

/** Read-only calendar view over the same scoped local events used by assistant tools. */
@Composable
internal fun CalendarScreen(onOpenTarget: (AppRoute) -> Unit = {}) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    var events by remember { mutableStateOf<List<LocalCalendarEventRow>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var selectedName by rememberSaveable { mutableStateOf(CalendarFilter.UPCOMING.name) }
    val selected = CalendarFilter.entries.firstOrNull { it.name == selectedName } ?: CalendarFilter.UPCOMING
    val listState = rememberLazyListState()
    val resources = ai.memora.app.ui.LocalStrings.current
    val locale = LocalLocale.current.platformLocale

    LaunchedEffect(scopeId, reload) {
        loading = true
        unavailable = false
        events = null
        try {
            // Keep the visible history bounded while the lazy list avoids composing all rows at once.
            events = repository.allCalendarEvents(scopeId).takeLast(CALENDAR_VISIBLE_LIMIT).asReversed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            events = null
            unavailable = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(selectedName) { listState.scrollToItem(0) }

    val now = remember(reload, events) { Instant.now() }
    val visibleEvents = remember(events, selected, now) {
        events.orEmpty().filter { event ->
            val cancelled = event.status == "cancelled"
            val isPast = calendarEventIsPast(event, now)
            when (selected) {
                CalendarFilter.ALL -> true
                CalendarFilter.UPCOMING -> !cancelled && !isPast
                CalendarFilter.PAST -> !cancelled && isPast
                CalendarFilter.CANCELLED -> cancelled
            }
        }.let { filtered ->
            when (selected) {
                CalendarFilter.UPCOMING -> filtered.sortedBy { it.startLocal }
                CalendarFilter.PAST, CalendarFilter.CANCELLED, CalendarFilter.ALL -> filtered.sortedByDescending { it.startLocal }
            }
        }
    }
    val filters = listOf(
        CalendarFilter.UPCOMING to s(R.string.calendar_upcoming),
        CalendarFilter.PAST to s(R.string.calendar_past),
        CalendarFilter.CANCELLED to s(R.string.calendar_cancelled),
        CalendarFilter.ALL to s(R.string.all),
    )

    BoxWithConstraints(Modifier.fillMaxSize().testTag("calendar_screen")) {
        val compactEmptyState = maxHeight < 520.dp
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!compactEmptyState) {
                    Text(s(R.string.calendar_hint), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                }
                Filters(filters.map { (filter, label) -> filter.name to label }, selected.name, { selectedName = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(resources.getString(R.string.calendar_result_count, visibleEvents.size),
                        style = MaterialTheme.typography.labelMedium, color = colors.muted,
                        modifier = Modifier.testTag("calendar_result_count"))
                    TextButton(onClick = { reload++ }, modifier = Modifier.testTag("calendar_refresh")) {
                        Text(s(R.string.calendar_refresh))
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).testTag("calendar_event_list"),
                contentPadding = PaddingValues(top = 4.dp, bottom = if (compactEmptyState) 8.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    loading && events == null -> item(key = "calendar_loading") {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().testTag("calendar_loading"))
                    }
                    unavailable -> item(key = "calendar_unavailable") {
                        MemoraCard {
                            Text(s(R.string.capture_storage_error), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { reload++ }, modifier = Modifier.testTag("calendar_retry")) {
                                Text(s(R.string.capture_retry))
                            }
                        }
                    }
                    events.orEmpty().isEmpty() -> item(key = "calendar_empty") {
                        EmptyState(s(R.string.calendar_empty_title), s(R.string.calendar_empty), Glyph.Calendar,
                            "calendar_empty", compact = compactEmptyState)
                    }
                    visibleEvents.isEmpty() -> item(key = "calendar_filter_empty") {
                        EmptyState(s(R.string.calendar_filter_empty), s(R.string.calendar_filter_empty_body), Glyph.Calendar,
                            "calendar_filter_empty", compact = compactEmptyState)
                    }
                }
                items(visibleEvents, key = { it.eventId }) { event ->
                    val target = AppRoute.target(AppTargetKind.CALENDAR, event.eventId)
                    CalendarEventCard(event, locale, target?.let { { onOpenTarget(it) } })
                }
                if (!loading && !unavailable && events.orEmpty().size == CALENDAR_VISIBLE_LIMIT) {
                    item(key = "calendar_history_limit") {
                        Text(s(R.string.calendar_history_limit), style = MaterialTheme.typography.bodySmall, color = colors.muted,
                            modifier = Modifier.testTag("calendar_history_limit"))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: LocalCalendarEventRow, locale: Locale, onClick: (() -> Unit)?) {
    val cancelled = event.status == "cancelled"
    MemoraCard(modifier = Modifier.testTag("calendar_event_${event.eventId}"), onClick = onClick, contentPadding = 16.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Glyph.Calendar, tone = if (cancelled) 2 else 1, size = 44)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = colors.ink, modifier = Modifier.testTag("calendar_event_title_${event.eventId}"))
                Text(calendarEventDateLabel(event, locale), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        if (onClick != null) androidx.compose.material3.Icon(Glyph.Chevron, contentDescription = null, tint = colors.muted)
        }
        if (event.timePrecision == "date") {
            Text(s(R.string.calendar_all_day), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        } else {
            val zone = runCatching { ZoneId.of(event.zoneId) }.getOrNull()
            if (zone != null) Text(ai.memora.app.ui.LocalStrings.current.getString(R.string.calendar_time_zone, zone.id),
                style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (cancelled) Text(s(R.string.calendar_cancelled), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("calendar_event_status_${event.eventId}"))
    }
}

private fun calendarEventIsPast(event: LocalCalendarEventRow, now: Instant): Boolean {
    val zone = runCatching { ZoneId.of(event.zoneId) }.getOrDefault(ZoneId.of("Asia/Baku"))
    return runCatching {
        val localNow = now.atZone(zone).toLocalDateTime()
        if (event.timePrecision == "date") LocalDate.parse(event.startLocal).isBefore(localNow.toLocalDate())
        else LocalDateTime.parse(event.startLocal).isBefore(localNow)
    }.getOrDefault(false)
}

internal fun calendarEventDateLabel(event: LocalCalendarEventRow, locale: Locale): String = runCatching {
    if (event.timePrecision == "date") {
        LocalDate.parse(event.startLocal).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale))
    } else {
        val start = LocalDateTime.parse(event.startLocal)
        val startText = start.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", locale))
        val end = event.endLocal?.let(LocalDateTime::parse)
        if (end == null) startText
        else if (end.toLocalDate() == start.toLocalDate()) "$startText – ${end.format(DateTimeFormatter.ofPattern("HH:mm", locale))}"
        else "$startText – ${end.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))}"
    }
}.getOrDefault(event.startLocal)

private const val CALENDAR_VISIBLE_LIMIT = 200

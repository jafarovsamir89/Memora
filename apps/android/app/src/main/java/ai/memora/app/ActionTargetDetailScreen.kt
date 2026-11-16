package ai.memora.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RelatedDetail(val title: String, val description: String?, val metadata: List<Pair<Int, String>> = emptyList())
private sealed interface RelatedDetailState {
    data object Loading : RelatedDetailState
    data class Found(val value: RelatedDetail) : RelatedDetailState
    data object Missing : RelatedDetailState
    data object Unavailable : RelatedDetailState
}

/** Read-only, owner-scoped screen for the exact stable ID returned by a completed assistant action. */
@Composable
internal fun ActionTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit = {}) {
    when (route.targetKind) {
        AppTargetKind.PERSON -> PersonTargetDetailScreen(route, onOpenTarget)
        AppTargetKind.CASE -> CaseTargetDetailScreen(route, onOpenTarget)
        AppTargetKind.FINANCE -> FinanceTargetDetailScreen(route, onOpenTarget)
        AppTargetKind.TASK -> TaskTargetDetailScreen(route, onOpenTarget)
        AppTargetKind.SOURCE -> SourceTargetDetailScreen(route, onOpenTarget)
        AppTargetKind.PROJECT, AppTargetKind.NOTE -> ProjectNoteTargetDetailScreen(route, onOpenTarget)
        else -> GenericActionTargetDetailScreen(route)
    }
}

@Composable
private fun GenericActionTargetDetailScreen(route: AppRoute) {
    val context = LocalContext.current
    val resources = LocalStrings.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    var state by remember(route, scopeId) { mutableStateOf<RelatedDetailState>(RelatedDetailState.Loading) }

    LaunchedEffect(route, scopeId, database, resources) {
        state = RelatedDetailState.Loading
        state = withContext(Dispatchers.IO) {
            try {
                loadRelatedDetail(database, scopeId, route, resources)
                    ?: RelatedDetailState.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RelatedDetailState.Unavailable
            }
        }
    }

    ScreenColumn(Modifier.testTag("action_target_screen")) {
        when (val current = state) {
            RelatedDetailState.Loading -> {
                ScreenHeading(s(R.string.action_related_details), s(R.string.action_related_loading))
                androidx.compose.material3.CircularProgressIndicator(color = colors.accent, modifier = Modifier.testTag("action_target_loading"))
            }
            RelatedDetailState.Missing -> {
                ScreenHeading(s(R.string.action_related_details), s(R.string.action_related_missing))
                EmptyState(s(R.string.action_related_missing), s(R.string.action_related_missing_body))
            }
            RelatedDetailState.Unavailable -> {
                ScreenHeading(s(R.string.action_related_details), s(R.string.action_related_unavailable))
                EmptyState(s(R.string.action_related_unavailable), s(R.string.action_related_unavailable_body))
            }
            is RelatedDetailState.Found -> {
                ScreenHeading(current.value.title, s(R.string.action_related_details))
                MemoraCard(modifier = Modifier.testTag("action_target_${route.targetId}")) {
                    current.value.description?.takeIf(String::isNotBlank)?.let { body ->
                        Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    }
                    current.value.metadata.forEach { (labelId, value) ->
                        androidx.compose.foundation.layout.Column {
                            Text(s(labelId), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun loadActionCardSummary(
    database: AgentDatabase,
    scope: String,
    targetKind: String?,
    targetId: String?,
    resources: android.content.res.Resources,
): List<String>? {
    val route = AppTargetKind.fromAction(targetKind, targetId) ?: return null
    val details = when (val result = loadRelatedDetail(database, scope, route, resources)) {
        is RelatedDetailState.Found -> result.value
        else -> null
    } ?: return null
    return buildList {
        details.title.takeIf(String::isNotBlank)?.let(::add)
        details.description?.takeIf(String::isNotBlank)?.let { add(it.take(180)) }
        details.metadata.forEach { (labelId, value) ->
            if (value.isNotBlank()) add("${resources.getString(labelId)}: ${value.take(140)}")
        }
    }.take(5)
}

private suspend fun loadRelatedDetail(
    database: AgentDatabase,
    scope: String,
    route: AppRoute,
    resources: android.content.res.Resources,
): RelatedDetailState? {
    val kind = route.targetKind ?: return null
    val id = route.targetId ?: return null
    val repository = ProtectedDomainRepository(database)
    val displayZone = runCatching { ZoneId.of(database.protectedStoreDao().localProfile()?.timeZoneId ?: "Asia/Baku") }
        .getOrDefault(ZoneId.of("Asia/Baku"))
    return when (kind) {
        AppTargetKind.CASE -> ProtectedCaseRepository(database).caseSnapshot(scope, id)?.let { snapshot ->
            val record = snapshot.record
            RelatedDetail(record.title, record.summary, buildList {
                val status = when (record.status) {
                    "waiting" -> R.string.case_status_waiting
                    "completed" -> R.string.case_status_completed
                    "cancelled" -> R.string.case_status_cancelled
                    else -> R.string.case_status_open
                }
                add(R.string.action_related_status to resources.getString(status))
                record.nextAction?.let { add(R.string.action_related_next_action to it) }
                add(R.string.action_related_updated to formatTimestamp(record.updatedAt, displayZone))
            })
        }
        AppTargetKind.FINANCE -> repository.moneyEvent(scope, id)?.let { event ->
            val person = database.protectedStoreDao().person(scope, event.personId)?.displayName
            RelatedDetail(
                title = when (event.kind) {
                    "receipt" -> resources.getString(R.string.action_related_income)
                    "debt" -> resources.getString(R.string.action_related_debt)
                    "expense" -> resources.getString(R.string.action_related_expense)
                    else -> resources.getString(R.string.finance)
                },
                description = event.comment ?: event.source,
                metadata = buildList {
                    add(R.string.action_related_amount to formatMoney(event.minorUnits, event.currency, resources))
                    person?.let { add(R.string.action_related_person to it) }
                    add(R.string.action_related_source to event.source)
                    event.occurredAt?.let { add(R.string.action_related_date to formatTimestamp(it, displayZone)) }
                },
            )
        }
        AppTargetKind.TASK -> ProtectedMemoryRepository(database).tasks().tasks
            .firstOrNull { it.id == id && it.scopeId == scope }
            ?.let { task -> RelatedDetail(task.title, null, buildList {
                add(R.string.action_related_status to resources.getString(if (task.completed) R.string.task_completed else R.string.task_open))
                task.dueLocal?.let { add(R.string.action_related_date to it.replace('T', ' ')) }
                task.personLabel?.let { add(R.string.action_related_person to it) }
            }) }
        AppTargetKind.CALENDAR -> repository.calendarEvent(scope, id)?.let { event ->
            val locale = resources.configuration.locales[0] ?: Locale.getDefault()
            RelatedDetail(event.title, null, buildList {
                add(R.string.action_related_date to calendarEventDateLabel(event, locale))
                add(R.string.action_related_status to resources.getString(
                    if (event.status == "cancelled") R.string.calendar_cancelled else R.string.calendar_active,
                ))
                if (event.timePrecision != "date") add(R.string.calendar_zone_label to event.zoneId)
            })
        }
        AppTargetKind.TABLE -> repository.table(scope, id)?.takeIf { it.deletedAt == null }?.let { table ->
            RelatedDetail(table.title, null, listOf(R.string.action_related_status to table.mode))
        }
        AppTargetKind.MEMORY -> {
            val profileFact = ProtectedProfileMemoryRepository(database).get(scope, id)
            if (profileFact != null) RelatedDetail(profileFact.category, profileFact.fact,
                listOf(R.string.action_related_updated to DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
                    .withZone(displayZone).format(Instant.ofEpochMilli(profileFact.updatedAtMillis))))
            else {
                val dao = database.protectedStoreDao()
                val episode = dao.conversationEpisode(scope, id)
                if (episode != null) RelatedDetail(episode.title, episode.summary)
                else repository.domainObject(scope, id)?.takeIf { it.deletedAt == null }?.let { RelatedDetail(it.title, it.body,
                    listOf(R.string.action_related_status to it.status)) }
            }
        }
        AppTargetKind.PERSON -> repository.domainObject(scope, id)?.takeIf {
            it.kind == ai.memora.app.data.protectedstore.DomainKind.PERSON.name && it.deletedAt == null
        }?.let { RelatedDetail(it.title, it.body, listOf(R.string.person_aliases to resources.getString(R.string.person_aliases))) }
        AppTargetKind.PROJECT -> repository.domainObject(scope, id)?.takeIf {
            it.kind == ai.memora.app.data.protectedstore.DomainKind.PROJECT.name && it.deletedAt == null
        }?.let { RelatedDetail(it.title, it.body, listOf(R.string.projects to resources.getString(R.string.projects_notes_active))) }
        AppTargetKind.NOTE -> repository.domainObject(scope, id)?.takeIf {
            it.kind == ai.memora.app.data.protectedstore.DomainKind.NOTE.name && it.deletedAt == null
        }?.let { RelatedDetail(it.title, it.body, listOf(R.string.notes_title to resources.getString(R.string.projects_notes_active))) }
        AppTargetKind.SOURCE -> null
    }?.let(RelatedDetailState::Found)
}

private fun formatMoney(minorUnits: Long, currency: String, resources: android.content.res.Resources): String =
    "${NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(minorUnits, 2))} $currency"

private fun formatTimestamp(raw: String, zone: ZoneId): String = runCatching {
    val date = runCatching { Instant.parse(raw) }.getOrNull()?.atZone(zone)?.toLocalDateTime()
        ?: LocalDateTime.parse(raw)
    date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
}.getOrDefault(raw.take(32))

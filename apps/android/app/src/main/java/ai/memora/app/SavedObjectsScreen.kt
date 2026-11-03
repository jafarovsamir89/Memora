package ai.memora.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.CaseSnapshot
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.FinancePersonIdentityChoices
import ai.memora.app.data.protectedstore.PersonIdentityLinkRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.launch

private data class PersonStoryEntry(val id: String, val at: String, val sortOrder: Long, val title: String, val detail: String?)

/** Read-only view of the same encrypted people/projects written through the agent tool registry. */
@Composable
internal fun SavedObjectsScreen(kind: DomainKind) {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val database = rememberUiAgentDatabase()
    val repository = remember(database) { ProtectedDomainRepository(database) }
    val coroutineScope = rememberCoroutineScope()
    var records by remember(kind) { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
    var query by remember(kind) { mutableStateOf("") }
    var selected by remember { mutableStateOf<DomainObjectRow?>(null) }
    var loading by remember(kind) { mutableStateOf(true) }
    var failed by remember(kind) { mutableStateOf(false) }

    fun refresh() {
        coroutineScope.launch {
            loading = true
            try {
                records = repository.domainObjects(scopeId, kind)
                failed = false
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(scopeId, kind) { refresh() }

    val filtered = records.filter { record ->
        val needle = query.trim().lowercase()
        needle.isEmpty() || record.title.lowercase().contains(needle) || record.body.orEmpty().lowercase().contains(needle)
    }

    ScreenColumn {
        val titleId = when (kind) {
            DomainKind.PERSON -> ai.memora.app.R.string.people
            DomainKind.PROJECT -> ai.memora.app.R.string.projects
            DomainKind.NOTE -> ai.memora.app.R.string.notes_title
        }
        ScreenHeading(s(titleId),
            s(ai.memora.app.R.string.saved_objects_subtitle))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag("saved_objects_search"),
            label = { Text(s(ai.memora.app.R.string.saved_objects_search)) },
            singleLine = true,
        )
        when {
            loading -> Text(s(ai.memora.app.R.string.ai_loading), color = colors.muted)
            failed -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_load_error))
            records.isEmpty() -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_empty))
            filtered.isEmpty() -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_no_matches))
            else -> filtered.forEach { record ->
                MemoraCard(
                    onClick = { selected = record },
                    modifier = Modifier.testTag("saved_object_${record.objectId}"),
                ) {
                    Text(record.title, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    record.body?.takeIf(String::isNotBlank)?.let { body ->
                        Text(body.take(240) + if (body.length > 240) "…" else "", color = colors.muted,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    }
                    if (record.status == "archived") Text(s(ai.memora.app.R.string.saved_objects_archived), color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    selected?.let { record ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(record.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val body = record.body?.takeIf(String::isNotBlank)
                    Text(body?.take(20_000) ?: s(ai.memora.app.R.string.saved_objects_no_details), color = colors.ink)
                    if (body != null && body.length > 20_000) {
                        Text(s(ai.memora.app.R.string.saved_objects_details_truncated), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
                    }
                    if (kind == DomainKind.PERSON) PersonCardSections(record, scopeId, repository,
                        remember(database) { ProtectedCaseRepository(database) })
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
}

@Composable
private fun PersonCardSections(
    person: DomainObjectRow,
    scopeId: String,
    domainRepository: ProtectedDomainRepository,
    caseRepository: ProtectedCaseRepository,
) {
    val coroutineScope = rememberCoroutineScope()
    var refreshVersion by remember(person.objectId) { mutableIntStateOf(0) }
    var candidates by remember(person.objectId) { mutableStateOf<List<FinancePersonIdentityChoices>>(emptyList()) }
    var historyByPersonId by remember(person.objectId) { mutableStateOf<Map<String, ai.memora.app.data.protectedstore.FinanceHistory>>(emptyMap()) }
    var relatedCases by remember(person.objectId) { mutableStateOf<List<CaseSnapshot>>(emptyList()) }
    var loading by remember(person.objectId) { mutableStateOf(true) }
    var pendingLink by remember { mutableStateOf<FinancePersonIdentityChoices?>(null) }
    var pendingUnlink by remember { mutableStateOf<PersonIdentityLinkRow?>(null) }
    var actionFailed by remember { mutableStateOf(false) }

    LaunchedEffect(scopeId, person.objectId, refreshVersion) {
        loading = true
        actionFailed = false
        try {
            val matched = domainRepository.matchingFinancePeople(scopeId, person.title).mapNotNull {
                caseRepository.financePersonIdentityChoices(scopeId, it.personId)
            }
            candidates = matched
            historyByPersonId = matched.filter { it.confirmedLink?.domainPersonId == person.objectId }
                .mapNotNull { choice -> domainRepository.financeHistoryByPersonId(scopeId, choice.financePerson.personId)
                    ?.let { choice.financePerson.personId to it } }.toMap()
            relatedCases = caseRepository.cases(scopeId, 100).mapNotNull { case ->
                caseRepository.caseSnapshot(scopeId, case.caseId)?.takeIf { snapshot ->
                    snapshot.participants.any { it.objectId == person.objectId }
                }
            }
        } catch (_: Exception) {
            actionFailed = true
        } finally {
            loading = false
        }
    }

    Text(s(ai.memora.app.R.string.person_finance_identity), modifier = Modifier.padding(top = 18.dp),
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
    Text(s(ai.memora.app.R.string.person_finance_identity_help), color = colors.muted,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    when {
        loading -> Text(s(ai.memora.app.R.string.ai_loading), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
        actionFailed -> Text(s(ai.memora.app.R.string.saved_objects_load_error), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
        candidates.isEmpty() -> Text(s(ai.memora.app.R.string.person_finance_events_empty), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
        else -> candidates.forEach { choice ->
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = colors.card,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(choice.financePerson.displayName, color = colors.ink,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    when {
                        choice.confirmedLink?.domainPersonId == person.objectId -> {
                            Text(s(ai.memora.app.R.string.person_identity_linked), color = colors.green)
                            TextButton(onClick = { pendingUnlink = choice.confirmedLink }) {
                                Text(s(ai.memora.app.R.string.person_identity_unlink))
                            }
                        }
                        choice.confirmedLink != null -> Text(s(ai.memora.app.R.string.person_identity_linked_elsewhere), color = colors.muted)
                        choice.candidates.any { it.objectId == person.objectId } -> TextButton(
                            modifier = Modifier.testTag("person_identity_confirm_action"), onClick = { pendingLink = choice }) {
                            Text(s(ai.memora.app.R.string.person_identity_confirm))
                        }
                        else -> Text(s(ai.memora.app.R.string.person_identity_linked_elsewhere), color = colors.muted)
                    }
                }
            }
        }
    }
    val linkedFinanceEvents = historyByPersonId.values.flatMap { it.events }
    val financeEventIds = linkedFinanceEvents.map { it.eventId }.toSet()
    val personStory = buildList {
        historyByPersonId.values.forEach { history ->
            history.events.forEach { event ->
                val occurredAt = event.occurredAt ?: event.recordedAt
                val amount = java.math.BigDecimal.valueOf(event.minorUnits, 2).stripTrailingZeros().toPlainString()
                val label = s(when (event.kind) {
                    "receipt" -> ai.memora.app.R.string.finance_receipt_label
                    "debt" -> ai.memora.app.R.string.voice_debt_label
                    else -> ai.memora.app.R.string.finance_expense_label
                })
                val reversed = event.eventId in history.voidedEventIds
                val debtLeft = history.remainingDebtMinorByEvent[event.eventId]?.let { remaining ->
                    java.math.BigDecimal.valueOf(remaining, 2).stripTrailingZeros().toPlainString()
                }
                val detail = buildList {
                    add("$amount ${event.currency}${event.comment?.let { " · $it" }.orEmpty()}")
                    if (reversed) add(s(ai.memora.app.R.string.finance_event_reversed))
                    debtLeft?.let { add("${s(ai.memora.app.R.string.finance_debt_remaining)}: $it ${event.currency}") }
                }.joinToString("\n")
                add(PersonStoryEntry(event.eventId, occurredAt, personTimelineOrder(occurredAt), label, detail))
            }
        }
        relatedCases.forEach { snapshot ->
            snapshot.timeline.filterNot { it.sourceKind == "money_event" && it.sourceId in financeEventIds }
                .forEach { event ->
                    val label = when {
                        event.sourceKind == "case_revision" -> event.title.ifBlank { s(ai.memora.app.R.string.case_timeline_update) }
                        event.title.isNotBlank() -> "${snapshot.record.title}: ${event.title}"
                        else -> snapshot.record.title
                    }
                    add(PersonStoryEntry("case-${event.id}", event.at, personTimelineOrder(event.at), label, event.detail))
                }
        }
    }.distinctBy { it.id }.sortedWith(compareByDescending<PersonStoryEntry> { it.sortOrder }.thenBy { it.id }).take(40)

    if (personStory.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.person_timeline), modifier = Modifier.padding(top = 18.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        personStory.forEach { entry ->
            MemoraCard(modifier = Modifier.padding(top = 8.dp).testTag("person_story_${entry.id}")) {
                Text(entry.title, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                entry.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(500), color = colors.muted) }
                Text(entry.at, color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (relatedCases.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.cases), modifier = Modifier.padding(top = 18.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        relatedCases.take(8).forEach { snapshot ->
            MemoraCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(snapshot.record.title, color = colors.ink,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Text(caseStatusLabelForPerson(snapshot.record.status), color = colors.green,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                snapshot.record.nextAction?.let { Text("${s(ai.memora.app.R.string.case_next_action)}: $it", color = colors.muted) }
                snapshot.timeline.takeLast(4).asReversed().forEach { event ->
                    Text("${event.title} · ${event.at}", color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    pendingLink?.let { choice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(s(ai.memora.app.R.string.person_identity_confirm)) },
            text = { Text(stringResource(ai.memora.app.R.string.person_identity_confirm_question,
                choice.financePerson.displayName, person.title)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_identity_confirm_accept"), onClick = {
                    coroutineScope.launch {
                        try {
                            caseRepository.confirmFinancePersonIdentity(scopeId, choice.financePerson.personId,
                                person.objectId, confirmedBy = "owner")
                            pendingLink = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingLink = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_identity_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
    pendingUnlink?.let { link ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            title = { Text(s(ai.memora.app.R.string.person_identity_unlink)) },
            text = { Text(s(ai.memora.app.R.string.person_identity_unlink_question)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_identity_unlink_accept"), onClick = {
                    coroutineScope.launch {
                        try {
                            caseRepository.unlinkFinancePersonIdentity(scopeId, link.linkId, confirmedBy = "owner")
                            pendingUnlink = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingUnlink = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_identity_unlink)) }
            },
            dismissButton = { TextButton(onClick = { pendingUnlink = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
}

@Composable
private fun caseStatusLabelForPerson(status: String): String = s(when (status) {
    "waiting" -> ai.memora.app.R.string.case_status_waiting
    "completed" -> ai.memora.app.R.string.case_status_completed
    "cancelled" -> ai.memora.app.R.string.case_status_cancelled
    else -> ai.memora.app.R.string.case_status_open
})

private fun personTimelineOrder(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

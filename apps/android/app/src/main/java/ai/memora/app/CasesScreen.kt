package ai.memora.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.CaseSnapshot
import ai.memora.app.data.protectedstore.CaseEvidenceView
import ai.memora.app.data.protectedstore.CaseTimelineEntry
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val CASES_PAGE_SIZE = 30
private const val CASES_MAX_SEARCH_OFFSET = 1_020
private const val CASES_MAX_LIST_OFFSET = 99_990

/** A local, source-linked view over cases written by the same secretary tools. */
@Composable
internal fun CasesScreen(onOpenSecretary: () -> Unit = {}) {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val database = rememberUiAgentDatabase()
    val repository = remember(database) { ProtectedCaseRepository(database) }
    val coroutineScope = rememberCoroutineScope()
    var records by remember(scopeId) { mutableStateOf<List<CaseRecordRow>>(emptyList()) }
    var query by remember(scopeId) { mutableStateOf("") }
    var statusFilter by remember(scopeId) { mutableStateOf("all") }
    var pageOffset by remember(scopeId) { mutableIntStateOf(0) }
    var hasMore by remember(scopeId) { mutableStateOf(false) }
    var refreshVersion by remember(scopeId) { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CaseSnapshot?>(null) }
    var selectedSource by remember { mutableStateOf<CaseEvidenceView?>(null) }
    var loading by remember(scopeId) { mutableStateOf(true) }
    var failed by remember(scopeId) { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }

    LaunchedEffect(scopeId, query, statusFilter, refreshVersion, pageOffset) {
        loading = true
        if (query.isNotBlank()) delay(250)
        try {
            val selectedStatus = statusFilter.takeUnless { it == "all" }
            val page = if (query.isBlank()) repository.cases(scopeId, limit = CASES_PAGE_SIZE + 1,
                status = selectedStatus, offset = pageOffset)
                else repository.searchCases(scopeId, query.trim(), limit = CASES_PAGE_SIZE + 1,
                    status = selectedStatus, offset = pageOffset)
            val visiblePage = page.take(CASES_PAGE_SIZE)
            hasMore = page.size > CASES_PAGE_SIZE
            records = visiblePage
            failed = false
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun changeCaseStatus(snapshot: CaseSnapshot, status: String) {
        coroutineScope.launch {
            try {
                repository.updateCase(scopeId, snapshot.record.caseId, snapshot.record.version,
                    snapshot.record.title, snapshot.record.summary, status, snapshot.record.nextAction,
                    sourceTurnId = "owner-ui-${UUID.randomUUID()}", now = Instant.now().toString())
                refreshVersion++
                pageOffset = 0
                selected = repository.caseSnapshot(scopeId, snapshot.record.caseId)
                actionFailed = false
            } catch (_: Exception) {
                actionFailed = true
                selected = runCatching { repository.caseSnapshot(scopeId, snapshot.record.caseId) }.getOrNull() ?: snapshot
            }
        }
    }

    ScreenColumn(modifier = Modifier.testTag("cases_screen")) {
        Text(s(R.string.cases_subtitle), color = colors.muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(200); pageOffset = 0 },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("cases_search"),
            label = { Text(s(R.string.cases_search_label)) },
            singleLine = true,
        )
        if (query.isNotBlank()) Text(s(R.string.cases_search_limit_hint),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp), color = colors.muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "open", "waiting", "completed", "cancelled").forEach { status ->
                FilterChip(
                    selected = statusFilter == status,
                    onClick = { statusFilter = status; pageOffset = 0 },
                    label = { Text(caseFilterLabel(status)) },
                    modifier = Modifier.testTag("cases_filter_$status"),
                )
            }
        }
        when {
            loading && (pageOffset == 0 || records.isEmpty()) -> Text(s(R.string.ai_loading), color = colors.muted,
                modifier = Modifier.testTag("cases_loading"))
            failed -> EmptyState(s(R.string.working_no_data), s(R.string.cases_load_error))
            records.isEmpty() && query.isBlank() && statusFilter == "all" -> {
                EmptyState(s(R.string.working_no_data), s(R.string.cases_empty))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = onOpenSecretary, modifier = Modifier.testTag("cases_empty_open_secretary")) {
                        Text(s(R.string.cases_empty_action))
                    }
                }
            }
            records.isEmpty() -> {
                EmptyState(s(R.string.working_no_data), s(R.string.cases_no_matches))
                if (query.isNotBlank() || statusFilter != "all") {
                    TextButton(modifier = Modifier.testTag("cases_clear_filters"), onClick = {
                        query = ""
                        statusFilter = "all"
                        pageOffset = 0
                    }) { Text(s(R.string.cases_clear_filters)) }
                }
            }
            else -> {
                if (!loading && pageOffset > 0) {
                    TextButton(modifier = Modifier.testTag("cases_load_newer"), onClick = {
                        pageOffset = (pageOffset - CASES_PAGE_SIZE).coerceAtLeast(0)
                    }) { Text(s(R.string.cases_load_newer)) }
                }
                records.forEach { record ->
                    MemoraCard(
                        onClick = {
                            coroutineScope.launch { selected = repository.caseSnapshot(scopeId, record.caseId) }
                        },
                        modifier = Modifier.testTag("case_${record.caseId}"),
                    ) {
                        Text(record.title, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(caseStatusLabel(record.status), color = colors.green,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                        record.summary?.takeIf(String::isNotBlank)?.let { summary ->
                            Text(summary.take(360) + if (summary.length > 360) "…" else "", color = colors.muted,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        }
                        record.nextAction?.takeIf(String::isNotBlank)?.let { action ->
                            Text("${s(R.string.case_next_action)}: $action", color = colors.ink,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                val maxOffset = if (query.isBlank()) CASES_MAX_LIST_OFFSET else CASES_MAX_SEARCH_OFFSET
                if (loading && pageOffset > 0) {
                    Text(s(R.string.ai_loading), color = colors.muted, modifier = Modifier.testTag("cases_loading_more"))
                } else if (hasMore && pageOffset < maxOffset) {
                    TextButton(modifier = Modifier.testTag("cases_load_older"), onClick = {
                        pageOffset = (pageOffset + CASES_PAGE_SIZE).coerceAtMost(maxOffset)
                    }) { Text(s(R.string.cases_load_older)) }
                } else if (hasMore) {
                    Text(s(R.string.cases_list_limit_reached), color = colors.muted,
                        modifier = Modifier.testTag("cases_list_limit_reached"))
                }
            }
        }
    }

    selected?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(snapshot.record.title, modifier = Modifier.testTag("case_detail_title")) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text(caseStatusLabel(snapshot.record.status), color = colors.green)
                    snapshot.record.summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp), color = colors.ink)
                    }
                    snapshot.record.nextAction?.takeIf(String::isNotBlank)?.let {
                        Text("${s(R.string.case_next_action)}: $it",
                            modifier = Modifier.padding(top = 12.dp).testTag("case_detail_next_action"), color = colors.ink)
                    }
                    if (actionFailed) Text(s(R.string.case_update_error), color = colors.amber,
                        modifier = Modifier.testTag("case_update_error"))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (snapshot.record.status == "open") {
                            OutlinedButton(modifier = Modifier.testTag("case_action_waiting"), onClick = {
                                changeCaseStatus(snapshot, "waiting")
                            }) { Text(s(R.string.case_mark_waiting)) }
                        }
                        if (snapshot.record.status != "completed") {
                            OutlinedButton(modifier = Modifier.testTag("case_action_complete"), onClick = {
                                changeCaseStatus(snapshot, "completed")
                            }) { Text(s(R.string.case_mark_completed)) }
                        }
                        if (snapshot.record.status in setOf("waiting", "completed", "cancelled")) {
                            OutlinedButton(modifier = Modifier.testTag("case_action_reopen"), onClick = {
                                changeCaseStatus(snapshot, "open")
                            }) { Text(s(R.string.case_reopen)) }
                        }
                    }
                    if (snapshot.participants.isNotEmpty()) {
                        Text(s(R.string.case_participants), modifier = Modifier.padding(top = 18.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
                        snapshot.participants.forEach { person -> Text(person.title, color = colors.muted) }
                    }
                    if (snapshot.evidence.isNotEmpty()) {
                        Text(s(R.string.case_sources), modifier = Modifier.padding(top = 18.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
                        snapshot.evidence.forEach { source ->
                            MemoraCard(
                                modifier = Modifier.padding(top = 8.dp)
                                    .testTag("case_evidence_source_${source.evidence.evidenceId}"),
                                onClick = { selectedSource = source },
                            ) {
                                Text(caseEvidenceTitle(source), color = colors.ink,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                                source.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(500), color = colors.muted) }
                                if (!source.sourceAvailable) Text(s(R.string.case_source_missing), color = colors.muted)
                                else Text(s(R.string.case_source_open_details), color = colors.accent,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (snapshot.timeline.isNotEmpty()) {
                        Text(s(R.string.case_timeline), modifier = Modifier.padding(top = 18.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
                        snapshot.timeline.asReversed().forEach { event ->
                            val linkedSource = snapshot.evidence.firstOrNull { source ->
                                source.evidence.sourceKind == event.sourceKind && source.evidence.sourceId == event.sourceId &&
                                    source.evidence.sourceRevisionId == event.sourceRevisionId
                            }
                            Column(Modifier.padding(top = 10.dp)
                                .then(if (linkedSource != null) Modifier.clickable { selectedSource = linkedSource } else Modifier)
                                .testTag("case_timeline_${event.id}")) {
                                Text(caseTimelineTitle(event), color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                Text(event.at, color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                event.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(300), color = colors.muted) }
                                event.sourceExcerpt?.let { excerpt ->
                                    Text(LocalStrings.current.getString(R.string.case_source_exact_excerpt, excerpt), color = colors.accent,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("case_timeline_exact_excerpt_${event.id}"))
                                }
                                if (linkedSource != null) {
                                    Text(s(R.string.case_source_open_details), color = colors.accent,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(s(R.string.close)) } },
        )
    }

    selectedSource?.let { source ->
        AlertDialog(
            modifier = Modifier.testTag("case_source_detail_dialog"),
            onDismissRequest = { selectedSource = null },
            title = { Text(caseEvidenceTitle(source),
                modifier = Modifier.testTag("case_source_detail_title")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    source.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(2_000), color = colors.ink) }
                    Text("${s(R.string.case_source_relation)}: ${source.evidence.relation}",
                        modifier = Modifier.padding(top = 12.dp), color = colors.muted)
                    Text("${s(R.string.case_source_time)}: ${source.evidence.occurredAt}",
                        modifier = Modifier.padding(top = 6.dp), color = colors.muted)
                    if (!source.sourceAvailable) Text(s(R.string.case_source_missing),
                        modifier = Modifier.padding(top = 8.dp), color = colors.amber)
                }
            },
            confirmButton = { TextButton(modifier = Modifier.testTag("case_source_detail_close"),
                onClick = { selectedSource = null }) { Text(s(R.string.close)) } },
        )
    }
}

@Composable
private fun caseStatusLabel(status: String): String = s(when (status) {
    "waiting" -> R.string.case_status_waiting
    "completed" -> R.string.case_status_completed
    "cancelled" -> R.string.case_status_cancelled
    else -> R.string.case_status_open
})

@Composable
private fun caseFilterLabel(status: String): String = s(when (status) {
    "open" -> R.string.case_status_open
    "waiting" -> R.string.case_status_waiting
    "completed" -> R.string.case_status_completed
    "cancelled" -> R.string.case_status_cancelled
    else -> R.string.cases_filter_all
})

@Composable
private fun caseSourceLabel(kind: String): String = s(when (kind) {
    "task" -> R.string.case_source_task
    "memory_revision" -> R.string.case_source_memory
    "money_event" -> R.string.finance
    "calendar_event" -> R.string.case_source_calendar
    else -> R.string.case_source_generic
})

@Composable
private fun caseEvidenceTitle(source: CaseEvidenceView): String {
    val label = when (source.eventKind) {
        "receipt" -> s(R.string.finance_receipt_label)
        "debt" -> s(R.string.voice_debt_label)
        "expense" -> s(R.string.finance_expense_label)
        else -> null
    } ?: source.title.takeIf(String::isNotBlank) ?: caseSourceLabel(source.evidence.sourceKind)
    val person = source.title.takeIf(String::isNotBlank)
    return if (source.eventKind != null && person != null) "$label: $person" else label
}

@Composable
private fun caseTimelineTitle(event: CaseTimelineEntry): String {
    if (event.eventKind != null) {
        val label = when (event.eventKind) {
            "receipt" -> s(R.string.finance_receipt_label)
            "debt" -> s(R.string.voice_debt_label)
            "expense" -> s(R.string.finance_expense_label)
            else -> s(R.string.finance)
        }
        return event.title.takeIf(String::isNotBlank)?.let { "$label: $it" } ?: label
    }
    if (event.title.isNotBlank()) return event.title
    return if (event.sourceKind == "case_revision") s(R.string.case_timeline_update) else caseSourceLabel(event.sourceKind)
}

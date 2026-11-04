package ai.memora.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
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
import ai.memora.app.data.protectedstore.CaseEvidenceView
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.FinancePersonIdentityChoices
import ai.memora.app.data.protectedstore.LinkedDomainObject
import ai.memora.app.data.protectedstore.PersonIdentityLinkRow
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.PersonAliasRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.CalendarPersonLinkRepository
import ai.memora.app.data.protectedstore.CalendarPersonLinkRow
import ai.memora.app.data.protectedstore.LocalCalendarEventRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.TaskPersonLinkRow
import ai.memora.app.domain.Task
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.launch

private data class PersonStoryEntry(
    val id: String,
    val at: String,
    val sortOrder: Long,
    val title: String,
    val detail: String?,
    val source: CaseEvidenceView? = null,
    val task: Task? = null,
)

/** Read-only view of the same encrypted people/projects written through the agent tool registry. */
@Composable
internal fun SavedObjectsScreen(kind: DomainKind) {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val database = rememberUiAgentDatabase()
    val repository = remember(database) { ProtectedDomainRepository(database) }
    val aliasRepository = remember(database) { PersonAliasRepository(database) }
    val coroutineScope = rememberCoroutineScope()
    var records by remember(kind) { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
    var query by remember(kind) { mutableStateOf("") }
    var selected by remember { mutableStateOf<DomainObjectRow?>(null) }
    var selectedCaseSource by remember { mutableStateOf<CaseEvidenceView?>(null) }
    var selectedCaseDetails by remember { mutableStateOf<CaseSnapshot?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var detailStack by remember { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
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
                    onClick = {
                        selected = record
                        selectedCaseSource = null
                        selectedCaseDetails = null
                        detailStack = emptyList()
                    },
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
        val detailScrollState = remember(record.objectId, selectedCaseDetails?.record?.caseId,
            selectedCaseSource?.evidence?.evidenceId) { ScrollState(0) }
        AlertDialog(
            onDismissRequest = {
                selected = null
                selectedCaseSource = null
                selectedCaseDetails = null
                detailStack = emptyList()
            },
            title = {
                val detailTitle = when {
                    selectedCaseSource != null -> personCaseSourceTitle(requireNotNull(selectedCaseSource))
                    selectedCaseDetails != null -> selectedCaseDetails?.record?.title
                    else -> record.title
                }
                Text(detailTitle ?: record.title,
                    modifier = Modifier.testTag("saved_object_detail_title"))
            },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(detailScrollState)) {
                    val source = selectedCaseSource
                    val caseDetails = selectedCaseDetails
                    val taskDetails = selectedTask
                    if (source != null) {
                        Text(personCaseSourceKindLabel(source), color = colors.accent,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                        source.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(2_000), color = colors.ink) }
                        Text("${s(ai.memora.app.R.string.case_source_relation)}: ${personCaseRelationLabel(source.evidence.relation)}",
                            modifier = Modifier.padding(top = 12.dp), color = colors.muted)
                        Text("${s(ai.memora.app.R.string.case_source_time)}: ${source.evidence.occurredAt}",
                            modifier = Modifier.padding(top = 6.dp), color = colors.muted)
                        if (!source.sourceAvailable) Text(s(ai.memora.app.R.string.case_source_missing),
                            modifier = Modifier.padding(top = 8.dp), color = colors.amber)
                    } else if (caseDetails != null) {
                        PersonCaseDetails(caseDetails, onOpenSource = { selectedCaseSource = it })
                    } else if (taskDetails != null) {
                        Text(s(ai.memora.app.R.string.person_task_detail_heading), color = colors.accent,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                        Text(taskDetails.title, modifier = Modifier.testTag("saved_object_task_title"), color = colors.ink,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(s(if (taskDetails.completed) ai.memora.app.R.string.task_completed
                            else ai.memora.app.R.string.task_open), color = colors.muted)
                        taskDetails.dueLocal?.let { Text("${s(ai.memora.app.R.string.task_due)}: $it", color = colors.muted) }
                    } else {
                        val body = record.body?.takeIf(String::isNotBlank)
                        Text(body?.take(20_000) ?: s(ai.memora.app.R.string.saved_objects_no_details), color = colors.ink)
                        if (body != null && body.length > 20_000) {
                            Text(s(ai.memora.app.R.string.saved_objects_details_truncated), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
                        }
                        if (record.kind == DomainKind.PERSON.name) PersonCardSections(record, scopeId, database, repository, aliasRepository,
                            remember(database) { ProtectedCaseRepository(database) },
                            onOpenLinkedObject = { linked ->
                                detailStack = detailStack + record
                                selectedCaseSource = null
                                selectedCaseDetails = null
                                selected = linked
                            },
                            onOpenCaseSource = { source -> selectedCaseSource = source },
                            onOpenCaseDetails = { snapshot -> selectedCaseDetails = snapshot },
                            onOpenTask = { task -> selectedTask = task })
                    }
                }
            },
            confirmButton = {
                if (selectedCaseSource != null || selectedCaseDetails != null || selectedTask != null || detailStack.isNotEmpty()) {
                    TextButton(modifier = Modifier.testTag("saved_object_detail_back"), onClick = {
                        if (selectedCaseSource != null) {
                            selectedCaseSource = null
                        } else if (selectedCaseDetails != null) {
                            selectedCaseDetails = null
                        } else if (selectedTask != null) {
                            selectedTask = null
                        } else {
                            selected = detailStack.last()
                            detailStack = detailStack.dropLast(1)
                        }
                    }) { Text(s(ai.memora.app.R.string.person_related_back)) }
                } else {
                    TextButton(modifier = Modifier.testTag("saved_object_detail_close"), onClick = {
                        selected = null
                        selectedTask = null
                        detailStack = emptyList()
                    }) {
                        Text(s(ai.memora.app.R.string.close))
                    }
                }
            },
        )
    }
}

@Composable
private fun PersonCardSections(
    person: DomainObjectRow,
    scopeId: String,
    database: ai.memora.app.data.protectedstore.AgentDatabase,
    domainRepository: ProtectedDomainRepository,
    aliasRepository: PersonAliasRepository,
    caseRepository: ProtectedCaseRepository,
    onOpenLinkedObject: (DomainObjectRow) -> Unit,
    onOpenCaseSource: (CaseEvidenceView) -> Unit,
    onOpenCaseDetails: (CaseSnapshot) -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var refreshVersion by remember(person.objectId) { mutableIntStateOf(0) }
    var candidates by remember(person.objectId) { mutableStateOf<List<FinancePersonIdentityChoices>>(emptyList()) }
    var tasks by remember(person.objectId) { mutableStateOf<List<Task>>(emptyList()) }
    var taskLinks by remember(person.objectId) { mutableStateOf<List<TaskPersonLinkRow>>(emptyList()) }
    var calendarEvents by remember(person.objectId) { mutableStateOf<List<LocalCalendarEventRow>>(emptyList()) }
    var calendarLinks by remember(person.objectId) { mutableStateOf<List<CalendarPersonLinkRow>>(emptyList()) }
    var historyByPersonId by remember(person.objectId) { mutableStateOf<Map<String, ai.memora.app.data.protectedstore.FinanceHistory>>(emptyMap()) }
    var relatedCases by remember(person.objectId) { mutableStateOf<List<CaseSnapshot>>(emptyList()) }
    var relatedObjects by remember(person.objectId) { mutableStateOf<List<LinkedDomainObject>>(emptyList()) }
    var aliases by remember(person.objectId) { mutableStateOf<List<PersonAliasRow>>(emptyList()) }
    var newAlias by remember(person.objectId) { mutableStateOf("") }
    var aliasInputVisible by remember(person.objectId) { mutableStateOf(false) }
    var loading by remember(person.objectId) { mutableStateOf(true) }
    var pendingLink by remember { mutableStateOf<FinancePersonIdentityChoices?>(null) }
    var pendingUnlink by remember { mutableStateOf<PersonIdentityLinkRow?>(null) }
    var taskPickerVisible by remember { mutableStateOf(false) }
    var pendingTask by remember { mutableStateOf<Task?>(null) }
    var pendingTaskUnlink by remember { mutableStateOf<TaskPersonLinkRow?>(null) }
    var calendarPickerVisible by remember { mutableStateOf(false) }
    var pendingCalendarEvent by remember { mutableStateOf<LocalCalendarEventRow?>(null) }
    var pendingCalendarUnlink by remember { mutableStateOf<CalendarPersonLinkRow?>(null) }
    var actionFailed by remember { mutableStateOf(false) }
    var aliasActionFailed by remember { mutableStateOf(false) }

    LaunchedEffect(scopeId, person.objectId, refreshVersion) {
        loading = true
        actionFailed = false
        aliasActionFailed = false
        try {
            val matched = domainRepository.matchingFinancePeople(scopeId, person.title).mapNotNull {
                caseRepository.financePersonIdentityChoices(scopeId, it.personId)
            }
            candidates = matched
            tasks = ProtectedMemoryRepository(database).tasks().tasks
            taskLinks = database.protectedStoreDao().activeTaskPersonLinks(scopeId, person.objectId)
            calendarEvents = database.protectedStoreDao().calendarEvents(scopeId).filter { it.status == "active" }
            calendarLinks = database.protectedStoreDao().activeCalendarPersonLinks(scopeId, person.objectId)
            historyByPersonId = matched.filter { it.confirmedLink?.domainPersonId == person.objectId }
                .mapNotNull { choice -> domainRepository.financeHistoryByPersonId(scopeId, choice.financePerson.personId)
                    ?.let { choice.financePerson.personId to it } }.toMap()
            relatedCases = caseRepository.cases(scopeId, 100).mapNotNull { case ->
                caseRepository.caseSnapshot(scopeId, case.caseId)?.takeIf { snapshot ->
                    snapshot.participants.any { it.objectId == person.objectId }
                }
            }
            relatedObjects = domainRepository.linkedObjects(scopeId, person.objectId)
            aliases = aliasRepository.list(scopeId, person.objectId)
        } catch (_: Exception) {
            actionFailed = true
        } finally {
            loading = false
        }
    }

    Text(s(ai.memora.app.R.string.person_aliases), modifier = Modifier.padding(top = 18.dp),
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
    Text(s(ai.memora.app.R.string.person_aliases_help), color = colors.muted,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    if (!aliasInputVisible) {
        TextButton(modifier = Modifier.testTag("person_alias_open_action"), onClick = { aliasInputVisible = true }) {
            Text(s(ai.memora.app.R.string.person_alias_add))
        }
    } else {
        OutlinedTextField(
            value = newAlias,
            onValueChange = { newAlias = it.take(120); aliasActionFailed = false },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("person_alias_input"),
            label = { Text(s(ai.memora.app.R.string.person_alias_add)) },
            singleLine = true,
        )
        TextButton(modifier = Modifier.testTag("person_alias_add_action"), enabled = newAlias.isNotBlank() && !loading,
            onClick = {
                val requestedAlias = newAlias
                coroutineScope.launch {
                    try {
                        aliasRepository.add(scopeId, person.objectId, requestedAlias, actorId = "owner")
                        newAlias = ""
                        aliasInputVisible = false
                        refreshVersion++
                    } catch (_: Exception) { aliasActionFailed = true }
                }
            }) { Text(s(ai.memora.app.R.string.person_alias_add)) }
    }
    if (aliasActionFailed) Text(s(ai.memora.app.R.string.person_alias_action_failed),
        modifier = Modifier.testTag("person_alias_error"), color = colors.amber)
    aliases.filter { it.deletedAtMillis == null }.forEach { alias ->
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("person_alias_item_${alias.aliasId}"),
            color = colors.card, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(alias.aliasText, modifier = Modifier.weight(1f), color = colors.ink)
                TextButton(modifier = Modifier.testTag("person_alias_retire_${alias.aliasId}"), onClick = {
                    coroutineScope.launch {
                        try {
                            aliasRepository.retire(scopeId, alias.aliasId, alias.version, actorId = "owner")
                            refreshVersion++
                        } catch (_: Exception) { aliasActionFailed = true }
                    }
                }) { Text(s(ai.memora.app.R.string.person_alias_retire)) }
            }
        }
    }
    aliases.filter { it.deletedAtMillis != null }.forEach { alias ->
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("person_alias_retired_${alias.aliasId}"),
            color = colors.card, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(alias.aliasText, modifier = Modifier.weight(1f), color = colors.muted)
                TextButton(modifier = Modifier.testTag("person_alias_restore_${alias.aliasId}"), onClick = {
                    coroutineScope.launch {
                        try {
                            aliasRepository.restore(scopeId, alias.aliasId, alias.version, actorId = "owner")
                            refreshVersion++
                        } catch (_: Exception) { aliasActionFailed = true }
                    }
                }) { Text(s(ai.memora.app.R.string.person_alias_restore)) }
            }
        }
    }

    val taskById = tasks.associateBy { it.id }
    val linkedTaskIds = taskLinks.map { it.taskId }.toSet()
    Text(s(ai.memora.app.R.string.person_tasks), modifier = Modifier.padding(top = 18.dp),
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
    Text(s(ai.memora.app.R.string.person_tasks_help), color = colors.muted,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    TextButton(modifier = Modifier.testTag("person_task_link_open"), enabled = !loading,
        onClick = { taskPickerVisible = true }) { Text(s(ai.memora.app.R.string.person_task_link_add)) }
    if (taskLinks.isEmpty() && !loading) Text(s(ai.memora.app.R.string.person_task_link_empty),
        modifier = Modifier.padding(top = 4.dp), color = colors.muted)
    taskLinks.forEach { link ->
        val task = taskById[link.taskId] ?: return@forEach
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("person_task_link_${link.taskId}"),
            color = colors.card, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, color = colors.ink)
                    Text(personRelatedRelationLabel(link.relation), color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                TextButton(modifier = Modifier.testTag("person_task_open_${task.id}"), onClick = { onOpenTask(task) }) {
                    Text(s(ai.memora.app.R.string.case_source_open_details))
                }
                TextButton(modifier = Modifier.testTag("person_task_unlink_${link.linkId}"),
                    onClick = { pendingTaskUnlink = link }) { Text(s(ai.memora.app.R.string.person_task_link_remove)) }
            }
        }
    }
    if (taskPickerVisible) {
        val availableTasks = tasks.filter { it.id !in linkedTaskIds }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { taskPickerVisible = false },
            title = { Text(s(ai.memora.app.R.string.person_task_link_add)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(remember { ScrollState(0) })) {
                    if (availableTasks.isEmpty()) Text(s(ai.memora.app.R.string.person_task_link_empty), color = colors.muted)
                    availableTasks.forEach { task ->
                        TextButton(modifier = Modifier.fillMaxWidth().testTag("person_task_candidate_${task.id}"),
                            onClick = { taskPickerVisible = false; pendingTask = task }) {
                            Text(task.title, color = colors.ink)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { taskPickerVisible = false }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
    pendingTask?.let { task ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingTask = null },
            title = { Text(s(ai.memora.app.R.string.person_task_link_add)) },
            text = { Text(stringResource(ai.memora.app.R.string.person_task_link_confirm, task.title, person.title)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_task_link_confirm"), onClick = {
                    coroutineScope.launch {
                        try {
                            caseRepository.confirmTaskPersonLink(scopeId, task.id, person.objectId,
                                confirmedBy = "owner")
                            pendingTask = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingTask = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_task_link_add)) }
            },
            dismissButton = { TextButton(onClick = { pendingTask = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
    pendingTaskUnlink?.let { link ->
        val taskTitle = taskById[link.taskId]?.title.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingTaskUnlink = null },
            title = { Text(s(ai.memora.app.R.string.person_task_link_remove)) },
            text = { Text(stringResource(ai.memora.app.R.string.person_task_unlink_confirm, taskTitle, person.title)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_task_unlink_confirm"), onClick = {
                    coroutineScope.launch {
                        try {
                            caseRepository.unlinkTaskPerson(scopeId, link.linkId, confirmedBy = "owner")
                            pendingTaskUnlink = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingTaskUnlink = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_task_link_remove)) }
            },
            dismissButton = { TextButton(onClick = { pendingTaskUnlink = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }

    val calendarEventById = calendarEvents.associateBy { it.eventId }
    val linkedCalendarIds = calendarLinks.map { it.eventId }.toSet()
    Text(s(ai.memora.app.R.string.person_calendar_events), modifier = Modifier.padding(top = 18.dp),
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
    Text(s(ai.memora.app.R.string.person_calendar_help), color = colors.muted,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    TextButton(modifier = Modifier.testTag("person_calendar_link_open"), enabled = !loading,
        onClick = { calendarPickerVisible = true }) { Text(s(ai.memora.app.R.string.person_calendar_link_add)) }
    if (calendarLinks.isEmpty() && !loading) Text(s(ai.memora.app.R.string.person_calendar_link_empty),
        modifier = Modifier.padding(top = 4.dp), color = colors.muted)
    calendarLinks.forEach { link ->
        val event = calendarEventById[link.eventId] ?: return@forEach
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("person_calendar_link_${link.eventId}"),
            color = colors.card, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(event.title, color = colors.ink)
                    Text("${event.startLocal} · ${event.zoneId}", color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                TextButton(modifier = Modifier.testTag("person_calendar_unlink_${link.linkId}"),
                    onClick = { pendingCalendarUnlink = link }) { Text(s(ai.memora.app.R.string.person_calendar_link_remove)) }
            }
        }
    }
    if (calendarPickerVisible) {
        val availableEvents = calendarEvents.filter { it.eventId !in linkedCalendarIds }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { calendarPickerVisible = false },
            title = { Text(s(ai.memora.app.R.string.person_calendar_link_add)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(remember { ScrollState(0) })) {
                    if (availableEvents.isEmpty()) Text(s(ai.memora.app.R.string.person_calendar_link_empty), color = colors.muted)
                    availableEvents.forEach { event ->
                        TextButton(modifier = Modifier.fillMaxWidth().testTag("person_calendar_candidate_${event.eventId}"),
                            onClick = { calendarPickerVisible = false; pendingCalendarEvent = event }) {
                            Text("${event.title} · ${event.startLocal}", color = colors.ink)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { calendarPickerVisible = false }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
    pendingCalendarEvent?.let { event ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingCalendarEvent = null },
            title = { Text(s(ai.memora.app.R.string.person_calendar_link_add)) },
            text = { Text(stringResource(ai.memora.app.R.string.person_calendar_link_confirm, event.title, person.title)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_calendar_link_confirm"), onClick = {
                    coroutineScope.launch {
                        try {
                            CalendarPersonLinkRepository(database).confirm(scopeId, event.eventId, person.objectId,
                                confirmedBy = "owner")
                            pendingCalendarEvent = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingCalendarEvent = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_calendar_link_add)) }
            },
            dismissButton = { TextButton(onClick = { pendingCalendarEvent = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
    pendingCalendarUnlink?.let { link ->
        val eventTitle = calendarEventById[link.eventId]?.title.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingCalendarUnlink = null },
            title = { Text(s(ai.memora.app.R.string.person_calendar_link_remove)) },
            text = { Text(stringResource(ai.memora.app.R.string.person_calendar_unlink_confirm, eventTitle, person.title)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("person_calendar_unlink_confirm"), onClick = {
                    coroutineScope.launch {
                        try {
                            CalendarPersonLinkRepository(database).retire(scopeId, link.linkId, confirmedBy = "owner")
                            pendingCalendarUnlink = null
                            refreshVersion++
                        } catch (_: Exception) { actionFailed = true; pendingCalendarUnlink = null }
                    }
                }) { Text(s(ai.memora.app.R.string.person_calendar_link_remove)) }
            },
            dismissButton = { TextButton(onClick = { pendingCalendarUnlink = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
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
        taskLinks.forEach { link ->
            val task = taskById[link.taskId] ?: return@forEach
            add(PersonStoryEntry("source-task-${task.id}-", link.createdAt, personTimelineOrder(link.createdAt),
                task.title, s(ai.memora.app.R.string.person_task_linked_story), task = task))
        }
        relatedCases.forEach { snapshot ->
            snapshot.timeline.filterNot { it.sourceKind == "money_event" && it.sourceId in financeEventIds }
                .forEach { event ->
                    val source = if (event.id.startsWith("evidence:")) {
                        snapshot.evidence.firstOrNull { "evidence:${it.evidence.evidenceId}" == event.id && it.sourceAvailable }
                    } else null
                    val label = when {
                        event.sourceKind == "case_revision" -> event.title.ifBlank { s(ai.memora.app.R.string.case_timeline_update) }
                        event.title.isNotBlank() -> "${snapshot.record.title}: ${event.title}"
                        else -> snapshot.record.title
                    }
                    // A source can be referenced by several cases. The person's global story is a
                    // timeline of canonical records, so key evidence cards by source identity,
                    // while keeping separate case-revision entries for each case.
                    val storyId = if (source != null) {
                        "source-${event.sourceKind}-${event.sourceId}-${event.sourceRevisionId.orEmpty()}"
                    } else "case-${event.id}"
                    add(PersonStoryEntry(storyId, event.at, personTimelineOrder(event.at), label, event.detail, source))
                }
        }
    }.distinctBy { it.id }.sortedWith(compareByDescending<PersonStoryEntry> { it.sortOrder }.thenBy { it.id }).take(40)

    if (relatedObjects.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.person_related_records), modifier = Modifier.padding(top = 18.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        relatedObjects.forEach { linked ->
            val target = linked.objectRecord
            MemoraCard(
                modifier = Modifier.padding(top = 8.dp).testTag("person_linked_object_${target.objectId}"),
                onClick = { onOpenLinkedObject(target) },
                contentPadding = 16.dp,
            ) {
                Text(target.title, color = colors.ink,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Text("${personRelatedKindLabel(target.kind)} · ${personRelatedRelationLabel(linked.link.relation)}",
                    color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (personStory.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.person_timeline), modifier = Modifier.padding(top = 18.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        personStory.forEach { entry ->
            MemoraCard(modifier = Modifier.padding(top = 8.dp).testTag("person_story_${entry.id}"),
                onClick = entry.source?.let { { onOpenCaseSource(it) } } ?: entry.task?.let { { onOpenTask(it) } }) {
                Text(entry.title, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                entry.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(500), color = colors.muted) }
                Text(entry.at, color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                if (entry.source != null || entry.task != null) Text(s(ai.memora.app.R.string.case_source_open_details), color = colors.accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (relatedCases.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.cases), modifier = Modifier.padding(top = 18.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        relatedCases.take(8).forEach { snapshot ->
            MemoraCard(modifier = Modifier.padding(top = 8.dp)
                .testTag("person_related_case_${snapshot.record.caseId}"),
                onClick = { onOpenCaseDetails(snapshot) }) {
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
private fun personRelatedKindLabel(kind: String): String = s(when (kind) {
    DomainKind.PERSON.name -> ai.memora.app.R.string.person_related_kind_person
    DomainKind.PROJECT.name -> ai.memora.app.R.string.person_related_kind_project
    else -> ai.memora.app.R.string.person_related_kind_note
})

@Composable
private fun personRelatedRelationLabel(relation: String): String = s(when (relation) {
    "assigned_to" -> ai.memora.app.R.string.person_related_relation_assigned_to
    "works_on" -> ai.memora.app.R.string.person_related_relation_works_on
    "owes" -> ai.memora.app.R.string.person_related_relation_owes
    "family_of" -> ai.memora.app.R.string.person_related_relation_family
    "part_of" -> ai.memora.app.R.string.person_related_relation_part_of
    else -> ai.memora.app.R.string.person_related_relation_related
})

@Composable
private fun caseStatusLabelForPerson(status: String): String = s(when (status) {
    "waiting" -> ai.memora.app.R.string.case_status_waiting
    "completed" -> ai.memora.app.R.string.case_status_completed
    "cancelled" -> ai.memora.app.R.string.case_status_cancelled
    else -> ai.memora.app.R.string.case_status_open
})

private fun personTimelineOrder(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

@Composable
private fun personCaseSourceTitle(source: CaseEvidenceView): String {
    val kindLabel = personCaseSourceKindLabel(source)
    val title = source.title.takeIf(String::isNotBlank)
    return if (source.eventKind != null && title != null) {
        val action = s(when (source.eventKind) {
            "receipt" -> ai.memora.app.R.string.finance_receipt_label
            "debt" -> ai.memora.app.R.string.voice_debt_label
            else -> ai.memora.app.R.string.finance_expense_label
        })
        "$action: $title"
    } else title ?: kindLabel
}

@Composable
private fun personCaseSourceKindLabel(source: CaseEvidenceView): String = s(when (source.evidence.sourceKind) {
        "task" -> ai.memora.app.R.string.case_source_task
        "calendar_event" -> ai.memora.app.R.string.case_source_calendar
        "memory_revision" -> ai.memora.app.R.string.case_source_memory
        "money_event" -> ai.memora.app.R.string.finance
        else -> ai.memora.app.R.string.case_source_generic
    })

@Composable
private fun personCaseRelationLabel(relation: String): String {
    val resource = when (relation) {
        "follow_up" -> ai.memora.app.R.string.person_source_relation_follow_up
        "appointment" -> ai.memora.app.R.string.person_source_relation_appointment
        else -> null
    }
    return if (resource == null) relation else s(resource)
}

@Composable
private fun PersonCaseDetails(snapshot: CaseSnapshot, onOpenSource: (CaseEvidenceView) -> Unit) {
    snapshot.record.summary?.takeIf(String::isNotBlank)?.let {
        Text(it.take(4_000), modifier = Modifier.testTag("person_case_summary"), color = colors.ink)
    }
    Text(caseStatusLabelForPerson(snapshot.record.status), color = colors.green,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    snapshot.record.nextAction?.takeIf(String::isNotBlank)?.let {
        Text("${s(ai.memora.app.R.string.case_next_action)}: $it", modifier = Modifier.testTag("person_case_next_action"),
            color = colors.muted)
    }
    if (snapshot.participants.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.case_participants), modifier = Modifier.padding(top = 12.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        snapshot.participants.forEach { person -> Text(person.title, color = colors.muted) }
    }
    if (snapshot.timeline.isNotEmpty()) {
        Text(s(ai.memora.app.R.string.case_timeline), modifier = Modifier.padding(top = 12.dp),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.ink)
        snapshot.timeline.asReversed().take(50).forEach { event ->
            val source = snapshot.evidence.firstOrNull { evidence ->
                event.id == "evidence:${evidence.evidence.evidenceId}" && evidence.sourceAvailable
            }
            MemoraCard(modifier = Modifier.padding(top = 8.dp)
                .testTag("person_case_detail_source_${source?.evidence?.evidenceId ?: event.id}"),
                onClick = source?.let { evidence -> { onOpenSource(evidence) } },
                contentPadding = 14.dp) {
                Text(event.title.ifBlank { s(ai.memora.app.R.string.case_timeline_update) }, color = colors.ink,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                event.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(300), color = colors.muted) }
                Text(event.at, color = colors.muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                if (source != null) Text(s(ai.memora.app.R.string.case_source_open_details), color = colors.accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
    }
}

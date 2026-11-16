package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.CaseEvidenceView
import ai.memora.app.data.protectedstore.CaseSnapshot
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.MoneyAdjustmentRow
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
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
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface EntityDetailLoad<out T> {
    data object Loading : EntityDetailLoad<Nothing>
    data class Found<T>(val value: T) : EntityDetailLoad<T>
    data object Missing : EntityDetailLoad<Nothing>
    data object Unavailable : EntityDetailLoad<Nothing>
}

private data class FinanceDetail(
    val event: MoneyEventRow,
    val personName: String?,
    val confirmedPerson: DomainObjectRow?,
    val adjustments: List<MoneyAdjustmentRow>,
    val voided: Boolean,
    val remainingDebt: Long?,
    val cases: List<CaseSnapshot>,
    val relatedTasks: List<Task>,
    val sourceTurn: String?,
    val dateZone: ZoneId,
)

private data class TaskDetail(
    val task: Task,
    val linkedPeople: List<DomainObjectRow>,
    val cases: List<CaseSnapshot>,
    val sourceTurns: List<String>,
    val revisions: List<ai.memora.app.data.protectedstore.TaskEventRow>,
)

private sealed interface SourceDetail {
    data class Evidence(val caseTitle: String, val evidence: CaseEvidenceView) : SourceDetail
    data class Conversation(
        val messages: List<AgentConversationMessageRow>,
        val actionResults: List<AgentActionResultRow>,
    ) : SourceDetail
}

@Composable
internal fun CaseTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedCaseRepository(database) }
    val coroutineScope = rememberCoroutineScope()
    var detail by remember(route.targetId, scopeId) { mutableStateOf<EntityDetailLoad<CaseSnapshot>>(EntityDetailLoad.Loading) }
    var updating by remember { mutableStateOf(false) }
    var updateFailed by remember { mutableStateOf(false) }

    suspend fun loadExact() {
        val id = route.targetId ?: return
        detail = try {
            withContext(Dispatchers.IO) {
                repository.caseSnapshot(scopeId, id)?.let(EntityDetailLoad<CaseSnapshot>::Found)
                    ?: EntityDetailLoad.Missing
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EntityDetailLoad.Unavailable
        }
    }
    LaunchedEffect(route.targetId, scopeId, database) { loadExact() }

    when (val current = detail) {
        EntityDetailLoad.Loading -> LoadingEntityScreen(R.string.cases)
        EntityDetailLoad.Missing -> MissingEntityScreen()
        EntityDetailLoad.Unavailable -> UnavailableEntityScreen()
        is EntityDetailLoad.Found -> {
            val snapshot = current.value
            ScreenColumn(Modifier.testTag("action_target_${snapshot.record.caseId}")) {
                ScreenHeading(snapshot.record.title, s(R.string.cases))
                MemoraCard(modifier = Modifier.testTag("case_detail_header")) {
                    Text(caseStatusText(snapshot.record.status), color = colors.green,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("case_detail_status"))
                    snapshot.record.summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = colors.ink, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("case_detail_summary"))
                    }
                    snapshot.record.nextAction?.takeIf(String::isNotBlank)?.let {
                        Text("${s(R.string.case_next_action)}: $it", color = colors.ink,
                            modifier = Modifier.testTag("case_detail_next_action"))
                    }
                    Text("${s(R.string.action_related_updated)}: ${snapshot.record.updatedAt.take(16)}",
                        color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    if (updateFailed) Text(s(R.string.case_update_error), color = colors.amber,
                        modifier = Modifier.testTag("case_update_error"))
                    if (updating) Text(s(R.string.ai_loading), color = colors.muted,
                        modifier = Modifier.testTag("case_detail_updating"))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!updating && snapshot.record.status == "open") OutlinedButton(
                            modifier = Modifier.fillMaxWidth().testTag("case_action_waiting"),
                            onClick = { changeCaseStatus(coroutineScope, repository, scopeId, snapshot, "waiting", { updating = it }, { updateFailed = it }, { detail = it }) },
                        ) { Text(s(R.string.case_mark_waiting)) }
                        if (!updating && snapshot.record.status != "completed") OutlinedButton(
                            modifier = Modifier.fillMaxWidth().testTag("case_action_complete"),
                            onClick = { changeCaseStatus(coroutineScope, repository, scopeId, snapshot, "completed", { updating = it }, { updateFailed = it }, { detail = it }) },
                        ) { Text(s(R.string.case_mark_completed)) }
                        if (!updating && snapshot.record.status in setOf("waiting", "completed", "cancelled")) OutlinedButton(
                            modifier = Modifier.fillMaxWidth().testTag("case_action_reopen"),
                            onClick = { changeCaseStatus(coroutineScope, repository, scopeId, snapshot, "open", { updating = it }, { updateFailed = it }, { detail = it }) },
                        ) { Text(s(R.string.case_reopen)) }
                    }
                }
                if (snapshot.participants.isNotEmpty()) {
                    DetailSectionTitle(s(R.string.case_participants))
                    snapshot.participants.forEach { person ->
                        val target = AppRoute.target(AppTargetKind.PERSON, person.objectId)
                        MemoraCard(modifier = Modifier.testTag("case_detail_person_${person.objectId}"),
                            onClick = target?.let { { onOpenTarget(it) } }) {
                            Text(person.title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (snapshot.evidence.isNotEmpty()) {
                    DetailSectionTitle(s(R.string.case_sources))
                    snapshot.evidence.forEach { source ->
                        val sourceRoute = AppRoute.source(source.evidence.evidenceId)
                        val linkedTarget = evidenceObjectRoute(source)
                        MemoraCard(
                            modifier = Modifier.testTag("case_detail_evidence_${source.evidence.evidenceId}"),
                            onClick = sourceRoute?.let { { onOpenTarget(it) } },
                        ) {
                            Text(source.title.ifBlank { s(R.string.case_source_generic) }, color = colors.ink,
                                style = MaterialTheme.typography.titleSmall)
                            Text("${s(R.string.case_source_relation)}: ${source.evidence.relation}", color = colors.muted)
                            Text("${s(R.string.case_source_time)}: ${source.evidence.occurredAt.take(16)}", color = colors.muted,
                                style = MaterialTheme.typography.bodySmall)
                            source.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(500), color = colors.muted) }
                            if (!source.sourceAvailable) Text(s(R.string.case_source_missing), color = colors.amber)
                            else Text(s(R.string.case_source_open_details), color = colors.accent,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        if (source.sourceAvailable && linkedTarget != null) TextButton(
                            modifier = Modifier.testTag("case_detail_open_object_${source.evidence.evidenceId}"),
                            onClick = { onOpenTarget(linkedTarget) },
                        ) { Text(s(R.string.entity_source_open_record)) }
                    }
                }
                if (snapshot.timeline.isNotEmpty()) {
                    DetailSectionTitle(s(R.string.case_timeline))
                    snapshot.timeline.asReversed().forEach { event ->
                        val matchingSource = snapshot.evidence.firstOrNull {
                            it.evidence.sourceKind == event.sourceKind && it.evidence.sourceId == event.sourceId &&
                                it.evidence.sourceRevisionId == event.sourceRevisionId
                        }
                        val sourceRoute = matchingSource?.let { AppRoute.source(it.evidence.evidenceId) }
                        MemoraCard(
                            modifier = Modifier.testTag("case_detail_timeline_${event.id}"),
                            onClick = sourceRoute?.let { { onOpenTarget(it) } },
                        ) {
                            Text(event.title.ifBlank { s(R.string.case_timeline_update) }, color = colors.ink,
                                style = MaterialTheme.typography.titleSmall)
                            event.detail?.takeIf(String::isNotBlank)?.let { Text(it.take(500), color = colors.muted) }
                            event.sourceExcerpt?.let { Text(it, color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                            Text(event.at.take(16), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun changeCaseStatus(
    scope: kotlinx.coroutines.CoroutineScope,
    repository: ProtectedCaseRepository,
    scopeId: String,
    snapshot: CaseSnapshot,
    status: String,
    setUpdating: (Boolean) -> Unit,
    setFailed: (Boolean) -> Unit,
    setDetail: (EntityDetailLoad<CaseSnapshot>) -> Unit,
) {
    scope.launch {
        setUpdating(true)
        setFailed(false)
        try {
            withContext(Dispatchers.IO) {
                repository.updateCase(scopeId, snapshot.record.caseId, snapshot.record.version,
                    snapshot.record.title, snapshot.record.summary, status, snapshot.record.nextAction,
                    sourceTurnId = "owner-ui-${UUID.randomUUID()}", now = Instant.now().toString())
                repository.caseSnapshot(scopeId, snapshot.record.caseId)
            }?.let { setDetail(EntityDetailLoad.Found(it)) } ?: setDetail(EntityDetailLoad.Missing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setFailed(true)
            setDetail(withContext(Dispatchers.IO) {
                runCatching { repository.caseSnapshot(scopeId, snapshot.record.caseId) }.getOrNull()
                    ?.let(EntityDetailLoad<CaseSnapshot>::Found) ?: EntityDetailLoad.Unavailable
            })
        } finally {
            setUpdating(false)
        }
    }
}

@Composable
internal fun FinanceTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    var detail by remember(route.targetId, scopeId) { mutableStateOf<EntityDetailLoad<FinanceDetail>>(EntityDetailLoad.Loading) }
    LaunchedEffect(route.targetId, scopeId, database) {
        detail = EntityDetailLoad.Loading
        detail = withContext(Dispatchers.IO) {
            try {
                val id = route.targetId ?: return@withContext EntityDetailLoad.Missing
                val event = repository.moneyEvent(scopeId, id) ?: return@withContext EntityDetailLoad.Missing
                val dao = database.protectedStoreDao()
                val financePerson = dao.person(scopeId, event.personId)
                val confirmedPerson = dao.activeIdentityLinkForFinancePerson(scopeId, event.personId)
                    ?.let { dao.domainObject(scopeId, it.domainPersonId) }
                    ?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
                val caseRepository = ProtectedCaseRepository(database)
                val linkedCases = dao.caseEvidenceForSource(scopeId, "money_event", id)
                    .mapNotNull { caseRepository.caseSnapshot(scopeId, it.caseId) }
                    .distinctBy { it.record.caseId }
                val taskRepository = ProtectedMemoryRepository(database)
                val linkedTaskIds = linkedCases.flatMap { snapshot -> snapshot.evidence }
                    .filter { it.sourceAvailable && it.evidence.sourceKind == "task" }
                    .map { it.evidence.sourceId }.toSet()
                val linkedTasks = taskRepository.tasks().tasks.filter { it.scopeId == scopeId && it.id in linkedTaskIds }
                val displayZone = runCatching { ZoneId.of(dao.localProfile()?.timeZoneId ?: "Asia/Baku") }
                    .getOrDefault(ZoneId.of("Asia/Baku"))
                val sourceTurn = dao.actionResultsForTarget(scopeId, "finance", id, 20)
                    .firstOrNull { it.status == "APPLIED" }?.turnId
                EntityDetailLoad.Found(FinanceDetail(
                    event = event,
                    personName = financePerson?.displayName,
                    confirmedPerson = confirmedPerson,
                    adjustments = dao.adjustments(scopeId, id),
                    voided = repository.isFinanceEventVoided(scopeId, id),
                    remainingDebt = if (event.kind == "debt") repository.outstandingDebtMinor(scopeId, id) else null,
                    cases = linkedCases,
                    relatedTasks = linkedTasks,
                    sourceTurn = sourceTurn,
                    dateZone = displayZone,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EntityDetailLoad.Unavailable
            }
        }
    }

    when (val current = detail) {
        EntityDetailLoad.Loading -> LoadingEntityScreen(R.string.finance)
        EntityDetailLoad.Missing -> MissingEntityScreen()
        EntityDetailLoad.Unavailable -> UnavailableEntityScreen()
        is EntityDetailLoad.Found -> {
            val value = current.value
            val event = value.event
            val kindLabel = when (event.kind) {
                "debt" -> s(R.string.voice_debt_label)
                "expense" -> s(R.string.finance_expense_label)
                else -> s(R.string.finance_receipt_label)
            }
            val direction = when (event.kind) {
                "debt" -> s(R.string.finance_direction_debt)
                "expense" -> s(R.string.finance_direction_spent)
                else -> s(R.string.finance_direction_received)
            }
            ScreenColumn(Modifier.testTag("action_target_${event.eventId}")) {
                ScreenHeading(kindLabel, s(R.string.finance))
                MemoraCard(modifier = Modifier.testTag("finance_detail_header")) {
                    Text(entityMoney(event.minorUnits, event.currency), color = colors.ink,
                        style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("finance_detail_amount"))
                    Text(direction, color = colors.accent, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("finance_detail_direction"))
                    if (value.voided) Text(s(R.string.finance_reversed_label), color = colors.amber)
                    Text("${s(R.string.action_related_status)}: $kindLabel", color = colors.muted)
                    value.personName?.let { name ->
                        if (value.confirmedPerson != null) {
                            val target = AppRoute.target(AppTargetKind.PERSON, value.confirmedPerson.objectId)
                            MemoraCard(modifier = Modifier.testTag("finance_detail_person_${value.confirmedPerson.objectId}"),
                                onClick = target?.let { { onOpenTarget(it) } }, contentPadding = 12.dp) {
                                Text(name, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                                Text(s(R.string.person_identity_linked), color = colors.green,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("${s(R.string.action_related_person)}: $name", color = colors.muted,
                                modifier = Modifier.testTag("finance_detail_finance_person"))
                        }
                    }
                    Text("${s(R.string.action_related_date)}: ${entityDate(event.occurredAt ?: event.recordedAt, value.dateZone)}", color = colors.muted)
                    Text("${s(R.string.action_related_source)}: ${event.source}", color = colors.muted,
                        modifier = Modifier.testTag("finance_detail_source"))
                    event.comment?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.ink) }
                    value.remainingDebt?.let { Text("${s(R.string.finance_debt_remaining)}: ${entityMoney(it, event.currency)}",
                        color = colors.muted, modifier = Modifier.testTag("finance_detail_debt_remaining")) }
                }
                value.sourceTurn?.let { turn ->
                    val sourceRoute = AppRoute.source(turn)
                    if (sourceRoute != null) TextButton(modifier = Modifier.testTag("finance_detail_open_source"),
                        onClick = { onOpenTarget(sourceRoute) }) { Text(s(R.string.entity_source_turn)) }
                }
                value.cases.forEach { case ->
                    val target = AppRoute.target(AppTargetKind.CASE, case.record.caseId)
                    if (target != null) MemoraCard(modifier = Modifier.testTag("finance_detail_case_${case.record.caseId}"),
                        onClick = { onOpenTarget(target) }) {
                        Text(s(R.string.cases), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                        Text(case.record.title, color = colors.ink, style = MaterialTheme.typography.titleSmall)
                    }
                }
                value.relatedTasks.forEach { task ->
                    val target = AppRoute.target(AppTargetKind.TASK, task.id)
                    if (target != null) MemoraCard(modifier = Modifier.testTag("finance_detail_task_${task.id}"),
                        onClick = { onOpenTarget(target) }) {
                        Text(s(R.string.tasks_title), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                        Text(task.title, color = colors.ink, style = MaterialTheme.typography.titleSmall)
                    }
                }
                DetailSectionTitle(s(R.string.finance_correction_history))
                Text(s(R.string.finance_correction_read_only), color = colors.muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("finance_detail_correction_help"))
                if (value.adjustments.isEmpty()) {
                    Text(s(R.string.working_no_data), color = colors.muted, modifier = Modifier.testTag("finance_detail_no_corrections"))
                } else value.adjustments.forEach { adjustment ->
                    MemoraCard(modifier = Modifier.testTag("finance_detail_adjustment_${adjustment.eventId}")) {
                        Text(adjustmentLabel(adjustment.kind), color = colors.ink, style = MaterialTheme.typography.titleSmall)
                        if (adjustment.minorUnits != 0L) Text(entityMoney(adjustment.minorUnits, adjustment.currency), color = colors.ink)
                        adjustment.reason.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted) }
                        adjustment.occurredAt?.let { Text(entityDate(it, value.dateZone), color = colors.muted,
                            style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    var detail by remember(route.targetId, scopeId) { mutableStateOf<EntityDetailLoad<TaskDetail>>(EntityDetailLoad.Loading) }
    LaunchedEffect(route.targetId, scopeId, database) {
        detail = EntityDetailLoad.Loading
        detail = withContext(Dispatchers.IO) {
            try {
                val id = route.targetId ?: return@withContext EntityDetailLoad.Missing
                val task = ProtectedMemoryRepository(database).tasks().tasks.firstOrNull { it.id == id && it.scopeId == scopeId }
                    ?: return@withContext EntityDetailLoad.Missing
                val dao = database.protectedStoreDao()
                val people = dao.activeTaskPersonLinksForTask(scopeId, id).mapNotNull { link ->
                    dao.domainObject(scopeId, link.domainPersonId)?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
                }
                val caseRepository = ProtectedCaseRepository(database)
                val cases = dao.caseEvidenceForSource(scopeId, "task", id).mapNotNull { caseRepository.caseSnapshot(scopeId, it.caseId) }
                    .distinctBy { it.record.caseId }
                val sourceTurns = dao.actionResultsForTarget(scopeId, "task", id, 20)
                    .filter { it.status == "APPLIED" }.map { it.turnId }.distinct()
                EntityDetailLoad.Found(TaskDetail(task, people, cases, sourceTurns, dao.taskEvents(id, scopeId)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EntityDetailLoad.Unavailable
            }
        }
    }
    when (val current = detail) {
        EntityDetailLoad.Loading -> LoadingEntityScreen(R.string.tasks_title)
        EntityDetailLoad.Missing -> MissingEntityScreen()
        EntityDetailLoad.Unavailable -> UnavailableEntityScreen()
        is EntityDetailLoad.Found -> {
            val value = current.value
            val task = value.task
            ScreenColumn(Modifier.testTag("action_target_${task.id}")) {
                ScreenHeading(task.title, s(R.string.tasks_title))
                MemoraCard(modifier = Modifier.testTag("task_detail_header")) {
                    Text(s(if (task.completed) R.string.task_completed else R.string.task_open), color = colors.accent,
                        modifier = Modifier.testTag("task_detail_status"))
                    task.dueLocal?.let { Text("${s(R.string.task_due)}: ${it.replace('T', ' ')}", color = colors.ink) }
                    task.personLabel?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted) }
                }
                value.linkedPeople.forEach { person ->
                    val target = AppRoute.target(AppTargetKind.PERSON, person.objectId)
                    if (target != null) MemoraCard(modifier = Modifier.testTag("task_detail_person_${person.objectId}"),
                        onClick = { onOpenTarget(target) }) { Text(person.title, color = colors.ink) }
                }
                value.cases.forEach { case ->
                    val target = AppRoute.target(AppTargetKind.CASE, case.record.caseId)
                    if (target != null) MemoraCard(modifier = Modifier.testTag("task_detail_case_${case.record.caseId}"),
                        onClick = { onOpenTarget(target) }) { Text(case.record.title, color = colors.ink) }
                }
                if (value.sourceTurns.isNotEmpty()) {
                    DetailSectionTitle(s(R.string.entity_source_turn))
                    value.sourceTurns.forEach { turn ->
                        val sourceRoute = AppRoute.source(turn)
                        if (sourceRoute != null) TextButton(modifier = Modifier.testTag("task_detail_source_$turn"),
                            onClick = { onOpenTarget(sourceRoute) }) { Text(s(R.string.entity_source_turn)) }
                    }
                }
                if (value.revisions.isNotEmpty()) {
                    DetailSectionTitle(s(R.string.person_timeline))
                    value.revisions.takeLast(20).asReversed().forEach { revision ->
                        MemoraCard(modifier = Modifier.testTag("task_detail_revision_${revision.sequence}")) {
                            Text(revision.type.replace('_', ' '), color = colors.ink)
                            revision.title?.let { Text(it, color = colors.muted) }
                            revision.dueLocal?.let { Text("${s(R.string.task_due)}: ${it.replace('T', ' ')}", color = colors.muted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    var detail by remember(route.targetId, scopeId) { mutableStateOf<EntityDetailLoad<SourceDetail>>(EntityDetailLoad.Loading) }
    LaunchedEffect(route.targetId, scopeId, database) {
        detail = EntityDetailLoad.Loading
        detail = withContext(Dispatchers.IO) {
            try {
                val id = route.targetId ?: return@withContext EntityDetailLoad.Missing
                val dao = database.protectedStoreDao()
                val evidence = dao.caseEvidenceById(scopeId, id)
                if (evidence != null) {
                    val snapshot = ProtectedCaseRepository(database).caseSnapshot(scopeId, evidence.caseId)
                        ?: return@withContext EntityDetailLoad.Missing
                    val resolved = snapshot.evidence.firstOrNull { it.evidence.evidenceId == id }
                        ?: return@withContext EntityDetailLoad.Missing
                    EntityDetailLoad.Found(SourceDetail.Evidence(snapshot.record.title, resolved))
                } else {
                    val conversations = ProtectedConversationRepository(database)
                    val messages = conversations.turn(scopeId, id)
                        .filter { it.role == "user" || it.role == "model" }
                    if (messages.none { it.role == "user" }) EntityDetailLoad.Missing
                    else EntityDetailLoad.Found(SourceDetail.Conversation(messages, conversations.actionResults(scopeId, id)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EntityDetailLoad.Unavailable
            }
        }
    }
    when (val current = detail) {
        EntityDetailLoad.Loading -> LoadingEntityScreen(R.string.entity_source_turn)
        EntityDetailLoad.Missing -> MissingEntityScreen()
        EntityDetailLoad.Unavailable -> UnavailableEntityScreen()
        is EntityDetailLoad.Found -> ScreenColumn(Modifier.testTag("source_detail_screen_${route.targetId}")) {
            when (val source = current.value) {
                is SourceDetail.Conversation -> {
                    ScreenHeading(s(R.string.entity_source_turn), s(R.string.action_related_details))
                    source.messages.forEachIndexed { index, message ->
                        MemoraCard(modifier = Modifier.testTag("source_detail_turn_message_$index")) {
                            Text(s(if (message.role == "user") R.string.chat_speaker_user else R.string.chat_speaker_secretary),
                                color = colors.muted, style = MaterialTheme.typography.labelMedium)
                            Text(message.message, Modifier.testTag("source_detail_turn_text_$index"),
                                color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    source.actionResults.forEach { result ->
                        val targetRoute = AppTargetKind.fromAction(result.targetKind, result.targetId)
                        val statusLabel = s(when (result.status) {
                            "APPLIED" -> R.string.action_status_applied
                            "NEEDS_CONFIRMATION" -> R.string.action_status_confirmation
                            "UNKNOWN" -> R.string.action_status_unknown
                            else -> R.string.action_status_failed
                        })
                        val targetLabel = result.targetKind?.let { kind -> targetAreaLabel(kind) }
                        MemoraCard(modifier = Modifier.testTag("source_detail_action_${result.resultId}")) {
                            Text(statusLabel, color = when (result.status) {
                                "APPLIED" -> colors.green
                                "NEEDS_CONFIRMATION", "UNKNOWN" -> colors.amber
                                else -> colors.amber
                            }, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("source_detail_action_status_${result.resultId}"))
                            targetLabel?.let { Text(it, color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (targetRoute != null) TextButton(
                            modifier = Modifier.testTag("source_detail_action_target_${result.resultId}"),
                            onClick = { onOpenTarget(targetRoute) },
                        ) { Text(s(R.string.action_open_related)) }
                    }
                    TextButton(
                        modifier = Modifier.testTag("source_detail_open_conversation"),
                        onClick = { onOpenTarget(AppRoute.screen(AppDestination.SECRETARY)) },
                    ) { Text(s(R.string.entity_source_open_conversation)) }
                }
                is SourceDetail.Evidence -> {
                    ScreenHeading(source.evidence.title.ifBlank { s(R.string.case_source_generic) }, s(R.string.case_sources))
                    MemoraCard(modifier = Modifier.testTag("source_detail_evidence_${source.evidence.evidence.evidenceId}")) {
                        if (source.evidence.sourceAvailable) {
                            source.evidence.detail?.let { Text(it, color = colors.ink, style = MaterialTheme.typography.bodyLarge) }
                        } else Text(s(R.string.entity_source_missing), color = colors.amber)
                        Text("${s(R.string.case_source_relation)}: ${source.evidence.evidence.relation}", color = colors.muted)
                        Text("${s(R.string.case_source_time)}: ${source.evidence.evidence.occurredAt}", color = colors.muted)
                        source.evidence.evidence.sourceRevisionId?.let {
                            Text("${s(R.string.action_related_updated)}: $it", color = colors.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val target = evidenceObjectRoute(source.evidence)
                    if (source.evidence.sourceAvailable && target != null) TextButton(
                        modifier = Modifier.testTag("source_detail_open_object"),
                        onClick = { onOpenTarget(target) },
                    ) { Text(s(R.string.entity_source_open_record)) }
                    TextButton(modifier = Modifier.testTag("source_detail_open_case"),
                        onClick = {
                            AppRoute.target(AppTargetKind.CASE, source.evidence.evidence.caseId)?.let(onOpenTarget)
                        }) { Text(source.caseTitle) }
                }
            }
        }
    }
}

private fun evidenceObjectRoute(source: CaseEvidenceView): AppRoute? = when (source.evidence.sourceKind) {
    "money_event" -> AppRoute.target(AppTargetKind.FINANCE, source.evidence.sourceId)
    "task" -> AppRoute.target(AppTargetKind.TASK, source.evidence.sourceId)
    "calendar_event" -> AppRoute.target(AppTargetKind.CALENDAR, source.evidence.sourceId)
    "domain_object" -> AppRoute.target(AppTargetKind.MEMORY, source.evidence.sourceId)
    else -> null
}

@Composable
private fun targetAreaLabel(kind: String): String? {
    val label = when (kind) {
        "person" -> R.string.people
        "finance" -> R.string.finance
        "case" -> R.string.cases
        "task" -> R.string.tasks_title
        "calendar" -> R.string.calendar_title
        "table" -> R.string.tables_title
        "memory" -> R.string.nav_memory
        else -> return null
    }
    return s(label)
}

@Composable
private fun adjustmentLabel(kind: String): String = s(when (kind) {
    "correction" -> R.string.finance_adjustment_correction
    "reversal" -> R.string.finance_adjustment_reversal
    "amount_correction" -> R.string.finance_adjustment_amount
    "settlement" -> R.string.finance_adjustment_settlement
    "annotation" -> R.string.finance_adjustment_annotation
    "event_void" -> R.string.finance_adjustment_void
    "event_restore" -> R.string.finance_adjustment_restore
    else -> R.string.finance_correction_history
})

private fun entityMoney(minorUnits: Long, currency: String): String {
    val amount = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(minorUnits, 2))
    return "$amount $currency"
}

private fun entityDate(raw: String, zone: ZoneId): String = runCatching {
    val dateTime = runCatching { Instant.parse(raw).atZone(zone).toLocalDateTime() }.getOrElse { LocalDateTime.parse(raw) }
    dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
}.getOrDefault(raw.take(32))

@Composable
private fun caseStatusText(status: String): String = s(when (status) {
    "waiting" -> R.string.case_status_waiting
    "completed" -> R.string.case_status_completed
    "cancelled" -> R.string.case_status_cancelled
    else -> R.string.case_status_open
})

@Composable
private fun DetailSectionTitle(title: String) {
    Text(title, color = colors.ink, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp).testTag("entity_detail_section"))
}

@Composable
private fun LoadingEntityScreen(title: Int) {
    ScreenColumn(Modifier.testTag("entity_detail_loading")) {
        ScreenHeading(s(title), s(R.string.action_related_loading))
        androidx.compose.material3.CircularProgressIndicator(color = colors.accent)
    }
}

@Composable
private fun MissingEntityScreen() = ScreenColumn(Modifier.testTag("entity_detail_missing")) {
    EmptyState(s(R.string.action_related_missing), s(R.string.entity_record_unavailable))
}

@Composable
private fun UnavailableEntityScreen() = ScreenColumn(Modifier.testTag("entity_detail_unavailable")) {
    EmptyState(s(R.string.action_related_unavailable), s(R.string.action_related_unavailable_body))
}

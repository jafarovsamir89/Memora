package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.CaptureEntry
import ai.memora.app.data.CaptureSessionState
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.data.StoreRead
import ai.memora.app.data.protectedstore.LocalProfileRow
import ai.memora.app.data.protectedstore.ConversationTurnEvidence
import ai.memora.app.data.protectedstore.ConversationEpisodeEvidence
import ai.memora.app.data.protectedstore.ProfileFactEvidence
import ai.memora.app.data.protectedstore.DomainHit
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.domain.LocalMemoryIndex
import ai.memora.app.reminders.AttentionEngine
import ai.memora.app.reminders.AttentionKind
import ai.memora.app.reminders.AttentionSuggestion
import ai.memora.app.ui.*
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.NumberFormat

private data class ProfileFactSourceDisplay(val message: String, val exactQuote: String?)
private data class TodayDebtObligation(val event: MoneyEventRow, val personName: String?, val remainingMinor: Long)
private const val MEMORY_DOMAIN_PAGE_SIZE = 30

@Composable
internal fun WorkingHomeScreen(language: String, state: CaptureSessionState, navigate: (String) -> Unit,
    detail: (String) -> Unit, taskStore: EncryptedTaskStore? = null,
    onOpenTarget: (AppRoute) -> Unit = {}) {
    val context = LocalContext.current
    val database = rememberOptionalUiAgentDatabase()
    val processor = remember(context, database) {
        AgentVoiceTurnProcessor(context, database)
    }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val caseRepository = remember(database) { database?.let(::ProtectedCaseRepository) }
    val domainRepository = remember(database) { database?.let(::ProtectedDomainRepository) }
    var dueTasks by remember { mutableStateOf<List<ai.memora.app.domain.Task>>(emptyList()) }
    var openCaseCount by remember { mutableIntStateOf(0) }
    var upcomingEvents by remember { mutableStateOf<List<ai.memora.app.data.protectedstore.LocalCalendarEventRow>>(emptyList()) }
    var debtObligations by remember { mutableStateOf<List<TodayDebtObligation>>(emptyList()) }
    var attentionSuggestions by remember { mutableStateOf<List<AttentionSuggestion>>(emptyList()) }
    var overviewLoading by remember { mutableStateOf(true) }
    var overviewError by remember { mutableStateOf(false) }
    var overviewVersion by remember { mutableIntStateOf(0) }
    var dateZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    LaunchedEffect(scopeId, taskStore, database, overviewVersion) {
        overviewLoading = true
        overviewError = false
        try {
            val memory = database ?: error("protected_overview_unavailable")
            val deviceZone = withContext(Dispatchers.IO) {
                runCatching { ZoneId.of(memory.protectedStoreDao().localProfile()?.timeZoneId ?: "Asia/Baku") }
                    .getOrDefault(ZoneId.of("Asia/Baku"))
            }
            dateZone = deviceZone
            val today = LocalDate.now(deviceZone)
            val through = today.plusDays(7)
            val taskRead = taskStore?.let { withContext(Dispatchers.IO) { it.load() } }
            if (taskRead == null || taskRead == StoreRead.Unavailable) overviewError = true
            val legacyTasks = (taskRead as? StoreRead.Ready)?.tasks.orEmpty()
            val protectedTasks = withContext(Dispatchers.IO) {
                ProtectedMemoryRepository(memory).tasks().tasks.filter { it.scopeId == scopeId }
            }
            val mergedTasks = legacyTasks.associateBy { it.id }.toMutableMap().apply {
                protectedTasks.forEach { put(it.id, it) }
            }.values.toList()
            dueTasks = mergedTasks
                .filter { task -> !task.completed && task.dueLocal?.substringBefore('T')?.let { it <= through.toString() } == true }
                .sortedBy { it.dueLocal }
            val cases = caseRepository ?: error("protected_overview_unavailable")
            val domains = domainRepository ?: error("protected_overview_unavailable")
            openCaseCount = cases.openCaseCount(scopeId)
            upcomingEvents = domains.calendarBetween(scopeId, today.atStartOfDay().toString(), today.plusDays(8).atStartOfDay().toString())
            debtObligations = withContext(Dispatchers.IO) {
                val finance = ProtectedDomainRepository(memory)
                val dao = memory.protectedStoreDao()
                buildList {
                    finance.allFinanceEvents(scopeId).filter { it.kind == "debt" }.forEach { event ->
                        if (finance.isFinanceEventVoided(scopeId, event.eventId)) return@forEach
                        val remaining = finance.outstandingDebtMinor(scopeId, event.eventId)
                        if (remaining <= 0L) return@forEach
                        add(TodayDebtObligation(event, dao.person(scopeId, event.personId)?.displayName, remaining))
                    }
                }.sortedByDescending { it.event.occurredAt ?: it.event.recordedAt }.take(20)
            }
            val attentionCases = cases.openCases(scopeId, limit = 100)
            attentionSuggestions = AttentionEngine.suggest(scopeId, mergedTasks, attentionCases, upcomingEvents,
                System.currentTimeMillis(), deviceZone, limit = 3)
        } catch (_: Exception) {
            overviewError = true
        } finally {
            overviewLoading = false
        }
    }
    ScreenColumn {
        Text(LocalDate.now(dateZone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag(language))),
            style = MaterialTheme.typography.labelSmall, color = colors.muted)
        ScreenHeading(s(R.string.home_title), s(R.string.working_home_hint))
        MemoraCard(tint = colors.hero, contentPadding = 14.dp, modifier = Modifier.testTag("today_conversation_entry")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s(R.string.home_hero_title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(s(R.string.secretary_voice_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                VoiceFirstScreen(
                    compact = true,
                    processorOverride = processor,
                    onOpenSettings = { navigate("settings") },
                    onShortTap = { navigate("chat") },
                    onTurnSettled = { overviewVersion++ },
                    modifier = Modifier.testTag("today_shared_voice_control"),
                )
            }
        }
        SectionHeading(s(R.string.today_priorities))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayMetricCard(dueTasks.size, R.string.today_tasks_count, Modifier.weight(1f).testTag("today_tasks_metric")) { navigate("tasks") }
            TodayMetricCard(openCaseCount, R.string.today_cases_count, Modifier.weight(1f).testTag("today_cases_metric")) { navigate("cases") }
            TodayMetricCard(upcomingEvents.size, R.string.today_events_count, Modifier.weight(1f).testTag("today_events_metric")) { navigate("calendar") }
        }
        if (overviewLoading) Text(s(R.string.today_loading), color = colors.muted, modifier = Modifier.testTag("today_loading"))
        if (overviewError) Text(s(R.string.today_partial_error), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("today_data_error"))
        if (debtObligations.isNotEmpty()) {
            SectionHeading(s(R.string.today_financial_obligations), { navigate("finance") })
            debtObligations.take(5).forEach { item ->
                val route = AppRoute.target(AppTargetKind.FINANCE, item.event.eventId)
                MemoraCard(
                    onClick = { if (route != null) onOpenTarget(route) else navigate("finance") },
                    modifier = Modifier.testTag("today_debt_${item.event.eventId}"),
                ) {
                    Text(item.personName ?: s(R.string.finance_direction_debt), color = colors.ink,
                        style = MaterialTheme.typography.titleSmall)
                    Text("${s(R.string.finance_debt_remaining)}: ${todayMoney(item.remainingMinor, item.event.currency)}",
                        color = colors.accent, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (debtObligations.size > 5) TextButton(onClick = { navigate("finance") },
                modifier = Modifier.fillMaxWidth().testTag("today_debts_open_all")) { Text(s(R.string.finance)) }
        }
        if (!overviewLoading && !overviewError && dueTasks.isEmpty() && openCaseCount == 0 && upcomingEvents.isEmpty() && debtObligations.isEmpty()) {
            EmptyState(s(R.string.today_clear_title), s(R.string.today_clear_body), Glyph.Calendar)
        }
        if (attentionSuggestions.isNotEmpty()) {
            SectionHeading(s(R.string.today_attention_suggestions))
            attentionSuggestions.forEach { suggestion ->
                val (label, section, targetKind) = when (suggestion.kind) {
                    AttentionKind.TASK -> Triple(R.string.today_due_tasks, "tasks", AppTargetKind.TASK)
                    AttentionKind.CASE -> Triple(R.string.today_open_cases, "cases", AppTargetKind.CASE)
                    AttentionKind.CALENDAR -> Triple(R.string.today_upcoming, "calendar", AppTargetKind.CALENDAR)
                }
                val tag = when (suggestion.kind) {
                    AttentionKind.TASK -> "today_task_${suggestion.sourceId}"
                    AttentionKind.CASE -> "today_case_${suggestion.sourceId}"
                    AttentionKind.CALENDAR -> "today_event_${suggestion.sourceId}"
                }
                val target = AppRoute.target(targetKind, suggestion.sourceId)
                MemoraCard(onClick = { if (target != null) onOpenTarget(target) else navigate(section) }, modifier = Modifier.testTag(tag)) {
                    Text(s(label), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                    Text(suggestion.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    suggestion.sourceLocalTime?.let { Text(it.replace('T', ' '), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    suggestion.detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                }
            }
        }
        SectionHeading(s(R.string.recent), { navigate("memory") })
        MemoryLoadState(state)
        if (state.ready && state.captures.none { !it.archived }) EmptyState(s(R.string.working_memory_empty), s(R.string.working_capture_hint))
        state.captures.asReversed().filter { !it.archived }.take(3).forEach { StoredMemoryCard(it) { detail(it.source.id) } }
    }
}

@Composable
private fun TodayMetricCard(count: Int, label: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MemoraCard(tint = colors.softAccent, modifier = modifier.heightIn(min = 76.dp), onClick = onClick, contentPadding = 12.dp) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = colors.accent)
        Text(s(label), style = MaterialTheme.typography.labelSmall, color = colors.muted, maxLines = 2)
    }
}

private fun todayMoney(minorUnits: Long, currency: String): String {
    val amount = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(minorUnits, 2))
    return "$amount $currency"
}

@Composable
private fun MemoryLoadState(state: CaptureSessionState) {
    if (state.loading) Text(s(R.string.capture_loading))
    if (!state.ready && state.error) Text(s(R.string.capture_storage_error), color = MaterialTheme.colorScheme.error)
}

@Composable
fun StoredMemoryCard(entry: CaptureEntry, onClick: () -> Unit) {
    MemoraCard(onClick = onClick, modifier = Modifier.testTag("memory_${entry.source.id}")) {
        Text(entry.source.revisions.last().text, style = MaterialTheme.typography.bodyLarge)
        Text(entry.source.revisions.first().recordedAt.toString(), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(s(R.string.source), style = MaterialTheme.typography.labelSmall, color = colors.accent)
    }
}

@Composable
internal fun WorkingMemoryScreen(state: CaptureSessionState, detail: (String) -> Unit, capture: () -> Unit, manage: () -> Unit,
    localProfile: LocalProfileRow? = null,
    updateLocalProfile: ((LocalProfileInput, (Boolean) -> Unit) -> Unit)? = null,
    onOpenTarget: (AppRoute) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    val matches = LocalMemoryIndex.search(state.captures, query, includeArchived)
    val context = LocalContext.current
    val database = rememberOptionalUiAgentDatabase()
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val conversationRepository = remember(database) { database?.let(::ProtectedConversationRepository) }
    val domainRepository = remember(database) { database?.let(::ProtectedDomainRepository) }
    val profileMemoryRepository = remember(database) { database?.let(::ProtectedProfileMemoryRepository) }
    val coroutineScope = rememberCoroutineScope()
    var conversationTurns by remember { mutableStateOf<List<ConversationTurnEvidence>>(emptyList()) }
    var conversationEpisodes by remember { mutableStateOf<List<ConversationEpisodeEvidence>>(emptyList()) }
    var domainFacts by remember { mutableStateOf<List<MemoryDomainFact>>(emptyList()) }
    var domainNextOffsets by remember { mutableStateOf<Map<DomainKind, Int>>(emptyMap()) }
    var domainPageLoadingKinds by remember { mutableStateOf<Set<DomainKind>>(emptySet()) }
    var profileFacts by remember { mutableStateOf<List<ProfileFactEvidence>>(emptyList()) }
    var archiveReady by remember { mutableStateOf(false) }
    var archiveLoading by remember { mutableStateOf(false) }
    var conversationPageLoading by remember { mutableStateOf(false) }
    var conversationNextCursor by remember { mutableStateOf<Long?>(null) }
    var conversationQueryGeneration by remember { mutableIntStateOf(0) }
    var archiveError by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableIntStateOf(0) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var showRetention by remember { mutableStateOf(false) }
    var pendingRetentionDays by remember { mutableIntStateOf(-1) }
    var selectedTurn by remember { mutableStateOf<ConversationTurnEvidence?>(null) }
    var selectedEpisode by remember { mutableStateOf<ConversationEpisodeEvidence?>(null) }
    var pendingDeleteTurn by remember { mutableStateOf<ConversationTurnEvidence?>(null) }
    var pendingDeleteEpisode by remember { mutableStateOf<ConversationEpisodeEvidence?>(null) }
    var selectedFact by remember { mutableStateOf<MemoryDomainFact?>(null) }
    var selectedProfileFact by remember { mutableStateOf<ProfileFactEvidence?>(null) }
    var profileFactSources by remember { mutableStateOf<List<ProfileFactSourceDisplay>>(emptyList()) }
    var profileFactSourcesLoading by remember { mutableStateOf(false) }
    var editingProfileFact by remember { mutableStateOf<ProfileFactEvidence?>(null) }
    var creatingProfileFact by remember { mutableStateOf(false) }
    var profileFactSaving by remember { mutableStateOf(false) }
    var profileFactSaveError by remember { mutableStateOf(false) }
    var pendingDeleteProfileFact by remember { mutableStateOf<ProfileFactEvidence?>(null) }
    var showLocalProfileEditor by remember { mutableStateOf(false) }
    var localProfileSaveError by remember { mutableStateOf(false) }
    var localProfileSaving by remember { mutableStateOf(false) }
    var selectedFilterName by rememberSaveable { mutableStateOf(MemoryScreenFilter.ALL.name) }
    val selectedFilter = MemoryScreenFilter.entries.firstOrNull { it.name == selectedFilterName } ?: MemoryScreenFilter.ALL
    val listState = rememberLazyListState()

    LaunchedEffect(scopeId) {
        try {
            if (conversationRepository == null) error("protected_memory_unavailable")
            retentionDays = conversationRepository.retentionDays(scopeId)
            conversationRepository.applyRetention(scopeId)
            archiveReady = true
        } catch (_: Exception) {
            archiveError = true
        }
    }
    LaunchedEffect(scopeId, query, refreshVersion, archiveReady) {
        if (!archiveReady) return@LaunchedEffect
        conversationQueryGeneration++
        val queryGeneration = conversationQueryGeneration
        conversationTurns = emptyList()
        conversationNextCursor = null
        domainNextOffsets = emptyMap()
        domainPageLoadingKinds = emptySet()
        archiveLoading = true
        delay(250)
        try {
            val conversations = conversationRepository ?: error("protected_memory_unavailable")
            val domain = domainRepository ?: error("protected_memory_unavailable")
            val conversationPage = conversations.conversationTurnPage(scopeId, query.trim(), limit = 30)
            if (queryGeneration != conversationQueryGeneration) return@LaunchedEffect
            conversationTurns = conversationPage.turns
            conversationNextCursor = conversationPage.nextBeforeSequence
            conversationEpisodes = conversations.searchEpisodes(scopeId, query.trim(), limit = 30)
            domainFacts = if (query.isBlank()) {
                val firstPages = mutableListOf<MemoryDomainFact>()
                val nextOffsets = mutableMapOf<DomainKind, Int>()
                DomainKind.entries.forEach { kind ->
                    val page = domain.domainObjectsPage(scopeId, kind, MEMORY_DOMAIN_PAGE_SIZE + 1, 0)
                    if (queryGeneration != conversationQueryGeneration) return@LaunchedEffect
                    firstPages += page.take(MEMORY_DOMAIN_PAGE_SIZE).map(::MemoryDomainFact)
                    if (page.size > MEMORY_DOMAIN_PAGE_SIZE) nextOffsets[kind] = MEMORY_DOMAIN_PAGE_SIZE
                }
                domainNextOffsets = nextOffsets
                firstPages
            } else {
                domain.search(scopeId, query.trim(), limit = 30).map(::MemoryDomainFact)
            }
            profileFacts = profileMemoryRepository?.list(scopeId, query.trim(), limit = 200).orEmpty()
            archiveError = false
        } catch (_: Exception) {
            archiveError = true
        } finally {
            archiveLoading = false
        }
    }

    fun loadMoreConversationTurns() {
        val cursor = conversationNextCursor ?: return
        if (conversationPageLoading) return
        val requestedQuery = query.trim()
        val requestedRefresh = refreshVersion
        val requestedGeneration = conversationQueryGeneration
        coroutineScope.launch {
            conversationPageLoading = true
            try {
                val conversations = conversationRepository ?: error("protected_memory_unavailable")
                val page = conversations.conversationTurnPage(scopeId, requestedQuery, cursor, limit = 30)
                if (requestedGeneration != conversationQueryGeneration || requestedQuery != query.trim() || requestedRefresh != refreshVersion) {
                    return@launch
                }
                conversationTurns = (conversationTurns + page.turns).distinctBy { it.turnId }
                conversationNextCursor = page.nextBeforeSequence
            } catch (_: Exception) {
                if (requestedGeneration == conversationQueryGeneration && requestedQuery == query.trim() && requestedRefresh == refreshVersion) {
                    archiveError = true
                }
            } finally {
                conversationPageLoading = false
            }
        }
    }

    fun loadMoreDomainObjects(kind: DomainKind) {
        val offset = domainNextOffsets[kind] ?: return
        if (kind in domainPageLoadingKinds || query.isNotBlank()) return
        val requestedQuery = query.trim()
        val requestedRefresh = refreshVersion
        val requestedGeneration = conversationQueryGeneration
        domainPageLoadingKinds = domainPageLoadingKinds + kind
        coroutineScope.launch {
            try {
                val domain = domainRepository ?: error("protected_memory_unavailable")
                val page = domain.domainObjectsPage(scopeId, kind, MEMORY_DOMAIN_PAGE_SIZE + 1, offset)
                if (requestedGeneration != conversationQueryGeneration || requestedQuery != query.trim() ||
                    requestedRefresh != refreshVersion) return@launch
                val visible = page.take(MEMORY_DOMAIN_PAGE_SIZE).map(::MemoryDomainFact)
                domainFacts = (domainFacts + visible).distinctBy { it.id }
                domainNextOffsets = if (page.size > MEMORY_DOMAIN_PAGE_SIZE) {
                    domainNextOffsets + (kind to (offset + visible.size))
                } else domainNextOffsets - kind
            } catch (_: Exception) {
                if (requestedGeneration == conversationQueryGeneration && requestedQuery == query.trim() &&
                    requestedRefresh == refreshVersion) archiveError = true
            } finally {
                domainPageLoadingKinds = domainPageLoadingKinds - kind
            }
        }
    }

    fun saveRetention(days: Int) {
        coroutineScope.launch {
            try {
                val conversations = conversationRepository ?: error("protected_memory_unavailable")
                conversations.setRetentionDays(scopeId, days)
                retentionDays = days
                refreshVersion++
                archiveError = false
            } catch (_: Exception) { archiveError = true }
        }
    }

    fun openProfileFact(fact: ProfileFactEvidence) {
        selectedProfileFact = fact
        profileFactSources = emptyList()
        profileFactSourcesLoading = fact.sourceTurnIds.isNotEmpty()
        if (fact.sourceTurnIds.isEmpty()) return
        coroutineScope.launch {
            try {
                val conversations = conversationRepository ?: return@launch
                profileFactSources = fact.sourceEvidence.mapNotNull { source ->
                    val message = conversations.turn(scopeId, source.turnId).firstOrNull { it.role == "user" }?.message
                        ?: return@mapNotNull null
                    val start = source.sourceStartUtf16
                    val end = source.sourceEndUtf16
                    val quote = if (start != null && end != null && start >= 0 && end > start && end <= message.length) {
                        message.substring(start, end)
                    } else null
                    ProfileFactSourceDisplay(message, quote)
                }
            } catch (_: Exception) { archiveError = true }
            finally { profileFactSourcesLoading = false }
        }
    }

    fun saveLocalProfile(input: LocalProfileInput) {
        localProfileSaveError = false
        localProfileSaving = true
        val updater = updateLocalProfile
        if (updater == null) {
            localProfileSaving = false
            localProfileSaveError = true
            return
        }
        updater(input) { succeeded ->
            localProfileSaving = false
            localProfileSaveError = !succeeded
            if (succeeded) showLocalProfileEditor = false
        }
    }

    if (showRetention) AlertDialog(
        onDismissRequest = { showRetention = false },
        title = { Text(s(R.string.memory_retention_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(R.string.memory_retention_explainer), color = colors.muted)
                listOf(0, 365, 90, 30).forEach { days ->
                    TextButton(onClick = {
                        showRetention = false
                        if (days == 0) saveRetention(days) else pendingRetentionDays = days
                    }, modifier = Modifier.fillMaxWidth().testTag("memory_retention_option_$days")) {
                        Text(s(memoryRetentionLabel(days)), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRetention = false }) { Text(s(R.string.close)) } },
    )
    if (pendingRetentionDays > 0) AlertDialog(
        onDismissRequest = { pendingRetentionDays = -1 },
        title = { Text(s(R.string.memory_retention_delete_title)) },
        text = { Text(s(R.string.memory_retention_delete_body)) },
        confirmButton = { TextButton(onClick = { val days = pendingRetentionDays; pendingRetentionDays = -1; saveRetention(days) },
            modifier = Modifier.testTag("memory_retention_confirm")) { Text(s(R.string.memory_retention_confirm)) } },
        dismissButton = { TextButton(onClick = { pendingRetentionDays = -1 }) { Text(s(R.string.task_cancel)) } },
    )
    selectedEpisode?.let { episode -> AlertDialog(
        onDismissRequest = { selectedEpisode = null },
        title = { Text(episode.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(episode.summary, color = colors.ink)
                Text(LocalStrings.current.getString(R.string.memory_episode_revision, episode.revision),
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
                Text(s(R.string.memory_episode_sources), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                episode.sourceTurnIds.forEachIndexed { index, turnId ->
                    TextButton(onClick = {
                        coroutineScope.launch {
                            val source = conversationRepository?.turnEvidence(scopeId, turnId)
                            if (source != null) { selectedEpisode = null; selectedTurn = source }
                            else archiveError = true
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("memory_episode_source_$index")) {
                        Text(s(R.string.memory_open_dialogue), modifier = Modifier.fillMaxWidth())
                    }
                }
                val olderRevisions = episode.revisions.dropLast(1).takeLast(5).asReversed()
                if (olderRevisions.isNotEmpty()) {
                    Text(s(R.string.memory_episode_previous_versions), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    olderRevisions.forEach { revision ->
                        Text(LocalStrings.current.getString(R.string.memory_episode_revision_entry, revision.revision,
                            revision.summary), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(DateFormatters.memoryDate(episode.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { pendingDeleteEpisode = episode; selectedEpisode = null },
            modifier = Modifier.testTag("memory_episode_delete")) { Text(s(R.string.memory_episode_delete)) } },
        dismissButton = { TextButton(onClick = { selectedEpisode = null }) { Text(s(R.string.close)) } },
    ) }
    pendingDeleteEpisode?.let { episode -> AlertDialog(
        onDismissRequest = { pendingDeleteEpisode = null },
        title = { Text(s(R.string.memory_episode_delete)) },
        text = { Text(s(R.string.memory_episode_delete_confirmation)) },
        confirmButton = { TextButton(onClick = {
            pendingDeleteEpisode = null
            coroutineScope.launch {
                try {
                    val conversations = conversationRepository ?: error("protected_memory_unavailable")
                    conversations.deleteEpisode(scopeId, episode.episodeId); refreshVersion++; archiveError = false
                } catch (_: Exception) { archiveError = true }
            }
        }, modifier = Modifier.testTag("memory_episode_delete_confirm")) { Text(s(R.string.memory_episode_delete)) } },
        dismissButton = { TextButton(onClick = { pendingDeleteEpisode = null }) { Text(s(R.string.task_cancel)) } },
    ) }
    selectedTurn?.let { turn -> AlertDialog(
        onDismissRequest = { selectedTurn = null },
        title = { Text(s(R.string.memory_conversation_detail)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s(R.string.memory_user_said), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                Text(turn.userMessage, color = colors.ink)
                Text(s(R.string.memory_assistant_replied), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                Text(turn.assistantMessage, color = colors.ink)
                Text(DateFormatters.memoryDate(turn.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { pendingDeleteTurn = turn; selectedTurn = null },
            modifier = Modifier.testTag("memory_turn_delete")) { Text(s(R.string.memory_delete_turn)) } },
        dismissButton = { TextButton(onClick = { selectedTurn = null }) { Text(s(R.string.close)) } },
    ) }
    pendingDeleteTurn?.let { turn -> AlertDialog(
        onDismissRequest = { pendingDeleteTurn = null },
        title = { Text(s(R.string.memory_delete_turn)) },
        text = { Text(s(R.string.memory_delete_turn_confirmation)) },
        confirmButton = { TextButton(onClick = {
            pendingDeleteTurn = null
            coroutineScope.launch {
                try {
                    val conversations = conversationRepository ?: error("protected_memory_unavailable")
                    conversations.deleteTurn(scopeId, turn.turnId); refreshVersion++; archiveError = false
                }
                catch (_: Exception) { archiveError = true }
            }
        }, modifier = Modifier.testTag("memory_turn_delete_confirm")) { Text(s(R.string.memory_delete_turn)) } },
        dismissButton = { TextButton(onClick = { pendingDeleteTurn = null }) { Text(s(R.string.task_cancel)) } },
    ) }
    selectedFact?.let { fact -> AlertDialog(
        onDismissRequest = { selectedFact = null },
        title = { Text(fact.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fact.body?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.ink) }
                Text(s(R.string.memory_source_saved), style = MaterialTheme.typography.labelMedium, color = colors.accent)
                fact.provenanceLabel?.let { Text(s(it), style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            }
        },
        confirmButton = { TextButton(onClick = { selectedFact = null }) { Text(s(R.string.close)) } },
    ) }
    if (showLocalProfileEditor && localProfile != null) LocalProfileEditorDialog(
        profile = localProfile,
        saving = localProfileSaving,
        saveError = localProfileSaveError,
        onDismiss = { if (!localProfileSaving) showLocalProfileEditor = false },
        onSave = ::saveLocalProfile,
    )
    selectedProfileFact?.let { fact -> AlertDialog(
        onDismissRequest = { selectedProfileFact = null },
        title = { Text(s(R.string.profile_memory_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fact.fact, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                Text(s(profileFactCategoryLabel(fact.category)), style = MaterialTheme.typography.labelMedium, color = colors.accent)
                Text(LocalStrings.current.getString(R.string.profile_memory_revision, fact.revision),
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
                if (fact.sourceTurnIds.isEmpty()) {
                    Text(s(R.string.profile_memory_manual_source), color = colors.muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("profile_fact_manual_source"))
                } else {
                    Text(s(R.string.profile_memory_sources), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    if (profileFactSourcesLoading) Text(s(R.string.capture_loading), color = colors.muted)
                    if (!profileFactSourcesLoading && profileFactSources.isEmpty())
                        Text(s(R.string.profile_memory_source_unavailable), color = colors.muted)
                    profileFactSources.forEach { source ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("profile_fact_conversation_source")) {
                            Text(source.message, color = colors.muted, style = MaterialTheme.typography.bodySmall)
                            source.exactQuote?.let { quote ->
                                Text(LocalStrings.current.getString(R.string.profile_memory_exact_excerpt, quote),
                                    color = colors.accent, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("profile_fact_exact_excerpt"))
                            }
                        }
                    }
                }
                val older = fact.revisions.dropLast(1).takeLast(5).asReversed()
                if (older.isNotEmpty()) {
                    Text(s(R.string.profile_memory_previous_versions), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    older.forEach { revision ->
                        Text(LocalStrings.current.getString(R.string.memory_episode_revision_entry, revision.revision, revision.fact),
                            color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { editingProfileFact = fact; selectedProfileFact = null },
            modifier = Modifier.testTag("profile_fact_edit")) { Text(s(R.string.profile_memory_edit)) } },
        dismissButton = { TextButton(onClick = { pendingDeleteProfileFact = fact; selectedProfileFact = null },
            modifier = Modifier.testTag("profile_fact_delete")) { Text(s(R.string.profile_memory_forget)) } },
    ) }
    if (creatingProfileFact || editingProfileFact != null) ProfileFactEditorDialog(
        fact = editingProfileFact,
        saving = profileFactSaving,
        saveError = profileFactSaveError,
        onDismiss = {
            if (!profileFactSaving) {
                creatingProfileFact = false
                editingProfileFact = null
                profileFactSaveError = false
            }
        },
        onSave = { category, value ->
            profileFactSaveError = false
            profileFactSaving = true
            coroutineScope.launch {
                try {
                    val repository = profileMemoryRepository ?: error("protected_profile_memory_unavailable")
                    val fact = editingProfileFact
                    if (fact == null) repository.createManually(scopeId, category, value)
                    else repository.editManually(scopeId, fact.factId, fact.revision, category, value)
                    refreshVersion++
                    archiveError = false
                    creatingProfileFact = false
                    editingProfileFact = null
                } catch (_: Exception) {
                    archiveError = true
                    profileFactSaveError = true
                } finally { profileFactSaving = false }
            }
        },
    )
    pendingDeleteProfileFact?.let { fact -> AlertDialog(
        onDismissRequest = { pendingDeleteProfileFact = null },
        title = { Text(s(R.string.profile_memory_forget)) },
        text = { Text(s(R.string.profile_memory_forget_confirmation)) },
        confirmButton = { TextButton(onClick = {
            pendingDeleteProfileFact = null
            coroutineScope.launch {
                try {
                    val repository = profileMemoryRepository ?: error("protected_profile_memory_unavailable")
                    check(repository.delete(scopeId, fact.factId, fact.revision))
                    refreshVersion++
                    archiveError = false
                } catch (_: Exception) { archiveError = true }
            }
        }, modifier = Modifier.testTag("profile_fact_delete_confirm")) { Text(s(R.string.profile_memory_forget)) } },
        dismissButton = { TextButton(onClick = { pendingDeleteProfileFact = null }) { Text(s(R.string.task_cancel)) } },
    ) }

    LaunchedEffect(selectedFilterName, query) { listState.scrollToItem(0) }

    val showAll = selectedFilter == MemoryScreenFilter.ALL
    val showConversations = showAll || selectedFilter == MemoryScreenFilter.CONVERSATIONS
    val showProfileFacts = showAll || selectedFilter == MemoryScreenFilter.FACTS
    val showSources = showAll || selectedFilter == MemoryScreenFilter.SOURCES
    val people = domainFacts.filter { it.kindLabel == R.string.people }
    val projects = domainFacts.filter { it.kindLabel == R.string.projects }
    val notes = domainFacts.filter { it.kindLabel == R.string.notes_title }
    val domainGroups = listOf(
        Triple(MemoryScreenFilter.PEOPLE, people, s(R.string.people)),
        Triple(MemoryScreenFilter.PROJECTS, projects, s(R.string.projects)),
        Triple(MemoryScreenFilter.NOTES, notes, s(R.string.memory_saved_sources)),
    )
    val hasAnyResult = matches.isNotEmpty() || domainFacts.isNotEmpty() || profileFacts.isNotEmpty() ||
        conversationEpisodes.isNotEmpty() || conversationTurns.isNotEmpty()
    val showAllEmpty = showAll && query.isBlank() && state.ready && archiveReady && !archiveLoading && !archiveError && !hasAnyResult

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s(R.string.memory_unified_hint), style = MaterialTheme.typography.bodySmall,
                        color = colors.muted, maxLines = 3)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(200) },
                modifier = Modifier.fillMaxWidth().testTag("memory_search"),
                label = { Text(s(R.string.memory_unified_search)) },
                leadingIcon = { Icon(Glyph.Search, null, tint = colors.muted) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { query = "" }, modifier = Modifier.testTag("memory_search_clear")) {
                        Icon(Glyph.Close, s(R.string.close), tint = colors.muted)
                    }
                }) else null,
                singleLine = true,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MemoryScreenFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilterName = filter.name },
                        label = { Text(s(filter.label), maxLines = 1) },
                        modifier = Modifier.testTag("memory_filter_${filter.name.lowercase(Locale.ROOT)}"),
                    )
                }
            }
            if (selectedFilter == MemoryScreenFilter.ALL || showSources) {
                FilterChip(selected = includeArchived, onClick = { includeArchived = !includeArchived },
                    label = { Text(s(R.string.inbox_archived)) }, modifier = Modifier.testTag("memory_include_archived"))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).testTag("memory_content_list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "memory_new_action") {
                OutlinedButton(onClick = capture, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("capture_text")) {
                    Icon(Glyph.Plus, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s(R.string.new_memory))
                }
            }
            item(key = "memory_status") {
                MemoryLoadState(state)
                if (archiveLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("memory_archive_loading"))
                if (archiveError) Text(s(R.string.memory_archive_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("memory_archive_error"))
                if (selectedFilter == MemoryScreenFilter.CONVERSATIONS) {
                    MemoraCard(tint = colors.softAccent, contentPadding = 14.dp) {
                        Text(s(R.string.memory_archive_notice), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
                if (query.isNotBlank() && archiveReady && !archiveLoading && !archiveError && !hasAnyResult) {
                    EmptyState(s(R.string.memory_filter_empty), s(R.string.memory_unified_hint), Glyph.Search,
                        testTag = "memory_no_results")
                }
                if (showAllEmpty) EmptyState(s(R.string.memory_no_saved_sources), s(R.string.memory_unified_hint), Glyph.Memory,
                    testTag = "memory_library_empty")
            }

            if (!showAllEmpty && showProfileFacts && (profileFacts.isNotEmpty() || (query.isBlank() && selectedFilter == MemoryScreenFilter.ALL) ||
                    selectedFilter == MemoryScreenFilter.FACTS)) {
                item(key = "memory_profile_heading") {
                    SectionHeading(s(R.string.profile_memory_title))
                    Text(s(R.string.profile_memory_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    OutlinedButton(onClick = {
                        creatingProfileFact = true
                        editingProfileFact = null
                        profileFactSaveError = false
                    }, modifier = Modifier.fillMaxWidth().testTag("profile_fact_add")) { Text(s(R.string.profile_memory_add)) }
                    if (archiveReady && !archiveLoading && !archiveError && profileFacts.isEmpty())
                        Text(s(R.string.profile_memory_empty), color = colors.muted)
                }
                itemsIndexed(profileFacts, key = { _, fact -> "profile:${fact.factId}" }) { index, fact ->
                    MemoraCard(onClick = { openProfileFact(fact) }, modifier = Modifier.testTag("profile_fact_$index")) {
                        Text(fact.fact, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s(profileFactCategoryLabel(fact.category)), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                            Text(LocalStrings.current.getString(R.string.profile_memory_sources_count, fact.sourceTurnIds.size),
                                style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editingProfileFact = fact }, modifier = Modifier.testTag("profile_fact_edit_$index")) {
                                Text(s(R.string.profile_memory_edit))
                            }
                            TextButton(onClick = { pendingDeleteProfileFact = fact }, modifier = Modifier.testTag("profile_fact_forget_$index")) {
                                Text(s(R.string.profile_memory_forget))
                            }
                        }
                    }
                }
            }

            if (!showAllEmpty && showConversations && (conversationEpisodes.isNotEmpty() || conversationTurns.isNotEmpty() ||
                    (query.isBlank() && selectedFilter == MemoryScreenFilter.ALL) || selectedFilter == MemoryScreenFilter.CONVERSATIONS)) {
                item(key = "memory_conversation_heading") {
                    SectionHeading(s(R.string.memory_conversation_archive))
                    if (archiveReady && !archiveLoading && !archiveError && conversationEpisodes.isEmpty() && conversationTurns.isEmpty())
                        EmptyState(s(R.string.memory_no_conversation_results), s(R.string.memory_unified_hint), Glyph.Chat,
                            testTag = "memory_no_conversations")
                }
                if (conversationEpisodes.isNotEmpty()) item(key = "memory_episode_heading") { SectionHeading(s(R.string.memory_episodes)) }
                itemsIndexed(conversationEpisodes, key = { _, episode -> "episode:${episode.episodeId}" }) { index, episode ->
                    MemoraCard(onClick = { selectedEpisode = episode }, modifier = Modifier.testTag("memory_episode_$index")) {
                        Text(episode.title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        Text(episode.summary.take(360) + if (episode.summary.length > 360) "…" else "", color = colors.muted)
                        Text(LocalStrings.current.getString(R.string.memory_episode_sources_count, episode.sourceTurnIds.size),
                            style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        Text(DateFormatters.memoryDate(episode.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
                items(conversationTurns, key = { "turn:${it.turnId}" }) { turn ->
                    MemoraCard(onClick = { selectedTurn = turn }, modifier = Modifier.testTag("memory_turn_${turn.turnId}")) {
                        Text(turn.matchedSnippet ?: turn.userMessage.take(280), color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                        Text(DateFormatters.memoryDate(turn.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        Text(s(R.string.memory_open_dialogue), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                    }
                }
                if (conversationNextCursor != null) item(key = "memory_conversation_load_more") {
                    OutlinedButton(onClick = ::loadMoreConversationTurns, enabled = !conversationPageLoading,
                        modifier = Modifier.fillMaxWidth().testTag("memory_conversation_load_more")) {
                        Text(if (conversationPageLoading) s(R.string.capture_loading) else s(R.string.memory_conversation_load_more))
                    }
                }
            }

            domainGroups.forEach { (filter, entries, title) ->
                val kind = when (filter) {
                    MemoryScreenFilter.PEOPLE -> DomainKind.PERSON
                    MemoryScreenFilter.PROJECTS -> DomainKind.PROJECT
                    else -> DomainKind.NOTE
                }
                if (!showAllEmpty && (showAll || selectedFilter == filter) && (entries.isNotEmpty() || selectedFilter == filter)) {
                    item(key = "memory_domain_heading:${filter.name}") {
                        SectionHeading(title)
                        if (archiveReady && !archiveLoading && !archiveError && entries.isEmpty())
                            EmptyState(s(R.string.memory_filter_empty), s(R.string.memory_unified_hint), Glyph.Memory,
                                testTag = "memory_empty_${filter.name.lowercase(Locale.ROOT)}")
                    }
                    items(entries, key = { "domain:${it.id}" }) { fact ->
                        MemoraCard(onClick = {
                            if (fact.kind == DomainKind.PERSON) {
                                AppRoute.target(AppTargetKind.PERSON, fact.id)?.let(onOpenTarget) ?: run { selectedFact = fact }
                            } else selectedFact = fact
                        }, modifier = Modifier.testTag("memory_domain_${fact.id}")) {
                            Text(fact.title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                            fact.body?.takeIf(String::isNotBlank)?.let {
                                Text(it.take(260) + if (it.length > 260) "…" else "", color = colors.muted)
                            }
                            Text(s(fact.kindLabel), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                    }
                    if (domainNextOffsets[kind] != null) item(key = "memory_domain_more:${kind.name}") {
                        OutlinedButton(onClick = { loadMoreDomainObjects(kind) },
                            enabled = kind !in domainPageLoadingKinds,
                            modifier = Modifier.fillMaxWidth().testTag("memory_domain_load_more_${kind.name.lowercase(Locale.ROOT)}")) {
                            Text(s(if (kind in domainPageLoadingKinds) R.string.capture_loading else R.string.memory_domain_load_more))
                        }
                    }
                }
            }

            if (!showAllEmpty && showSources && (matches.isNotEmpty() || (query.isBlank() && selectedFilter == MemoryScreenFilter.ALL) ||
                    selectedFilter == MemoryScreenFilter.SOURCES)) {
                item(key = "memory_sources_heading") {
                    SectionHeading(s(R.string.recent))
                    if (state.ready && matches.isEmpty()) Text(s(R.string.memory_no_saved_sources), color = colors.muted)
                }
                items(matches, key = { "capture:${it.entry.source.id}" }) { match ->
                    StoredMemoryCard(match.entry) { detail(match.entry.source.id) }
                }
            }

            item(key = "memory_management") {
                SectionHeading(s(R.string.settings))
                OutlinedButton(onClick = { showRetention = true }, enabled = conversationRepository != null,
                    modifier = Modifier.fillMaxWidth().testTag("memory_retention")) {
                    Text(LocalStrings.current.getString(R.string.memory_retention_current, s(memoryRetentionLabel(retentionDays))))
                }
                if (localProfile != null) OutlinedButton(onClick = { showLocalProfileEditor = true },
                    modifier = Modifier.fillMaxWidth().testTag("profile_preferences_secondary")) {
                    Text(s(R.string.profile_preferences_title))
                }
                OutlinedButton(onClick = manage, modifier = Modifier.fillMaxWidth().testTag("memory_manage")) {
                    Text(s(R.string.working_manage))
                }
            }
        }
    }
}

private enum class MemoryScreenFilter(val label: Int) {
    ALL(R.string.all), CONVERSATIONS(R.string.memory_conversation_archive), FACTS(R.string.memory_filter_facts),
    PEOPLE(R.string.people), PROJECTS(R.string.projects), NOTES(R.string.notes_title), SOURCES(R.string.memory_filter_sources),
}

@Composable
private fun LocalProfileEditorDialog(
    profile: LocalProfileRow,
    saving: Boolean,
    saveError: Boolean,
    onDismiss: () -> Unit,
    onSave: (LocalProfileInput) -> Unit,
) {
    var displayName by remember(profile.localProfileId, profile.updatedAtMillis) { mutableStateOf(profile.displayName.orEmpty()) }
    var language by remember(profile.localProfileId, profile.updatedAtMillis) { mutableStateOf(profile.language) }
    var tone by remember(profile.localProfileId, profile.updatedAtMillis) { mutableStateOf(profile.toneStyle) }
    var style by remember(profile.localProfileId, profile.updatedAtMillis) { mutableStateOf(profile.responseStyle) }
    var zone by remember(profile.localProfileId, profile.updatedAtMillis) { mutableStateOf(profile.timeZoneId) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s(R.string.profile_preferences_title)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(displayName, { displayName = it.take(60) }, label = { Text(s(R.string.onboarding_name)) },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("profile_preferences_name"))
                Text(s(R.string.onboarding_language), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("az" to "Azərbaycanca", "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                        FilterChip(language == code, { language = code }, enabled = !saving, label = { Text(label) })
                    }
                }
                Text(s(R.string.onboarding_tone), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("friendly" to R.string.onboarding_tone_friendly, "direct" to R.string.onboarding_tone_direct).forEach { (value, label) ->
                        FilterChip(tone == value, { tone = value }, enabled = !saving, label = { Text(s(label)) })
                    }
                }
                Text(s(R.string.onboarding_style), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("concise" to R.string.onboarding_style_concise, "balanced" to R.string.onboarding_style_balanced,
                        "detailed" to R.string.onboarding_style_detailed).forEach { (value, label) ->
                        FilterChip(style == value, { style = value }, enabled = !saving, label = { Text(s(label)) })
                    }
                }
                Text(s(R.string.onboarding_timezone), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Asia/Baku", "Europe/Moscow", "UTC").forEach { value ->
                        FilterChip(zone == value, { zone = value }, enabled = !saving, label = { Text(value) })
                    }
                }
                if (saveError || invalid) Text(s(R.string.onboarding_save_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("profile_preferences_error"))
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val input = runCatching { LocalProfileInput(displayName.trim().takeIf(String::isNotEmpty), language, tone, style, zone) }.getOrNull()
            if (input == null) invalid = true else { invalid = false; onSave(input) }
        }, modifier = Modifier.testTag("profile_preferences_save")) {
            Text(if (saving) s(R.string.onboarding_saving) else s(R.string.profile_memory_save))
        } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(s(R.string.task_cancel)) } },
    )
}

@Composable
private fun ProfileFactEditorDialog(
    fact: ProfileFactEvidence?,
    saving: Boolean,
    saveError: Boolean,
    onDismiss: () -> Unit,
    onSave: (category: String, fact: String) -> Unit,
) {
    var category by remember(fact?.factId, fact?.revision) { mutableStateOf(fact?.category ?: "other") }
    var value by remember(fact?.factId, fact?.revision) { mutableStateOf(fact?.fact.orEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(s(if (fact == null) R.string.profile_memory_add else R.string.profile_memory_edit)) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value, { value = it.take(600) }, label = { Text(s(R.string.profile_memory_fact)) },
                    enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("profile_fact_editor"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("identity", "preference", "goal", "context", "other").forEach { item ->
                        FilterChip(category == item, { category = item }, enabled = !saving,
                            label = { Text(s(profileFactCategoryLabel(item))) },
                            modifier = Modifier.testTag("profile_fact_category_$item"))
                    }
                }
                if (saveError) Text(s(R.string.profile_memory_save_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("profile_fact_save_error"))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(category, value.trim()) },
            enabled = !saving && value.isNotBlank(), modifier = Modifier.testTag("profile_fact_editor_save")) {
            Text(s(if (saving) R.string.onboarding_saving else R.string.profile_memory_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(s(R.string.task_cancel)) } },
    )
}

private data class MemoryDomainFact(val id: String, val title: String, val body: String?, val kind: DomainKind,
    val provenanceLabel: Int? = null) {
    val kindLabel: Int get() = domainKindResource(kind.name)
    constructor(row: DomainObjectRow) : this(row.objectId, row.title, row.body, DomainKind.valueOf(row.kind), R.string.memory_source_saved)
    constructor(hit: DomainHit) : this(hit.objectId, hit.title, hit.evidenceText, hit.kind, R.string.memory_source_linked)
}

private fun domainKindResource(kind: String): Int = when (kind) {
    DomainKind.PERSON.name -> R.string.people
    DomainKind.PROJECT.name -> R.string.projects
    else -> R.string.notes_title
}

private fun profileFactCategoryLabel(category: String): Int = when (category) {
    "identity" -> R.string.profile_memory_category_identity
    "preference" -> R.string.profile_memory_category_preference
    "goal" -> R.string.profile_memory_category_goal
    "context" -> R.string.profile_memory_category_context
    else -> R.string.profile_memory_category_other
}

private fun memoryRetentionLabel(days: Int): Int = when (days) {
    365 -> R.string.memory_retention_365
    90 -> R.string.memory_retention_90
    30 -> R.string.memory_retention_30
    else -> R.string.memory_retention_forever
}

private object DateFormatters {
    fun memoryDate(millis: Long): String = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault()).format(Date(millis))
}

@Composable
fun StoredMemoryDetail(entry: CaptureEntry, close: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s(R.string.source), style = MaterialTheme.typography.titleLarge)
        Text(entry.source.revisions.last().text, modifier = Modifier.testTag("stored_source_text"))
        Text(s(R.string.capture_history), style = MaterialTheme.typography.titleMedium)
        entry.source.revisions.asReversed().forEach {
            Text(LocalStrings.current.getString(R.string.capture_revision_number, it.number))
            Text(it.text, modifier = Modifier.testTag("stored_revision_${it.number}"))
            Text(it.recordedAt.toString(), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(s(R.string.close)) }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
fun UnavailableModuleScreen(title: String) {
    ScreenColumn {
        ScreenHeading(title, s(R.string.working_module_hint))
        EmptyState(s(R.string.working_no_data), s(R.string.working_module_empty))
    }
}

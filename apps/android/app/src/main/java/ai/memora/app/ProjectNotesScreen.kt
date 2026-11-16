package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.LinkedDomainObject
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROJECT_NOTE_PAGE_SIZE = 30
private const val PROJECT_NOTE_SEARCH_LIMIT = 100

/** Read-only project/note library over the existing scoped domain repository. */
@Composable
internal fun ProjectNotesScreen(kind: DomainKind, onOpenTarget: (AppRoute) -> Unit) {
    require(kind == DomainKind.PROJECT || kind == DomainKind.NOTE)
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var query by rememberSaveable(kind.name) { mutableStateOf("") }
    var records by remember(kind) { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
    var total by remember(kind) { mutableIntStateOf(0) }
    var nextOffset by remember(kind) { mutableIntStateOf(0) }
    var hasMore by remember(kind) { mutableStateOf(false) }
    var loading by remember(kind) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember(kind) { mutableStateOf(false) }
    var refreshVersion by rememberSaveable(kind.name) { mutableIntStateOf(0) }
    var requestGeneration by remember(kind) { mutableIntStateOf(0) }

    LaunchedEffect(scopeId, kind, query, refreshVersion) {
        val generation = ++requestGeneration
        loading = true
        loadingMore = false
        failed = false
        records = emptyList()
        nextOffset = 0
        hasMore = false
        try {
            val request = query.trim()
            if (request.isEmpty()) {
                val page = withContext(Dispatchers.IO) {
                    repository.domainObjectsPage(scopeId, kind, PROJECT_NOTE_PAGE_SIZE + 1, 0) to
                        database.protectedStoreDao().countActiveDomainObjects(scopeId, kind.name)
                }
                if (generation != requestGeneration) return@LaunchedEffect
                records = page.first.take(PROJECT_NOTE_PAGE_SIZE)
                total = page.second
                hasMore = page.first.size > PROJECT_NOTE_PAGE_SIZE
                nextOffset = records.size
            } else {
                val matches = withContext(Dispatchers.IO) {
                    repository.search(scopeId, request, setOf(kind), PROJECT_NOTE_SEARCH_LIMIT)
                        .mapNotNull { hit -> repository.domainObject(scopeId, hit.objectId) }
                        .filter { it.kind == kind.name && it.deletedAt == null }
                }
                if (generation != requestGeneration) return@LaunchedEffect
                records = matches
                total = matches.size
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == requestGeneration) failed = true
        } finally {
            if (generation == requestGeneration) loading = false
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore || query.isNotBlank()) return
        val requestedOffset = nextOffset
        val generation = requestGeneration
        coroutineScope.launch {
            loadingMore = true
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.domainObjectsPage(scopeId, kind, PROJECT_NOTE_PAGE_SIZE + 1, requestedOffset)
                }
                if (generation != requestGeneration || requestedOffset != nextOffset) return@launch
                records = (records + page.take(PROJECT_NOTE_PAGE_SIZE)).distinctBy { it.objectId }
                hasMore = page.size > PROJECT_NOTE_PAGE_SIZE
                nextOffset = requestedOffset + page.size.coerceAtMost(PROJECT_NOTE_PAGE_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == requestGeneration) failed = true
            } finally {
                if (generation == requestGeneration) loadingMore = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            s(if (kind == DomainKind.PROJECT) R.string.projects_hint else R.string.notes_hint),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("project_notes_subtitle"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(200) },
                modifier = Modifier.weight(1f).testTag("project_notes_search"),
                label = { Text(s(R.string.saved_objects_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            TextButton(
                onClick = { focusManager.clearFocus(); refreshVersion++ },
                enabled = !loading,
                modifier = Modifier.testTag("project_notes_refresh"),
            ) { Text(s(R.string.projects_notes_refresh)) }
        }
        if (query.isNotBlank()) {
            Text(s(R.string.projects_notes_search_limit), color = colors.muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project_notes_search_limit"))
        } else if (!loading && !failed) {
            Text(LocalStrings.current.getString(R.string.projects_notes_count, records.size, total), color = colors.muted,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("project_notes_count"))
        }
        when {
            loading -> Text(s(R.string.ai_loading), color = colors.muted, modifier = Modifier.testTag("project_notes_loading"))
            failed && records.isEmpty() -> {
                EmptyState(s(R.string.working_no_data), s(R.string.saved_objects_load_error))
                OutlinedButton(onClick = { refreshVersion++ }, modifier = Modifier.testTag("project_notes_retry")) {
                    Text(s(R.string.capture_retry))
                }
            }
            records.isEmpty() -> EmptyState(
                s(R.string.working_no_data),
                s(if (query.isBlank()) R.string.saved_objects_empty else R.string.saved_objects_no_matches),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("project_notes_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (failed) item(key = "load_error") {
                    Text(s(R.string.saved_objects_load_error), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("project_notes_load_error"))
                    OutlinedButton(onClick = { refreshVersion++ }, modifier = Modifier.testTag("project_notes_retry")) {
                        Text(s(R.string.capture_retry))
                    }
                }
                items(records, key = DomainObjectRow::objectId) { record ->
                    ProjectNoteCard(record) {
                        val targetKind = if (kind == DomainKind.PROJECT) AppTargetKind.PROJECT else AppTargetKind.NOTE
                        AppRoute.target(targetKind, record.objectId)?.let(onOpenTarget)
                    }
                }
                if (query.isBlank() && hasMore) item(key = "load_more") {
                    OutlinedButton(
                        onClick = ::loadMore,
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth().testTag("project_notes_load_more"),
                    ) {
                        Text(s(if (loadingMore) R.string.ai_loading else R.string.projects_notes_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectNoteCard(record: DomainObjectRow, onClick: () -> Unit) {
    MemoraCard(
        onClick = onClick,
        modifier = Modifier.testTag("project_note_${record.objectId}"),
        contentPadding = 16.dp,
    ) {
        Text(record.title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
        record.body?.takeIf(String::isNotBlank)?.let { body ->
            Text(body.take(240) + if (body.length > 240) "…" else "", color = colors.muted,
                style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (record.status == "archived") Text(s(R.string.saved_objects_archived), color = colors.muted,
            style = MaterialTheme.typography.labelMedium)
    }
}

private sealed interface ProjectNoteDetailState {
    data object Loading : ProjectNoteDetailState
    data class Found(val record: DomainObjectRow, val related: List<LinkedDomainObject>) : ProjectNoteDetailState
    data object Missing : ProjectNoteDetailState
    data object Unavailable : ProjectNoteDetailState
}

/** Exact-ID project/note details and links, read only and constrained to the current conversation scope. */
@Composable
internal fun ProjectNoteTargetDetailScreen(route: AppRoute, onOpenTarget: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    var state by remember(route, scopeId) { mutableStateOf<ProjectNoteDetailState>(ProjectNoteDetailState.Loading) }
    val expectedKind = when (route.targetKind) {
        AppTargetKind.PROJECT -> DomainKind.PROJECT
        AppTargetKind.NOTE -> DomainKind.NOTE
        else -> null
    }

    LaunchedEffect(route.targetId, expectedKind, scopeId, database) {
        state = ProjectNoteDetailState.Loading
        state = withContext(Dispatchers.IO) {
            try {
                val id = route.targetId ?: return@withContext ProjectNoteDetailState.Missing
                val kind = expectedKind ?: return@withContext ProjectNoteDetailState.Missing
                val repository = ProtectedDomainRepository(database)
                val record = repository.domainObject(scopeId, id)?.takeIf {
                    it.kind == kind.name && it.deletedAt == null
                } ?: return@withContext ProjectNoteDetailState.Missing
                ProjectNoteDetailState.Found(record, repository.linkedObjects(scopeId, id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProjectNoteDetailState.Unavailable
            }
        }
    }

    when (val current = state) {
        ProjectNoteDetailState.Loading -> Column(Modifier.fillMaxSize().padding(20.dp).testTag("project_note_detail_loading")) {
            Text(s(R.string.ai_loading), color = colors.muted)
        }
        ProjectNoteDetailState.Missing -> Column(Modifier.fillMaxSize().padding(20.dp).testTag("project_note_detail_missing")) {
            EmptyState(s(R.string.action_related_missing), s(R.string.action_related_missing_body))
        }
        ProjectNoteDetailState.Unavailable -> Column(Modifier.fillMaxSize().padding(20.dp).testTag("project_note_detail_unavailable")) {
            EmptyState(s(R.string.action_related_unavailable), s(R.string.action_related_unavailable_body))
        }
        is ProjectNoteDetailState.Found -> androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()).testTag("project_note_detail_${current.record.objectId}"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val kindLabel = s(if (current.record.kind == DomainKind.PROJECT.name) R.string.projects else R.string.notes_title)
            ScreenHeading(current.record.title, kindLabel)
            MemoraCard(modifier = Modifier.testTag("project_note_detail_content"), contentPadding = 18.dp) {
                Text(s(if (current.record.status == "archived") R.string.saved_objects_archived else R.string.projects_notes_active),
                    color = colors.accent, style = MaterialTheme.typography.labelLarge)
                Text(current.record.body?.takeIf(String::isNotBlank)?.take(20_000) ?: s(R.string.saved_objects_no_details),
                    color = colors.ink, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("project_note_detail_body"))
                if (current.record.body != null && current.record.body.length > 20_000) {
                    Text(s(R.string.saved_objects_details_truncated), color = colors.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(s(R.string.projects_notes_related), color = colors.ink, style = MaterialTheme.typography.titleMedium)
            if (current.related.isEmpty()) {
                EmptyState(s(R.string.projects_notes_related_empty_title), s(R.string.projects_notes_related_empty_body))
            } else {
                Column(
                    modifier = Modifier.testTag("project_note_related_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    current.related.forEach { linked ->
                        val linkedRoute = linkedObjectRoute(linked.objectRecord)
                        ProjectNoteCard(linked.objectRecord) {
                            linkedRoute?.let(onOpenTarget)
                        }
                    }
                }
            }
        }
    }
}

private fun linkedObjectRoute(record: DomainObjectRow): AppRoute? = when (record.kind) {
    DomainKind.PERSON.name -> AppRoute.target(AppTargetKind.PERSON, record.objectId)
    DomainKind.PROJECT.name -> AppRoute.target(AppTargetKind.PROJECT, record.objectId)
    DomainKind.NOTE.name -> AppRoute.target(AppTargetKind.NOTE, record.objectId)
    else -> null
}

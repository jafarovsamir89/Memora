package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.CaptureEntry
import ai.memora.app.data.CaptureSessionState
import ai.memora.app.domain.LocalMemoryIndex
import ai.memora.app.ui.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WorkingHomeScreen(language: String, state: CaptureSessionState, navigate: (String) -> Unit,
    capture: (Boolean) -> Unit, detail: (String) -> Unit) {
    ScreenColumn {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag(language))),
            style = MaterialTheme.typography.labelSmall, color = colors.muted)
        ScreenHeading(s(R.string.home_title), s(R.string.working_home_hint))
        MemoraCard(tint = colors.hero) {
            Text(s(R.string.home_hero_title), style = MaterialTheme.typography.titleLarge)
            Text(s(R.string.working_capture_hint))
            Button(onClick = { capture(true) }, modifier = Modifier.fillMaxWidth().testTag("capture_voice")) { Text(s(R.string.tell)) }
            OutlinedButton(onClick = { capture(false) }, modifier = Modifier.fillMaxWidth().testTag("capture_text")) { Text(s(R.string.write)) }
        }
        OutlinedButton(onClick = { navigate("chat") }, modifier = Modifier.fillMaxWidth().testTag("open_chat")) { Text(s(R.string.ask_memory)) }
        OutlinedButton(onClick = { navigate("tasks") }, modifier = Modifier.fillMaxWidth()) { Text(s(R.string.tasks_title)) }
        SectionHeading(s(R.string.recent), { navigate("memory") })
        MemoryLoadState(state)
        if (state.ready && state.captures.none { !it.archived }) EmptyState(s(R.string.working_memory_empty), s(R.string.working_capture_hint))
        state.captures.asReversed().filter { !it.archived }.take(3).forEach { StoredMemoryCard(it) { detail(it.source.id) } }
    }
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
fun WorkingMemoryScreen(state: CaptureSessionState, detail: (String) -> Unit, capture: () -> Unit, manage: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    val matches = LocalMemoryIndex.search(state.captures, query, includeArchived)
    ScreenColumn {
        ScreenHeading(s(R.string.memory_title), s(R.string.working_memory_hint))
        OutlinedTextField(query, { query = it.take(200) }, modifier = Modifier.fillMaxWidth().testTag("memory_search"),
            label = { Text(s(R.string.search)) }, singleLine = true)
        FilterChip(selected = includeArchived, onClick = { includeArchived = !includeArchived },
            label = { Text(s(R.string.inbox_archived)) }, modifier = Modifier.testTag("memory_include_archived"))
        MemoryLoadState(state)
        if (state.ready && matches.isEmpty()) EmptyState(s(R.string.working_memory_empty), s(R.string.working_no_matches))
        matches.forEach { match -> StoredMemoryCard(match.entry) { detail(match.entry.source.id) } }
        OutlinedButton(onClick = manage, modifier = Modifier.fillMaxWidth().testTag("memory_manage")) { Text(s(R.string.working_manage)) }
        Button(onClick = capture, modifier = Modifier.fillMaxWidth()) { Text(s(R.string.new_memory)) }
    }
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

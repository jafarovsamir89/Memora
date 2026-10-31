package ai.memora.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.ui.*
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.CaptureEntry
import ai.memora.app.data.CaptureOrigin
import ai.memora.app.data.CaptureArchiveSelection
import ai.memora.app.data.CaptureRevisionRequest
import ai.memora.app.domain.TextSourceRules
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun HomeScreen(language: String, completed: List<String>, onToggle: (String) -> Unit,
    navigate: (String) -> Unit, capture: (Boolean) -> Unit, detail: (String) -> Unit) {
    ScreenColumn {
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag(language))).uppercase(Locale.forLanguageTag(language)),
            style = MaterialTheme.typography.labelSmall, color = colors.muted)
        Text(s(R.string.home_title), style = MaterialTheme.typography.headlineLarge, color = colors.ink)
        MemoraCard(tint = colors.hero) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s(R.string.home_hero_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.onHero)
                Icon(Glyph.Spark, null, Modifier.size(28.dp), tint = colors.accent)
            }
            Text(s(R.string.home_hero_body), style = MaterialTheme.typography.bodyMedium, color = colors.onHero.copy(alpha = .8f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ capture(true) }, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("capture_voice"),
                    contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Icon(Glyph.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.tell))
                }
                TextButton({ capture(false) }, modifier = Modifier.heightIn(min = 50.dp).testTag("capture_text")) {
                    Icon(Glyph.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(s(R.string.write))
                }
            }
        }
        MemoraCard(onClick = { navigate("chat") }, modifier = Modifier.testTag("open_chat")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Glyph.Search, null, Modifier.size(22.dp), tint = colors.accent)
                Column(Modifier.weight(1f)) {
                    Text(s(R.string.ask_memory), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(s(R.string.ask_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                Icon(Glyph.Arrow, null, Modifier.size(18.dp), tint = colors.muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeading(s(R.string.today_section), { navigate("tasks") })
            exampleTasks.take(2).forEach { TaskRow(it, it.id in completed, { onToggle(it.id) }) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading(s(R.string.recent), { navigate("memory") })
            exampleMemories.take(2).forEach { MemoryCard(it) { detail(it.id) } }
        }
        Text(s(R.string.sample_only), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
fun MemoryCard(item: ExampleMemory, onClick: () -> Unit) {
    MemoraCard(onClick = onClick, modifier = Modifier.testTag("memory_${item.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBubble(item.icon, item.tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(item.category), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                Text(s(item.title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
            Icon(Glyph.Chevron, null, Modifier.size(16.dp), tint = colors.muted)
        }
        Text(s(item.body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        Text(s(R.string.time_recent), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
fun Filters(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, title) ->
            FilterChip(selected == id, { onSelect(id) }, enabled = enabled, label = { Text(title) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("filter_$id"),
                shape = RoundedCornerShape(14.dp), border = null,
                colors = FilterChipDefaults.filterChipColors(containerColor = colors.card,
                    selectedContainerColor = colors.softAccent, selectedLabelColor = colors.onSoftAccent, labelColor = colors.muted))
        }
    }
}

@Composable
fun MemoryScreen(detail: (String) -> Unit, capture: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("all") }
    val res = LocalStrings.current
    val filtered = exampleMemories.filter {
        (selected == "all" || it.category.toString() == selected) &&
            (res.getString(it.title) + " " + res.getString(it.body)).contains(query.trim(), ignoreCase = true)
    }
    ScreenColumn {
        ScreenHeading(s(R.string.memory_title), s(R.string.memory_subtitle))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("memory_search"),
            label = { Text(s(R.string.search)) }, leadingIcon = { Icon(Glyph.Search, null, Modifier.size(20.dp)) },
            shape = RoundedCornerShape(18.dp), singleLine = true,
            trailingIcon = if (query.isNotEmpty()) ({ IconButton({ query = "" }) { Icon(Glyph.Close, s(R.string.close)) } }) else null)
        Filters(listOf("all" to s(R.string.all), R.string.notes.toString() to s(R.string.notes),
            R.string.promises.toString() to s(R.string.promises), R.string.money.toString() to s(R.string.money),
            R.string.ideas.toString() to s(R.string.ideas)), selected, { selected = it })
        SectionHeading(s(R.string.memory_count), badge = s(R.string.examples))
        if (filtered.isEmpty()) EmptyState(s(R.string.empty_title), s(R.string.empty_body))
        filtered.forEach { MemoryCard(it) { detail(it.id) } }
        OutlinedButton(capture, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Glyph.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.new_memory))
        }
    }
}

@Composable
fun TaskRow(task: ExampleTask, done: Boolean, onToggle: () -> Unit) {
    Surface(color = colors.card, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag("task_${task.id}").padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(done, onCheckedChange = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(s(task.title), style = MaterialTheme.typography.titleMedium, color = if (done) colors.muted else colors.ink,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None)
                Text(s(task.group), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Text(task.time, style = MaterialTheme.typography.labelLarge, color = colors.muted)
        }
    }
}

@Composable
fun TasksScreen(language: String, completed: List<String>, toggle: (String) -> Unit, capture: () -> Unit, taskStore: EncryptedTaskStore) {
    var filter by rememberSaveable { mutableStateOf("today") }
    val today = LocalDate.now()
    val shown = exampleTasks.filter { when (filter) {
        "done" -> it.id in completed
        "upcoming" -> it.upcoming && it.id !in completed
        else -> !it.upcoming && it.id !in completed
    } }
    ScreenColumn {
        ScreenHeading(s(R.string.tasks_title), s(R.string.tasks_subtitle))
        TaskPrototype(taskStore)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (-2L..4L).forEach { shift ->
                val day = today.plusDays(shift)
                Column(Modifier.weight(1f).background(if (shift == 0L) colors.accent else Color.Transparent, RoundedCornerShape(18.dp))
                    .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val ink = if (shift == 0L) MaterialTheme.colorScheme.onPrimary else colors.muted
                    Text(day.format(DateTimeFormatter.ofPattern("EE", Locale.forLanguageTag(language))), style = MaterialTheme.typography.bodySmall, color = ink)
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (shift == 0L) ink else colors.ink)
                }
            }
        }
        Filters(listOf("today" to s(R.string.nav_home), "upcoming" to s(R.string.upcoming), "done" to s(R.string.completed)), filter, { filter = it })
        SectionHeading(s(R.string.today_section), badge = s(R.string.examples))
        if (shown.isEmpty()) EmptyState(s(R.string.tasks_done), s(R.string.sample_only), Glyph.Check)
        shown.forEach { TaskRow(it, it.id in completed) { toggle(it.id) } }
        OutlinedButton(capture, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Glyph.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.new_memory))
        }
    }
}

@Composable
fun MoreScreen(navigate: (String) -> Unit, about: () -> Unit) {
    ScreenColumn {
        ScreenHeading(s(R.string.more_title), s(R.string.more_subtitle))
        MemoraCard {
            MenuRow(Glyph.Inbox, s(R.string.inbox), s(R.string.inbox_hint)) { navigate("inbox") }
            HorizontalDivider(color = colors.line)
            MenuRow(Glyph.Wallet, s(R.string.finance), s(R.string.finance_hint), 1) { navigate("finance") }
            HorizontalDivider(color = colors.line)
            MenuRow(Glyph.Person, s(R.string.people), s(R.string.people_hint)) { navigate("people") }
            HorizontalDivider(color = colors.line)
            MenuRow(Glyph.Folder, s(R.string.projects), s(R.string.projects_hint), 2) { navigate("projects") }
        }
        MemoraCard { MenuRow(Glyph.Settings, s(R.string.settings), s(R.string.settings_hint)) { navigate("settings") } }
        MemoraCard(tint = colors.softGreen, onClick = about) {
            Icon(Glyph.Shield, null, tint = colors.green)
            Text(s(R.string.private_title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(s(R.string.private_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
    }
}

@Composable
fun FinanceScreen(detail: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("overview") }
    ScreenColumn {
        ScreenHeading(s(R.string.finance_title), s(R.string.finance_subtitle))
        Filters(listOf("overview" to s(R.string.overview), "debts" to s(R.string.debts)), selected, { selected = it })
        if (selected == "overview") {
            MemoraCard(tint = colors.hero) {
                Text(s(R.string.period), style = MaterialTheme.typography.labelSmall, color = colors.onHero)
                Text("230 ₼", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp), color = colors.onHero)
                Text(s(R.string.balance), style = MaterialTheme.typography.bodySmall, color = colors.onHero)
                HorizontalDivider(color = colors.onHero.copy(alpha = .12f))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(s(R.string.income), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                        Text("+350 ₼", color = colors.green, style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s(R.string.expense), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                        Text("−120 ₼", color = colors.onHero, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Text(s(R.string.finance_coverage), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            SectionHeading(s(R.string.activity), badge = s(R.string.examples))
            MemoraCard {
                TransactionRow(s(R.string.income_example), "+350 ₼", true)
                HorizontalDivider(color = colors.line)
                TransactionRow(s(R.string.expense_example), "−120 ₼", false)
            }
        }
        SectionHeading(s(R.string.owed_to_you), badge = s(R.string.examples))
        MemoraCard(onClick = detail) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBubble(Glyph.Person)
                Text(s(R.string.elchin), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
                Text("300 ₼", style = MaterialTheme.typography.titleLarge, color = colors.ink)
            }
            Text(s(R.string.remaining), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { .4f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = colors.accent, trackColor = colors.softAccent)
            Text(s(R.string.debt_progress), color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TransactionRow(title: String, amount: String, income: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(if (income) Glyph.Plus else Glyph.Wallet, if (income) 1 else 2)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        Text(amount, style = MaterialTheme.typography.titleMedium, color = if (income) colors.green else colors.ink)
    }
}

@Composable
fun PeopleScreen(detail: (String) -> Unit) {
    ScreenColumn {
        ScreenHeading(s(R.string.people), s(R.string.people_example))
        MemoraCard {
            MenuRow(Glyph.Person, s(R.string.elchin), s(R.string.elchin_hint)) { detail("payment") }
            HorizontalDivider(color = colors.line)
            MenuRow(Glyph.Person, s(R.string.ali), s(R.string.ali_hint), 1) { detail("meeting") }
        }
    }
}

@Composable
fun ProjectsScreen(detail: (String) -> Unit) {
    ScreenColumn {
        ScreenHeading(s(R.string.projects), s(R.string.projects_hint))
        MemoraCard { MenuRow(Glyph.Folder, s(R.string.bmw_project), s(R.string.bmw_project_hint)) { detail("sensor") } }
        MemoraCard { MenuRow(Glyph.Folder, s(R.string.workshop_project), s(R.string.workshop_project_hint), 2) { detail("idea") } }
    }
}

@Composable
fun InboxScreen(drafts: List<CaptureEntry>, loading: Boolean, error: Boolean, managementError: Boolean,
    canManage: Boolean, retry: () -> Unit, capture: () -> Unit, export: () -> Unit,
    importFile: () -> Unit, importIssue: String?, importDone: Boolean, importStale: Boolean,
    archive: (List<CaptureArchiveSelection>, Boolean) -> Unit, managementBusy: Boolean,
    revise: (CaptureRevisionRequest) -> Unit, delete: (CaptureEntry) -> Unit) {
    var deleting by remember { mutableStateOf<CaptureEntry?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPrevious by rememberSaveable { mutableStateOf("") }
    var editingText by rememberSaveable { mutableStateOf("") }
    var pendingRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTime by rememberSaveable { mutableStateOf<String?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("active") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(drafts, pendingRevision) {
        val id = pendingRevision
        if (id != null && drafts.any { entry -> entry.source.id == editingId && entry.source.revisions.any { it.id == id } }) {
            editingId = null; pendingRevision = null; pendingTime = null
        }
    }
    LaunchedEffect(search, filter, drafts) { selected = emptySet() }
    val visible = drafts.asReversed().filter { entry ->
        (filter == "all" || entry.archived == (filter == "archived")) &&
            entry.source.revisions.last().text.contains(search.trim(), ignoreCase = true)
    }
    val selectedVisible = visible.filter { it.source.id in selected }
    ScreenColumn {
        ScreenHeading(s(R.string.inbox), s(R.string.inbox_subtitle))
        SectionHeading(s(R.string.inbox_session))
        OutlinedTextField(value = search, onValueChange = { search = it.take(200) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("inbox_search"), label = { Text(s(R.string.inbox_search)) })
        Filters(listOf("active" to s(R.string.inbox_active), "archived" to s(R.string.inbox_archived), "all" to s(R.string.inbox_all)), filter,
            { filter = it })
        if (loading) Text(s(R.string.capture_loading), modifier = Modifier.testTag("inbox_loading"))
        else if (error) {
            Text(s(R.string.capture_storage_error), modifier = Modifier.testTag("inbox_error"), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = retry, modifier = Modifier.testTag("inbox_retry")) { Text(s(R.string.capture_retry)) }
        } else if (visible.isEmpty()) EmptyState(s(if (drafts.isEmpty()) R.string.empty_drafts else R.string.inbox_no_matches_title),
            s(if (drafts.isEmpty()) R.string.empty_drafts_body else R.string.inbox_no_results), Glyph.Inbox)
        if (managementError) Text(s(R.string.capture_management_error), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("capture_management_error"))
        if (importIssue != null) Text(importIssue, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capture_import_error"))
        if (importStale) Text(s(R.string.capture_import_changed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capture_import_stale"))
        if (importDone) Text(s(R.string.capture_import_success), modifier = Modifier.testTag("capture_import_success"))
        if (selectedVisible.isNotEmpty()) {
            Text(LocalStrings.current.getString(R.string.inbox_selected, selectedVisible.size), modifier = Modifier.testTag("inbox_selected"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { archive(selectedVisible.map { CaptureArchiveSelection(it.source.id, it.source.revisions.last().id, it.organizationVersion) }, true) },
                    enabled = canManage, modifier = Modifier.weight(1f).testTag("inbox_archive_selected")) { Text(s(R.string.inbox_archive_action)) }
                OutlinedButton(onClick = { archive(selectedVisible.map { CaptureArchiveSelection(it.source.id, it.source.revisions.last().id, it.organizationVersion) }, false) },
                    enabled = canManage, modifier = Modifier.weight(1f).testTag("inbox_restore_selected")) { Text(s(R.string.inbox_restore_action)) }
            }
        }
        visible.forEachIndexed { index, draft ->
            val sourceText = draft.source.revisions.last().text
            val prefixLength = sourceText.offsetByCodePoints(0, minOf(sourceText.codePointCount(0, sourceText.length), 80))
            val selectionDescription = s(R.string.inbox_select) + ": " + sourceText.substring(0, prefixLength)
            MemoraCard(Modifier.testTag("draft_$index")) {
                Pill(s(if (draft.origin == CaptureOrigin.IMPORTED) R.string.capture_imported else R.string.draft), 2)
                if (draft.archived) Text(s(R.string.inbox_archived), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                Text(sourceText, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(s(R.string.draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.source.id in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + draft.source.id else selected - draft.source.id
                    }, enabled = canManage, modifier = Modifier.testTag("capture_select_$index").semantics { contentDescription = selectionDescription })
                    Text(s(R.string.inbox_select))
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        editingId = draft.source.id; editingPrevious = draft.source.revisions.last().id
                        editingText = sourceText; pendingRevision = null; pendingTime = null
                    }, enabled = canManage, modifier = Modifier.weight(1f).testTag("capture_edit_$index")) { Text(s(R.string.capture_edit)) }
                    TextButton(onClick = { historyId = draft.source.id }, modifier = Modifier.weight(1f).testTag("capture_history_$index")) { Text(s(R.string.capture_history)) }
                    TextButton(onClick = { deleting = draft }, enabled = canManage,
                        modifier = Modifier.weight(1f).testTag("capture_delete_$index")) { Text(s(R.string.task_delete)) }
                }
            }
        }
        Button(capture, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s(R.string.new_memory)) }
        OutlinedButton(onClick = export, enabled = canManage, modifier = Modifier.fillMaxWidth().testTag("capture_export")) {
            Text(s(R.string.capture_export))
        }
        OutlinedButton(onClick = importFile, enabled = canManage, modifier = Modifier.fillMaxWidth().testTag("capture_import")) {
            Text(s(R.string.capture_import))
        }
    }
    val editing = drafts.find { it.source.id == editingId }
    if (editing != null) {
        AlertDialog(onDismissRequest = { if (!managementBusy) { editingId = null; pendingRevision = null; pendingTime = null } },
            title = { Text(s(R.string.capture_edit)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s(R.string.capture_edit_hint))
                OutlinedTextField(editingText, { if (TextSourceRules.validDraft(it)) editingText = it },
                    enabled = !managementBusy && pendingRevision == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).testTag("capture_edit_text"), label = { Text(s(R.string.capture_label)) })
                if (managementError) Text(s(R.string.capture_edit_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capture_edit_error"))
            } },
            confirmButton = { TextButton(onClick = {
                val id = pendingRevision ?: UUID.randomUUID().toString().also { pendingRevision = it }
                val at = pendingTime ?: Instant.now().toString().also { pendingTime = it }
                revise(CaptureRevisionRequest(editing.source.id, editingPrevious, id, editingText, Instant.parse(at)))
            }, enabled = canManage && !managementBusy && editingText.isNotBlank() &&
                (pendingRevision != null || editingText != editing.source.revisions.last().text),
                modifier = Modifier.testTag("capture_edit_save")) { Text(s(if (pendingRevision == null) R.string.task_save else R.string.capture_retry)) } },
            dismissButton = { TextButton(onClick = { editingId = null; pendingRevision = null; pendingTime = null }, enabled = !managementBusy) { Text(s(R.string.task_cancel)) } })
    }
    drafts.find { it.source.id == historyId }?.let { entry ->
        AlertDialog(onDismissRequest = { historyId = null }, title = { Text(s(R.string.capture_history)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s(R.string.capture_history_hint))
                entry.source.revisions.asReversed().forEach { revision ->
                    Text(LocalStrings.current.getString(R.string.capture_revision_number, revision.number), style = MaterialTheme.typography.titleSmall)
                    Text(revision.text, modifier = Modifier.testTag("capture_revision_${revision.number}"))
                    Text(revision.recordedAt.toString(), style = MaterialTheme.typography.bodySmall)
                }
            } }, confirmButton = { TextButton(onClick = { historyId = null }) { Text(s(R.string.close)) } })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(s(R.string.task_delete)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s(R.string.capture_delete_confirm))
                Text(s(R.string.capture_delete_history_warning))
                Text(entry.source.revisions.last().text, modifier = Modifier.testTag("capture_delete_target"))
            } },
            confirmButton = { TextButton(onClick = { deleting = null; delete(entry) }, modifier = Modifier.testTag("capture_delete_confirm")) { Text(s(R.string.task_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(s(R.string.task_cancel)) } })
    }
}

@Composable
fun SettingsScreen(theme: String, changeTheme: (String) -> Unit, language: String, changeLanguage: (String) -> Unit, ready: Boolean, error: Boolean, retry: () -> Unit, about: () -> Unit) {
    ScreenColumn {
        ScreenHeading(s(R.string.settings), s(R.string.settings_subtitle))
        if (error) {
            Text(s(R.string.settings_storage_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings_storage_error"))
            OutlinedButton(onClick = retry, modifier = Modifier.testTag("settings_retry")) { Text(s(R.string.settings_retry)) }
        }
        SectionHeading(s(R.string.appearance))
        Filters(listOf("system" to s(R.string.theme_system), "light" to s(R.string.theme_light), "dark" to s(R.string.theme_dark)), theme, changeTheme, enabled = ready)
        SectionHeading(s(R.string.language))
        Filters(listOf("ru" to "Русский", "az" to "Azərbaycan", "en" to "English"), language, changeLanguage, enabled = ready)
        DevicePermissionSettings()
        MemoraCard { MenuRow(Glyph.Shield, s(R.string.privacy), s(R.string.privacy_hint), 1, about) }
        MemoraCard { MenuRow(Glyph.Spark, s(R.string.about), s(R.string.version), onClick = about) }
    }
}

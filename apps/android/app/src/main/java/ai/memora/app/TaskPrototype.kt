package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ai.memora.app.data.*
import ai.memora.app.domain.*
import ai.memora.app.reminders.TaskReminders
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test-only user flow. Do not enter personal data until export/deletion and device checks exist. */
@Composable
internal fun TaskPrototype(store: EncryptedTaskStore, onOpenTarget: (AppRoute) -> Unit = {}) {
    var tasks by remember(store) { mutableStateOf<List<Task>?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var attemptId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var storeRetry by remember { mutableIntStateOf(0) }
    var exportFailed by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDue by remember { mutableStateOf("") }
    var editZone by remember { mutableStateOf("") }
    var editPerson by remember { mutableStateOf("") }
    var editProject by remember { mutableStateOf("") }
    var editRemind by remember { mutableStateOf(false) }
    var selectedFilterName by rememberSaveable { mutableStateOf(TaskScreenFilter.ALL.name) }
    val selectedFilter = TaskScreenFilter.entries.firstOrNull { it.name == selectedFilterName } ?: TaskScreenFilter.ALL
    val visibleTasks = remember(tasks, selectedFilter) {
        tasks.orEmpty().filter { task ->
            when (selectedFilter) {
                TaskScreenFilter.ALL -> true
                TaskScreenFilter.OPEN -> !task.completed
                TaskScreenFilter.COMPLETED -> task.completed
            }
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val assistantScopeId = remember(processor) { processor.conversationScopeId() }
    val protectedMemory = remember(database) { ProtectedMemoryRepository(database) }
    var assistantTasks by remember { mutableStateOf<List<Task>?>(null) }
    var assistantLoading by remember { mutableStateOf(true) }
    var assistantLoadError by remember { mutableStateOf(false) }
    var assistantWriteError by remember { mutableStateOf(false) }
    var assistantBusy by remember { mutableStateOf(false) }
    var assistantReload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var notifyAllowed by remember { mutableStateOf(TaskReminders.available(context)) }
    var priorIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifyAllowed = TaskReminders.available(context)
    }
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notifyAllowed = TaskReminders.available(context)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true; error = false; exportFailed = false; exportDone = false
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val result = store.export() as? StoreExport.Ready ?: return@withContext false
                        val out = context.contentResolver.openOutputStream(uri) ?: return@withContext false
                        out.use { it.write(result.json.toByteArray(Charsets.UTF_8)); it.flush() }
                        true
                    } catch (_: Exception) { false }
                }
                exportDone = success; exportFailed = !success; busy = false
            }
        }
    }

    LaunchedEffect(store, storeRetry) {
        busy = true
        error = false
        val result = withContext(Dispatchers.IO) { store.load() }
        if (result is StoreRead.Ready) tasks = result.tasks else error = true
        busy = false
    }

    LaunchedEffect(assistantScopeId, assistantReload) {
        assistantLoading = true
        try {
            assistantTasks = withContext(Dispatchers.IO) {
                protectedMemory.tasks().tasks.filter { it.scopeId == assistantScopeId }
            }
            assistantLoadError = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            assistantTasks = null
            assistantLoadError = true
        } finally {
            assistantLoading = false
        }
    }
    LaunchedEffect(selectedFilterName) { listState.scrollToItem(0) }

    LaunchedEffect(tasks, notifyAllowed) {
        val current = tasks ?: return@LaunchedEffect
        val newIds = current.map { it.id }.toSet()
        try {
            withContext(Dispatchers.IO) {
                (priorIds - newIds).forEach { TaskReminders.cancel(context, it) }
                TaskReminders.reschedule(context, current)
            }
            priorIds = newIds
        } catch (_: Exception) { error = true }
    }

    fun applyEvent(event: TaskEvent, onSaved: () -> Unit = {}) {
        if (busy || tasks == null) return
        busy = true
        error = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.append(event) }
            if (result == StoreWrite.Recorded || result == StoreWrite.Duplicate) {
                onSaved()
                when (val loaded = withContext(Dispatchers.IO) { store.load() }) {
                    is StoreRead.Ready -> tasks = loaded.tasks
                    StoreRead.Unavailable -> { tasks = null; error = true }
                }
            } else {
                // Reload after a conflict; never show an uncommitted change as successful.
                val loaded = withContext(Dispatchers.IO) { store.load() }
                tasks = (loaded as? StoreRead.Ready)?.tasks
                error = true
            }
            busy = false
        }
    }

    fun removeTask(task: Task?) {
        if (busy || tasks == null) return
        busy = true; error = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (task == null) store.clearAll() else store.deleteTask(task.id, task.version)
            }
            val loaded = withContext(Dispatchers.IO) { store.load() }
            tasks = (loaded as? StoreRead.Ready)?.tasks
            error = result != StoreWrite.Recorded || tasks == null
            busy = false
        }
    }

    fun completeAssistantTask(task: Task, completed: Boolean) {
        if (assistantBusy || task.completed == completed) return
        val decision = TaskRules.complete(task, task.version, assistantScopeId, UUID.randomUUID().toString(), completed)
        val event = (decision as? TaskDecision.Changed)?.event ?: return
        assistantBusy = true
        assistantWriteError = false
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    ai.memora.app.data.protectedstore.ProtectedDomainRepository(database).appendTaskEvent(event)
                }
                TaskReminders.schedule(context, saved)
                assistantTasks = withContext(Dispatchers.IO) {
                    protectedMemory.tasks().tasks.filter { it.scopeId == assistantScopeId }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                assistantWriteError = true
                assistantReload++
            } finally {
                assistantBusy = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("tasks_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "tasks_intro") {
            Text(s(R.string.working_tasks_hint), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        item(key = "assistant_tasks_heading") {
            SectionHeading(s(R.string.assistant_tasks_title), badge = assistantTasks?.size?.toString())
        }
        when {
            assistantLoading && assistantTasks == null -> item(key = "assistant_tasks_loading") {
                MemoraCard { Text(s(R.string.prototype_loading), color = colors.muted) }
            }
            assistantLoadError -> item(key = "assistant_tasks_error") {
                MemoraCard {
                    Text(s(R.string.prototype_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { assistantReload++ }, modifier = Modifier.testTag("assistant_tasks_retry")) {
                        Text(s(R.string.capture_retry))
                    }
                }
            }
            assistantWriteError -> item(key = "assistant_tasks_write_error") {
                Text(s(R.string.prototype_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("assistant_tasks_write_error"))
            }
            assistantTasks.orEmpty().isEmpty() -> item(key = "assistant_tasks_empty") {
                Text(s(R.string.task_assistant_empty), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        items(assistantTasks.orEmpty(), key = { "assistant:${it.id}" }) { task ->
            MemoraCard(contentPadding = 14.dp, modifier = Modifier.testTag("assistant_task_${task.id}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(
                        checked = task.completed,
                        onCheckedChange = { completeAssistantTask(task, it) },
                        enabled = !assistantBusy,
                        modifier = Modifier.testTag("assistant_task_check_${task.id}"),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, color = colors.ink,
                            textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                        Text(s(if (task.completed) R.string.task_completed else R.string.task_open),
                            style = MaterialTheme.typography.labelMedium, color = if (task.completed) colors.green else colors.accent)
                    }
                }
                task.dueLocal?.let { due ->
                    Text("${s(R.string.task_due_label)}: ${formatTaskDue(due, locale)}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                task.personLabel?.takeIf(String::isNotBlank)?.let {
                    Text("${s(R.string.task_person)}: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                val target = AppRoute.target(AppTargetKind.TASK, task.id)
                if (target != null) TextButton(onClick = { onOpenTarget(target) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assistant_task_open_${task.id}")) {
                    Text(s(R.string.task_open_details))
                }
            }
        }

        item(key = "device_tasks_heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeading(s(R.string.working_tasks_hint), badge = tasks?.size?.toString())
                Text(s(R.string.task_local_store_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        if (busy && tasks == null) item(key = "device_tasks_loading") {
            MemoraCard { Text(s(R.string.prototype_loading), color = colors.muted) }
        }
        if (error) item(key = "device_tasks_error") {
            MemoraCard {
                Text(s(R.string.prototype_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("prototype_error"))
                TextButton(onClick = { storeRetry++ }, enabled = !busy, modifier = Modifier.testTag("device_tasks_retry")) {
                    Text(s(R.string.capture_retry))
                }
            }
        }
        if (exportFailed) item(key = "task_export_error") {
            Text(s(R.string.task_export_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("task_export_error"))
        }
        if (!notifyAllowed && tasks?.any { it.remind && !it.completed } == true) item(key = "reminder_denied") {
            Text(s(R.string.reminder_denied), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reminder_denied"))
        }
        if (exportDone) item(key = "task_export_success") {
            Text(s(R.string.task_export_success), color = colors.green, modifier = Modifier.testTag("task_export_success"))
        }
        if (tasks != null) {
            item(key = "device_task_editor") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it; attemptId = UUID.randomUUID().toString() },
                        modifier = Modifier.fillMaxWidth().testTag("prototype_input"),
                        label = { Text(s(R.string.prototype_title)) }, singleLine = true, enabled = !busy)
                    val decision = TaskRules.create(attemptId, "local", "local-owner", attemptId, title)
                    Button(onClick = {
                        if (decision is TaskDecision.Changed) applyEvent(decision.event) {
                            title = ""; attemptId = UUID.randomUUID().toString()
                        }
                    }, enabled = !busy && decision is TaskDecision.Changed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("prototype_add")) {
                        Text(s(R.string.prototype_add))
                    }
                }
            }
            item(key = "device_task_filters") {
                Filters(
                    listOf(
                        TaskScreenFilter.ALL.name to s(R.string.all),
                        TaskScreenFilter.OPEN.name to s(R.string.task_open),
                        TaskScreenFilter.COMPLETED.name to s(R.string.task_completed),
                    ),
                    selectedFilter.name,
                    { selectedFilterName = it },
                    enabled = !busy,
                )
            }
            if (tasks!!.isEmpty()) item(key = "device_task_empty") {
                Text(s(R.string.prototype_empty), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            } else if (visibleTasks.isEmpty()) item(key = "device_task_filter_empty") {
                Text(s(R.string.task_filter_empty), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            items(visibleTasks, key = { "device:${it.id}" }) { task ->
                MemoraCard(contentPadding = 14.dp, modifier = Modifier.testTag("device_task_${task.id}")) {
                    Row(Modifier.fillMaxWidth().toggleable(value = task.completed, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { done ->
                            val change = TaskRules.complete(task, task.version, "local-owner", UUID.randomUUID().toString(), done)
                            if (change is TaskDecision.Changed) applyEvent(change.event)
                        }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task.completed, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            color = colors.ink, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                    }
                    task.dueLocal?.let { Text("${s(R.string.task_due_label)}: ${formatTaskDue(it, locale)} · ${task.dueZone.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    if (!task.completed && (TaskReminders.dueAt(task) ?: Long.MAX_VALUE) <= System.currentTimeMillis())
                        Text(s(R.string.task_overdue), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    task.personLabel?.takeIf(String::isNotBlank)?.let {
                        Text("${s(R.string.task_person)}: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                    task.projectLabel?.takeIf(String::isNotBlank)?.let {
                        Text("${s(R.string.task_project)}: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = {
                            editing = task; editTitle = task.title; editDue = task.dueLocal?.replace('T', ' ') ?: ""
                            editZone = task.dueZone ?: ZoneId.systemDefault().id
                            editPerson = task.personLabel ?: ""; editProject = task.projectLabel ?: ""; editRemind = task.remind
                        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("edit_${task.id}")) {
                            Text(s(R.string.task_edit))
                        }
                        TextButton(onClick = { deleting = task }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("delete_${task.id}")) { Text(s(R.string.task_delete)) }
                    }
                }
            }
            item(key = "device_task_export_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s(R.string.prototype_warning), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { confirmExport = true }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("task_export")) { Text(s(R.string.task_export)) }
                        OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && tasks!!.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("task_clear_all")) { Text(s(R.string.task_delete_all)) }
                    }
                }
            }
        }
    }

    editing?.let { task ->
        val local = editDue.trim().takeIf { it.isNotEmpty() }?.replace(' ', 'T')
        val decision = TaskRules.edit(task, task.version, "local-owner", UUID.randomUUID().toString(), editTitle,
            local, if (local == null) null else editZone, editRemind, editPerson, editProject)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(s(R.string.task_edit)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editTitle, { editTitle = it }, label = { Text(s(R.string.prototype_title)) },
                    singleLine = true, modifier = Modifier.testTag("task_edit_input"))
                OutlinedTextField(editDue, { editDue = it }, label = { Text(s(R.string.task_due)) },
                    singleLine = true, modifier = Modifier.testTag("task_due_input"))
                Text(LocalStrings.current.getString(R.string.task_due_zone, editZone), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editPerson, { editPerson = it }, label = { Text(s(R.string.task_person)) }, singleLine = true,
                    modifier = Modifier.testTag("task_person_input"))
                OutlinedTextField(editProject, { editProject = it }, label = { Text(s(R.string.task_project)) }, singleLine = true,
                    modifier = Modifier.testTag("task_project_input"))
                Row(Modifier.fillMaxWidth().toggleable(value = editRemind, role = Role.Checkbox, onValueChange = { editRemind = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(editRemind, onCheckedChange = null); Text(s(R.string.task_remind))
                }
                Text(s(R.string.task_notification_warning), style = MaterialTheme.typography.bodySmall)
                if (decision is TaskDecision.Invalid && editDue.isNotBlank()) Text(s(R.string.task_details_error), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                editing = null
                if (decision is TaskDecision.Changed) applyEvent(decision.event)
                if (editRemind && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, enabled = decision is TaskDecision.Changed || decision is TaskDecision.Unchanged,
                modifier = Modifier.testTag("task_edit_save")) { Text(s(R.string.task_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(s(R.string.task_cancel)) } })
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(s(R.string.task_delete)) },
            text = { Text(s(R.string.task_delete_confirm)) },
            confirmButton = { TextButton(onClick = { deleting = null; removeTask(task) },
                modifier = Modifier.testTag("task_delete_confirm")) { Text(s(R.string.task_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(s(R.string.task_cancel)) } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(s(R.string.task_delete_all)) },
        text = { Text(s(R.string.task_delete_all_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; removeTask(null) }) { Text(s(R.string.task_delete)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(s(R.string.task_cancel)) } })
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text(s(R.string.task_export)) },
        text = { Text(s(R.string.task_export_warning)) },
        confirmButton = { TextButton(onClick = { confirmExport = false; exportLauncher.launch("memora-tasks.json") }) { Text(s(R.string.task_export)) } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(s(R.string.task_cancel)) } })
}

private enum class TaskScreenFilter { ALL, OPEN, COMPLETED }

private fun formatTaskDue(raw: String, locale: Locale): String = runCatching {
    LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
}.getOrDefault(raw.replace('T', ' '))

package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ai.memora.app.data.*
import ai.memora.app.domain.*
import ai.memora.app.reminders.TaskReminders
import ai.memora.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.ZoneId

/** Test-only user flow. Do not enter personal data until export/deletion and device checks exist. */
@Composable
fun TaskPrototype(store: EncryptedTaskStore) {
    var tasks by remember(store) { mutableStateOf<List<Task>?>(null) }
    var title by remember { mutableStateOf("") }
    var attemptId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
            busy = true; error = false; exportDone = false
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val result = store.export() as? StoreExport.Ready ?: return@withContext false
                        val out = context.contentResolver.openOutputStream(uri) ?: return@withContext false
                        out.use { it.write(result.json.toByteArray(Charsets.UTF_8)); it.flush() }
                        true
                    } catch (_: Exception) { false }
                }
                exportDone = success; error = !success; busy = false
            }
        }
    }

    LaunchedEffect(store) {
        val result = withContext(Dispatchers.IO) { store.load() }
        if (result is StoreRead.Ready) tasks = result.tasks else error = true
        busy = false
    }

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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(s(R.string.prototype_tasks))
        Text(s(R.string.prototype_warning), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (busy && tasks == null) Text(s(R.string.prototype_loading), color = colors.muted)
        if (error) Text(s(R.string.prototype_error), color = MaterialTheme.colorScheme.error)
        if (!notifyAllowed && tasks?.any { it.remind && !it.completed } == true)
            Text(s(R.string.reminder_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (exportDone) Text(s(R.string.task_export_success), color = colors.muted)
        if (tasks != null) {
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
            if (tasks!!.isEmpty()) Text(s(R.string.prototype_empty), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            tasks!!.forEach { task ->
                Surface(color = colors.card, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(8.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(value = task.completed, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { done ->
                                val change = TaskRules.complete(task, task.version, "local-owner", UUID.randomUUID().toString(), done)
                                if (change is TaskDecision.Changed) applyEvent(change.event)
                            }).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.completed, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(task.title, style = MaterialTheme.typography.titleMedium,
                                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                        }
                        task.dueLocal?.let { Text("${it.replace('T', ' ')} · ${task.dueZone}", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                        if (!task.completed && (TaskReminders.dueAt(task) ?: Long.MAX_VALUE) <= System.currentTimeMillis())
                            Text(s(R.string.task_overdue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        task.personLabel?.let { Text("${s(R.string.task_person)}: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                        task.projectLabel?.let { Text("${s(R.string.task_project)}: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                        Row {
                            TextButton(onClick = {
                                editing = task; editTitle = task.title; editDue = task.dueLocal?.replace('T', ' ') ?: ""
                                editZone = task.dueZone ?: ZoneId.systemDefault().id
                                editPerson = task.personLabel ?: ""; editProject = task.projectLabel ?: ""; editRemind = task.remind
                            }, enabled = !busy,
                                modifier = Modifier.testTag("edit_${task.id}")) { Text(s(R.string.task_edit)) }
                            TextButton(onClick = { deleting = task }, enabled = !busy,
                                modifier = Modifier.testTag("delete_${task.id}")) { Text(s(R.string.task_delete)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmExport = true }, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("task_export")) { Text(s(R.string.task_export)) }
                OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && tasks!!.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("task_clear_all")) { Text(s(R.string.task_delete_all)) }
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

package ai.memora.app

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.LinkedViewSpec
import ai.memora.app.data.protectedstore.LinkedViewPage
import ai.memora.app.data.protectedstore.LINKED_VIEW_MAX_OFFSET
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.TableDataRow
import ai.memora.app.data.protectedstore.TableColumnRow
import ai.memora.app.data.protectedstore.TableSnapshot
import ai.memora.app.data.protectedstore.TableSchemaRevisionSnapshot
import ai.memora.app.data.protectedstore.ImportPreview
import ai.memora.app.data.protectedstore.TableTransferRead
import ai.memora.app.data.protectedstore.TableTransferReader
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Currency

/** Visible local table manager backed by the same protected domain store as Gemini tools. */
@Composable
fun TablesScreen() {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val database = rememberUiAgentDatabase()
    val repository = remember(database) { ProtectedDomainRepository(database) }
    val activeLocale = LocalLocale.current.platformLocale
    val coroutineScope = rememberCoroutineScope()
    var tables by remember { mutableStateOf<List<TableSnapshot>>(emptyList()) }
    var selectedTableId by remember { mutableStateOf<String?>(null) }
    var deletedRows by remember { mutableStateOf<List<TableDataRow>>(emptyList()) }
    var showDeleted by remember { mutableStateOf(false) }
    var createTableDialog by remember { mutableStateOf(false) }
    var rowDialog by remember { mutableStateOf(false) }
    var deleteRowTarget by remember { mutableStateOf<TableDataRow?>(null) }
    var editingRow by remember { mutableStateOf<TableDataRow?>(null) }
    var rowValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var referenceColumn by remember { mutableStateOf<TableColumnRow?>(null) }
    var referenceQuery by remember { mutableStateOf("") }
    var referenceOptions by remember { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
    var referenceLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var referenceLoading by remember { mutableStateOf(false) }
    var referenceError by remember { mutableStateOf(false) }
    var newTableTitle by remember { mutableStateOf("") }
    var newTableColumns by remember { mutableStateOf("") }
    var linkedViewMode by remember { mutableStateOf(false) }
    var linkedSourceKind by remember { mutableStateOf(DomainKind.PERSON) }
    var linkedQuery by remember { mutableStateOf("") }
    var linkedPage by remember { mutableIntStateOf(0) }
    var linkedSortAscending by remember { mutableStateOf(true) }
    var linkedPageResult by remember { mutableStateOf<LinkedViewPage?>(null) }
    var linkedPageLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var transferBusy by remember { mutableStateOf(false) }
    var transferSuccess by remember { mutableStateOf(false) }
    var schemaSuccess by remember { mutableStateOf(false) }
    var transferTableId by remember { mutableStateOf<String?>(null) }
    var pendingTableImport by remember { mutableStateOf<ImportPreview?>(null) }
    var aggregateColumnId by remember { mutableStateOf<String?>(null) }
    var aggregateTotals by remember { mutableStateOf<Map<String, Long>?>(null) }
    var aggregateLoading by remember { mutableStateOf(false) }
    var aggregateMenuExpanded by remember { mutableStateOf(false) }
    var addColumnsDialog by remember { mutableStateOf(false) }
    var addColumnsInput by remember { mutableStateOf("") }
    var pendingSchemaChange by remember { mutableStateOf<List<ColumnSpec>?>(null) }
    var schemaHistory by remember { mutableStateOf<List<TableSchemaRevisionSnapshot>>(emptyList()) }
    var showSchemaHistory by remember { mutableStateOf(false) }
    var pendingSchemaRestore by remember { mutableStateOf<TableSchemaRevisionSnapshot?>(null) }
    val selected = tables.firstOrNull { it.table.tableId == selectedTableId }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val tableId = transferTableId
        if (uri != null && tableId != null) coroutineScope.launch {
            transferBusy = true
            error = false
            transferSuccess = false
            val success = withContext(Dispatchers.IO) {
                try {
                    val json = repository.exportTable(scopeId, tableId)
                    val output = context.contentResolver.openOutputStream(uri) ?: run {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        return@withContext false
                    }
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)); it.flush() }
                    true
                } catch (_: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    false
                }
            }
            transferBusy = false
            error = !success
            transferSuccess = success
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tableId = transferTableId
        if (uri != null && tableId != null) coroutineScope.launch {
            transferBusy = true
            error = false
            transferSuccess = false
            val read = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext TableTransferRead.Unavailable
                    input.use { TableTransferReader.read(it) }
                } catch (_: Exception) { TableTransferRead.Unavailable }
            }
            try {
                val json = (read as? TableTransferRead.Ready)?.json ?: throw IllegalArgumentException("table_import_file_invalid")
                pendingTableImport = repository.previewImport(scopeId, tableId, json)
            } catch (_: Exception) {
                error = true
            } finally {
                transferBusy = false
            }
        }
    }

    LaunchedEffect(selectedTableId) {
        linkedQuery = ""
        linkedPage = 0
        linkedSortAscending = true
        aggregateTotals = null
        schemaSuccess = false
        schemaHistory = emptyList()
        aggregateColumnId = selected?.columns?.firstOrNull { it.valueType == ColumnType.MONEY.name }?.columnId
    }

    LaunchedEffect(scopeId, selectedTableId, showDeleted) {
        try {
            tables = repository.tables(scopeId)
            deletedRows = selectedTableId?.let { repository.deletedRows(scopeId, it) }.orEmpty()
            error = false
        } catch (_: Exception) { error = true }
    }

    LaunchedEffect(scopeId, selectedTableId, tables, linkedQuery, linkedPage, linkedSortAscending, activeLocale) {
        val table = tables.firstOrNull { it.table.tableId == selectedTableId && it.table.mode == "linked_view" }
        if (table == null) {
            linkedPageResult = null
            linkedPageLoading = false
            return@LaunchedEffect
        }
        linkedPageResult = null
        linkedPageLoading = true
        try {
            linkedPageResult = repository.linkedViewPage(scopeId, table.table.tableId, linkedQuery, linkedSortAscending,
                (linkedPage * 25).coerceAtMost(LINKED_VIEW_MAX_OFFSET), 25, activeLocale)
            error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            linkedPageResult = null
            error = true
        } finally {
            linkedPageLoading = false
        }
    }

    LaunchedEffect(scopeId, selectedTableId, tables, deletedRows) {
        val table = tables.firstOrNull { it.table.tableId == selectedTableId }
        if (table == null) {
            referenceLabels = emptyMap()
        } else {
            val referenceColumns = table.columns.filter { it.valueType == ColumnType.PERSON_REF.name || it.valueType == ColumnType.PROJECT_REF.name }
            val references = (table.rows + deletedRows).flatMap { row ->
                val values = runCatching { JSONObject(row.valuesJson) }.getOrNull() ?: return@flatMap emptyList()
                referenceColumns.mapNotNull { column ->
                    val id = values.optString(column.columnId).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val expectedKind = if (column.valueType == ColumnType.PERSON_REF.name) DomainKind.PERSON.name else DomainKind.PROJECT.name
                    id to expectedKind
                }
            }.distinct()
            referenceLabels = references.mapNotNull { (id, expectedKind) ->
                val objectRow = runCatching { repository.domainObject(scopeId, id) }.getOrNull()
                    ?.takeIf { it.kind == expectedKind && it.status == "active" && it.deletedAt == null }
                objectRow?.let { id to it.title }
            }.toMap()
        }
    }

    LaunchedEffect(scopeId, referenceColumn?.columnId, referenceQuery) {
        val column = referenceColumn ?: return@LaunchedEffect
        referenceLoading = true
        referenceError = false
        try {
            val kind = if (column.valueType == ColumnType.PERSON_REF.name) DomainKind.PERSON else DomainKind.PROJECT
            referenceOptions = if (referenceQuery.isBlank()) repository.domainObjects(scopeId, kind, limit = 500)
            else repository.search(scopeId, referenceQuery.trim(), kinds = setOf(kind), limit = 100).mapNotNull { hit ->
                repository.domainObject(scopeId, hit.objectId)?.takeIf {
                    it.status == "active" && it.deletedAt == null && it.kind == kind.name
                }
            }.distinctBy { it.objectId }
            referenceError = false
        } catch (_: Exception) {
            referenceOptions = emptyList()
            referenceError = true
        } finally {
            referenceLoading = false
        }
    }

    fun beginRowEdit(row: TableDataRow?) {
        val table = selected ?: return
        error = false
        editingRow = row
        val stored = row?.let { runCatching { JSONObject(it.valuesJson) }.getOrNull() } ?: JSONObject()
        rowValues = table.columns.associate { column ->
            val value = stored.opt(column.columnId)
            column.columnId to when {
                value is JSONObject && column.valueType == ColumnType.MONEY.name -> {
                    val minor = value.optLong("minorUnits")
                    "${minor / 100}.${(minor % 100).toString().padStart(2, '0')} ${value.optString("currency", "AZN")}"
                }
                value == null || value == JSONObject.NULL -> ""
                else -> value.toString()
            }
        }
        rowDialog = true
    }

    fun openReferencePicker(column: TableColumnRow) {
        referenceQuery = ""
        referenceOptions = emptyList()
        referenceColumn = column
        rowDialog = false
    }

    fun closeReferencePicker() {
        referenceColumn = null
        referenceQuery = ""
        rowDialog = true
    }

    pendingTableImport?.let { preview ->
        val importingView = preview.mode == "linked_view"
        AlertDialog(
            onDismissRequest = { if (!transferBusy) pendingTableImport = null },
            title = { Text(s(R.string.table_import_preview_title)) },
            text = { Text(if (importingView)
                s(R.string.table_import_linked_view_preview)
            else LocalStrings.current.getString(R.string.table_import_rows_preview, preview.rows.size)) },
            confirmButton = { TextButton(
                enabled = !transferBusy,
                onClick = {
                    transferBusy = true
                    coroutineScope.launch {
                        try {
                            repository.importRows(scopeId, preview)
                            tables = repository.tables(scopeId)
                            deletedRows = repository.deletedRows(scopeId, preview.tableId)
                            pendingTableImport = null
                            error = false
                            transferSuccess = true
                        } catch (_: Exception) { error = true }
                        finally { transferBusy = false }
                    }
                }, modifier = Modifier.testTag("table_import_confirm"),
            ) { Text(s(R.string.table_import_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingTableImport = null }, enabled = !transferBusy,
                modifier = Modifier.testTag("table_import_cancel")) { Text(s(R.string.task_cancel)) } },
        )
    }

    if (addColumnsDialog && selected != null) AlertDialog(
        onDismissRequest = { addColumnsDialog = false },
        title = { Text(s(R.string.table_add_columns_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(addColumnsInput, { addColumnsInput = it.take(1200) },
                Modifier.fillMaxWidth().testTag("table_add_columns_input"), label = { Text(s(R.string.table_columns)) }, minLines = 2)
            Text(s(R.string.table_add_columns_help), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            if (error) Text(s(R.string.table_action_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            val additions = parseTableColumnDeclarations(addColumnsInput)
            val existingNames = selected.columns.map { it.name.trim().lowercase() }.toSet()
            if (additions == null || additions.isEmpty() || additions.size + selected.columns.size > 50 ||
                additions.any { it.first.lowercase() in existingNames } ||
                (existingNames + additions.map { it.first.lowercase() }).size != existingNames.size + additions.size) {
                error = true
                return@TextButton
            }
            pendingSchemaChange = selected.columns.map { ColumnSpec(it.columnId, it.name,
                ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) } + additions.map { (name, type) ->
                ColumnSpec(UUID.randomUUID().toString(), name, type)
            }
            error = false
            addColumnsDialog = false
        }, modifier = Modifier.testTag("table_add_columns_preview")) { Text(s(R.string.table_schema_preview)) } },
        dismissButton = { TextButton(onClick = { addColumnsDialog = false; addColumnsInput = "" }) { Text(s(R.string.task_cancel)) } },
    )

    pendingSchemaChange?.let { proposed ->
        val current = selected
        val additions = current?.let { proposed.drop(it.columns.size) }.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingSchemaChange = null },
            title = { Text(s(R.string.table_schema_preview)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s(R.string.table_schema_add_preview_explainer))
                additions.forEach { Text("+ ${it.name} (${it.type.name})", color = colors.accent) }
            } },
            confirmButton = { TextButton(enabled = current != null, modifier = Modifier.testTag("table_schema_apply"), onClick = {
                val table = current ?: return@TextButton
                val schema = proposed
                pendingSchemaChange = null
                coroutineScope.launch {
                    try {
                        repository.changeTableSchema(scopeId, table.table.tableId, table.table.schemaVersion, schema,
                            java.time.Instant.now().toString())
                        tables = repository.tables(scopeId)
                        addColumnsInput = ""
                        error = false
                        schemaSuccess = true
                    } catch (_: Exception) { error = true }
                }
            }) { Text(s(R.string.table_schema_apply)) } },
            dismissButton = { TextButton(onClick = { pendingSchemaChange = null }) { Text(s(R.string.task_cancel)) } },
        )
    }

    if (showSchemaHistory && selected != null) AlertDialog(
        onDismissRequest = { showSchemaHistory = false },
        title = { Text(s(R.string.table_schema_history_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (schemaHistory.isEmpty()) Text(s(R.string.table_schema_history_empty))
            schemaHistory.asReversed().forEach { revision ->
                val isCurrent = revision.version == selected.table.schemaVersion
                TextButton(enabled = !isCurrent, modifier = Modifier.fillMaxWidth().testTag("table_schema_revision_${revision.version}"),
                    onClick = { pendingSchemaRestore = revision; showSchemaHistory = false }) {
                    Text(LocalStrings.current.getString(if (isCurrent) R.string.table_schema_revision_current else R.string.table_schema_revision_restore,
                        revision.version))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { showSchemaHistory = false }) { Text(s(R.string.task_cancel)) } },
    )

    pendingSchemaRestore?.let { revision ->
        val current = selected
        AlertDialog(
            onDismissRequest = { pendingSchemaRestore = null },
            title = { Text(s(R.string.table_schema_restore_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s(R.string.table_schema_restore_explainer))
                Text(s(R.string.table_schema_restore_fields), color = colors.muted)
                revision.columns.forEach { column ->
                    Text("${column.name} (${column.valueType})", modifier = Modifier.testTag("table_schema_restore_field_${column.columnId}"))
                }
            } },
            confirmButton = { TextButton(enabled = current != null, modifier = Modifier.testTag("table_schema_restore_confirm"), onClick = {
                val table = current ?: return@TextButton
                pendingSchemaRestore = null
                coroutineScope.launch {
                    try {
                        repository.restoreTableSchema(scopeId, table.table.tableId, table.table.schemaVersion,
                            revision.version, java.time.Instant.now().toString())
                        tables = repository.tables(scopeId)
                        error = false
                        schemaSuccess = true
                    } catch (_: Exception) { error = true }
                }
            }) { Text(s(R.string.table_schema_restore_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingSchemaRestore = null }) { Text(s(R.string.task_cancel)) } },
        )
    }

    if (createTableDialog) AlertDialog(
        onDismissRequest = { createTableDialog = false },
        title = { Text(s(ai.memora.app.R.string.table_create)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(newTableTitle, { newTableTitle = it.take(200) }, Modifier.fillMaxWidth().testTag("table_new_title"),
                label = { Text(s(ai.memora.app.R.string.table_title)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !linkedViewMode, onClick = { linkedViewMode = false },
                    label = { Text(s(R.string.table_mode_editable)) }, modifier = Modifier.testTag("table_mode_editable"))
                FilterChip(selected = linkedViewMode, onClick = { linkedViewMode = true },
                    label = { Text(s(R.string.table_mode_linked_view)) }, modifier = Modifier.testTag("table_mode_linked_view"))
            }
            if (linkedViewMode) {
                Text(s(R.string.table_linked_view_explainer), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(DomainKind.PERSON, DomainKind.PROJECT, DomainKind.NOTE).forEach { kind ->
                        FilterChip(selected = linkedSourceKind == kind, onClick = { linkedSourceKind = kind },
                        label = { Text(s(when (kind) {
                                DomainKind.PERSON -> R.string.table_source_people
                                DomainKind.PROJECT -> R.string.table_source_projects
                                DomainKind.NOTE -> R.string.table_source_notes
                            })) }, modifier = Modifier.testTag("table_source_${kind.name.lowercase()}"))
                    }
                }
            } else {
                OutlinedTextField(newTableColumns, { newTableColumns = it.take(1200) }, Modifier.fillMaxWidth().testTag("table_new_columns"),
                    label = { Text(s(ai.memora.app.R.string.table_columns)) }, minLines = 2)
                Text(s(R.string.table_columns_help), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            val declarations = if (linkedViewMode) listOf("Source" to ColumnType.TEXT) else newTableColumns.split(',').map(String::trim).filter(String::isNotEmpty).map { token ->
                val pieces = token.split(':', limit = 2)
                val name = pieces[0].trim()
                val type = if (pieces.size == 1) ColumnType.TEXT else runCatching { ColumnType.valueOf(pieces[1].trim().uppercase()) }.getOrNull()
                if (type == null || type !in setOf(ColumnType.TEXT, ColumnType.INTEGER, ColumnType.DECIMAL,
                        ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.DATETIME, ColumnType.MONEY,
                        ColumnType.PERSON_REF, ColumnType.PROJECT_REF)) null else name to type
            }
            if (newTableTitle.isBlank() || declarations.isEmpty() || declarations.size > 50 || declarations.any { it == null || it.first.isBlank() || it.first.length > 100 } ||
                declarations.filterNotNull().map { it.first.lowercase() }.distinct().size != declarations.size) { error = true; return@TextButton }
            val id = UUID.randomUUID().toString()
            val createLinkedView = linkedViewMode
            val sourceKind = linkedSourceKind
            val columns = if (createLinkedView) listOf(
                ColumnSpec("source_id", "source_id", ColumnType.TEXT),
                ColumnSpec("title", "title", ColumnType.TEXT),
                ColumnSpec("body", "body", ColumnType.TEXT),
                ColumnSpec("status", "status", ColumnType.TEXT),
                ColumnSpec("version", "version", ColumnType.INTEGER),
            ) else declarations.filterNotNull().map { (name, type) -> ColumnSpec(UUID.randomUUID().toString(), name, type) }
            createTableDialog = false
            coroutineScope.launch {
                try {
                    repository.createTable(scopeId, id, newTableTitle.trim(), if (createLinkedView) "linked_view" else "editable",
                        columns, viewSpec = if (createLinkedView) LinkedViewSpec(sourceKind) else null)
                    selectedTableId = id
                    newTableTitle = ""; newTableColumns = ""; error = false
                    tables = repository.tables(scopeId)
                } catch (_: Exception) { error = true }
            }
        }, modifier = Modifier.testTag("table_create_confirm")) { Text(s(ai.memora.app.R.string.table_save)) } },
        dismissButton = { TextButton(onClick = { createTableDialog = false }) { Text(s(ai.memora.app.R.string.task_cancel)) } },
    )

    if (referenceColumn != null) {
        val column = referenceColumn!!
        val selectedId = rowValues[column.columnId].orEmpty()
        AlertDialog(
            onDismissRequest = { closeReferencePicker() },
            title = { Text(s(if (column.valueType == ColumnType.PERSON_REF.name) R.string.table_select_person else R.string.table_select_project)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(referenceQuery, { referenceQuery = it.take(100) },
                    Modifier.fillMaxWidth().testTag("table_reference_search"), label = { Text(s(R.string.table_reference_search)) }, singleLine = true)
                if (referenceLoading) Text(s(R.string.today_loading), color = colors.muted)
                if (referenceError) Text(s(R.string.table_action_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                if (!column.required && selectedId.isNotEmpty()) TextButton(onClick = {
                    rowValues = rowValues + (column.columnId to "")
                    closeReferencePicker()
                }, modifier = Modifier.testTag("table_reference_clear")) { Text(s(R.string.table_reference_clear)) }
                if (!referenceLoading && !referenceError) referenceOptions.forEach { option ->
                    TextButton(onClick = {
                        rowValues = rowValues + (column.columnId to option.objectId)
                        referenceLabels = referenceLabels + (option.objectId to option.title)
                        closeReferencePicker()
                    }, modifier = Modifier.fillMaxWidth().testTag("table_reference_${option.objectId}")) {
                        Text(option.title, color = if (option.objectId == selectedId) colors.accent else colors.ink)
                    }
                }
                if (!referenceLoading && !referenceError && referenceOptions.isEmpty()) Text(s(R.string.table_reference_empty), color = colors.muted)
            } },
            confirmButton = { TextButton(onClick = { closeReferencePicker() }) { Text(s(R.string.task_cancel)) } },
        )
    }

    if (rowDialog && selected != null) AlertDialog(
        onDismissRequest = { rowDialog = false },
        title = { Text(s(if (editingRow == null) ai.memora.app.R.string.table_add_row else ai.memora.app.R.string.table_edit_row)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error) Text(s(R.string.table_action_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            selected.columns.forEach { column ->
                if (column.valueType == ColumnType.PERSON_REF.name || column.valueType == ColumnType.PROJECT_REF.name) {
                    val id = rowValues[column.columnId].orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${column.name} (${column.valueType})", color = colors.muted)
                        Text(if (id.isBlank()) s(R.string.table_reference_unselected)
                            else referenceLabels[id] ?: s(R.string.table_reference_unavailable), color = colors.ink)
                        OutlinedButton(onClick = { openReferencePicker(column) },
                            modifier = Modifier.fillMaxWidth().testTag("table_cell_${column.columnId}")) {
                            Text(s(if (id.isBlank()) R.string.table_reference_choose else R.string.table_reference_change))
                        }
                    }
                } else OutlinedTextField(rowValues[column.columnId].orEmpty(),
                    { value -> rowValues = rowValues + (column.columnId to value.take(10_000)) },
                    Modifier.fillMaxWidth().testTag("table_cell_${column.columnId}"), label = { Text("${column.name} (${column.valueType})") }, maxLines = 3)
            }
        } },
        confirmButton = { TextButton(onClick = {
            val table = selected
            val parsed = table.columns.associate { column -> column.columnId to parseTableInput(column.valueType, rowValues[column.columnId].orEmpty()) }
            if (parsed.values.any { !it.first }) { error = true; return@TextButton }
            val values: Map<String, Any?> = parsed.mapValues { it.value.second }
            coroutineScope.launch {
                try {
                    val current = editingRow
                    if (current == null) repository.addRows(scopeId, table.table.tableId, table.table.schemaVersion,
                        listOf(UUID.randomUUID().toString() to values))
                    else repository.updateRow(scopeId, table.table.tableId, current.rowId, current.version,
                        table.table.schemaVersion, values)
                    tables = repository.tables(scopeId)
                    rowDialog = false
                    error = false
                } catch (_: Exception) { error = true }
            }
        }, modifier = Modifier.testTag("table_row_save")) { Text(s(ai.memora.app.R.string.table_save)) } },
        dismissButton = { TextButton(onClick = { rowDialog = false }) { Text(s(R.string.task_cancel)) } },
    )

    if (deleteRowTarget != null && selected != null) AlertDialog(
        onDismissRequest = { deleteRowTarget = null },
        title = { Text(s(ai.memora.app.R.string.table_delete_row)) },
        text = { Text(s(ai.memora.app.R.string.table_delete_row_confirm)) },
        confirmButton = { TextButton(onClick = {
            val target = deleteRowTarget ?: return@TextButton
            val table = selected ?: return@TextButton
            deleteRowTarget = null
            coroutineScope.launch {
                try {
                    repository.deleteRow(scopeId, table.table.tableId, target.rowId, target.version, java.time.Instant.now().toString())
                    tables = repository.tables(scopeId)
                    deletedRows = repository.deletedRows(scopeId, table.table.tableId)
                    error = false
                } catch (_: Exception) { error = true }
            }
        }, modifier = Modifier.testTag("table_row_delete_confirm")) { Text(s(ai.memora.app.R.string.table_delete_row)) } },
        dismissButton = { TextButton(onClick = { deleteRowTarget = null }) { Text(s(R.string.task_cancel)) } },
    )

    ScreenColumn {
        ScreenHeading(s(R.string.tables_title), s(R.string.tables_subtitle))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedTableId = null }, modifier = Modifier.weight(1f).testTag("tables_all")) {
                Text(s(R.string.tables_all))
            }
            Button(onClick = { createTableDialog = true }, modifier = Modifier.weight(1f).testTag("table_create")) {
                Text(s(R.string.table_create))
            }
        }
        if (error) Text(s(R.string.table_action_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        if (tables.isEmpty()) EmptyState(s(R.string.tables_empty), s(R.string.tables_subtitle))
        tables.forEach { table ->
            MemoraCard(onClick = { selectedTableId = table.table.tableId }, modifier = Modifier.testTag("table_${table.table.tableId}")) {
                Text(table.table.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(if (table.table.mode == "linked_view")
                    LocalStrings.current.getString(R.string.table_linked_view_summary, table.linkedTotalCount)
                    else LocalStrings.current.getString(ai.memora.app.R.string.table_summary, table.rows.size, table.columns.size), color = colors.muted)
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected.table.title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.ink)
                if (selected.table.mode == "editable") TextButton(onClick = { beginRowEdit(null) }, modifier = Modifier.testTag("table_add_row")) { Text(s(R.string.table_add_row)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    transferTableId = selected.table.tableId
                    val fileName = selected.table.title.trim().replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(64).ifBlank { "memora_table" } + ".json"
                    exportLauncher.launch(fileName)
                }, enabled = !transferBusy, modifier = Modifier.weight(1f).testTag("table_export")) {
                    Text(s(R.string.table_export_json))
                }
                OutlinedButton(onClick = {
                    transferTableId = selected.table.tableId
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                }, enabled = !transferBusy, modifier = Modifier.weight(1f).testTag("table_import")) {
                    Text(s(R.string.table_import_json))
                }
            }
            if (selected.table.mode == "editable") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addColumnsInput = ""; error = false; addColumnsDialog = true },
                        modifier = Modifier.weight(1f).testTag("table_add_columns")) { Text(s(R.string.table_add_columns_title)) }
                    OutlinedButton(onClick = {
                        coroutineScope.launch {
                            try {
                                schemaHistory = repository.tableSchemaHistory(scopeId, selected.table.tableId)
                                showSchemaHistory = true
                                error = false
                            } catch (_: Exception) { error = true }
                        }
                    }, modifier = Modifier.weight(1f).testTag("table_schema_history")) { Text(s(R.string.table_schema_history_title)) }
                }
            }
            if (transferSuccess) Text(s(R.string.table_transfer_success), color = colors.accent,
                modifier = Modifier.testTag("table_transfer_success"))
            if (schemaSuccess) Text(s(R.string.table_schema_saved), color = colors.accent,
                modifier = Modifier.testTag("table_schema_saved"))
            if (transferBusy) Text(s(R.string.today_loading), color = colors.muted)
            if (selected.table.mode == "linked_view") {
                OutlinedTextField(value = linkedQuery, onValueChange = { linkedQuery = it; linkedPage = 0 },
                    modifier = Modifier.fillMaxWidth().testTag("linked_view_filter"),
                    label = { Text(s(R.string.table_linked_view_filter)) }, singleLine = true)
                TextButton(onClick = { linkedSortAscending = !linkedSortAscending; linkedPage = 0 },
                    modifier = Modifier.testTag("linked_view_sort")) {
                    Text(s(if (linkedSortAscending) R.string.table_linked_view_sort_ascending else R.string.table_linked_view_sort_descending))
                }
                val pageSize = 25
                val viewPage = linkedPageResult
                if (linkedPageLoading) Text(s(R.string.table_linked_view_loading), color = colors.muted)
                else if (viewPage == null) Text(s(R.string.table_action_failed), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                val pageCount = viewPage?.let { (it.matchingCount + pageSize - 1) / pageSize } ?: 0
                val pageIndex = viewPage?.let { if (it.matchingCount == 0) 0 else it.offset / pageSize } ?: 0
                Text(LocalStrings.current.getString(R.string.table_linked_view_count,
                    viewPage?.matchingCount ?: 0, viewPage?.totalCount ?: selected.linkedTotalCount),
                    color = colors.muted)
                if (viewPage?.continuationLimitReached == true) Text(s(R.string.table_linked_view_page_limit), color = colors.muted)
                if (!linkedPageLoading && viewPage?.rows.isNullOrEmpty()) Text(s(R.string.table_linked_view_empty), color = colors.muted)
                viewPage?.rows.orEmpty().forEach { source ->
                    MemoraCard(modifier = Modifier.testTag("linked_view_source_${source.objectId}")) {
                        Text(source.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.ink)
                        source.body?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted) }
                        Text(LocalStrings.current.getString(R.string.table_linked_source_version, source.version),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.accent)
                    }
                }
                if (pageCount > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { linkedPage = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0,
                            modifier = Modifier.testTag("linked_view_previous")) { Text(s(R.string.table_linked_view_previous)) }
                        Text(LocalStrings.current.getString(R.string.table_linked_view_page, pageIndex + 1, pageCount), color = colors.muted)
                        TextButton(onClick = { linkedPage = (pageIndex + 1).coerceAtMost(pageCount - 1) }, enabled = viewPage?.nextOffset != null,
                            modifier = Modifier.testTag("linked_view_next")) { Text(s(R.string.table_linked_view_next)) }
                    }
                }
            } else {
            val moneyColumns = selected.columns.filter { it.valueType == ColumnType.MONEY.name }
            if (moneyColumns.isNotEmpty()) {
                if (aggregateColumnId !in moneyColumns.map { it.columnId }) aggregateColumnId = moneyColumns.first().columnId
                val aggregateColumn = moneyColumns.first { it.columnId == aggregateColumnId }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        TextButton(onClick = { aggregateMenuExpanded = true }, modifier = Modifier.testTag("table_aggregate_column")) {
                            Text(aggregateColumn.name)
                        }
                        DropdownMenu(expanded = aggregateMenuExpanded, onDismissRequest = { aggregateMenuExpanded = false }) {
                            moneyColumns.forEach { column -> DropdownMenuItem(
                                text = { Text(column.name) }, onClick = {
                                    aggregateColumnId = column.columnId
                                    aggregateTotals = null
                                    aggregateMenuExpanded = false
                                }, modifier = Modifier.testTag("table_aggregate_column_${column.columnId}"),
                            ) }
                        }
                    }
                    OutlinedButton(onClick = {
                        aggregateLoading = true
                        aggregateTotals = null
                        coroutineScope.launch {
                            try { aggregateTotals = repository.aggregateMoney(scopeId, selected.table.tableId, aggregateColumn.columnId) }
                            catch (_: Exception) { error = true }
                            finally { aggregateLoading = false }
                        }
                    }, enabled = !aggregateLoading, modifier = Modifier.testTag("table_aggregate_run")) {
                        Text(s(R.string.table_aggregate))
                    }
                }
                if (aggregateLoading) Text(s(R.string.today_loading), color = colors.muted)
                aggregateTotals?.let { totals ->
                    if (totals.isEmpty()) Text(s(R.string.table_aggregate_no_values), color = colors.muted)
                    totals.forEach { (currency, minorUnits) ->
                        val amount = BigDecimal.valueOf(minorUnits, 2).toPlainString()
                        Text(LocalStrings.current.getString(R.string.table_aggregate_total, amount, currency),
                            color = colors.accent, modifier = Modifier.testTag("table_aggregate_$currency"))
                    }
                }
            }
            OutlinedButton(onClick = { showDeleted = !showDeleted }, modifier = Modifier.fillMaxWidth().testTag("table_show_deleted")) {
                Text(s(if (showDeleted) R.string.table_show_active else R.string.table_show_deleted))
            }
            val visibleRows = if (showDeleted) deletedRows else selected.rows
            if (visibleRows.isEmpty()) Text(s(R.string.table_no_rows), color = colors.muted)
            visibleRows.forEach { row ->
                val values = runCatching { JSONObject(row.valuesJson) }.getOrDefault(JSONObject())
                MemoraCard(tint = if (row.deletedAt != null) colors.softAccent else colors.card) {
                    selected.columns.forEach { column ->
                        val raw = values.opt(column.columnId)
                        val value = if (raw is JSONObject && column.valueType == ColumnType.MONEY.name) {
                            val minor = raw.optLong("minorUnits")
                            "${minor / 100}.${(minor % 100).toString().padStart(2, '0')} ${raw.optString("currency", "AZN")}"
                        } else if (raw != null && raw != JSONObject.NULL &&
                            (column.valueType == ColumnType.PERSON_REF.name || column.valueType == ColumnType.PROJECT_REF.name)) {
                            referenceLabels[raw.toString()] ?: s(R.string.table_reference_unavailable)
                        } else raw?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
                        if (value.isNotBlank()) Text("${column.name}: $value", color = colors.ink)
                    }
                    HorizontalDivider(color = colors.line)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (row.deletedAt != null) TextButton(onClick = {
                            coroutineScope.launch {
                                try {
                                    repository.restoreRow(scopeId, selected.table.tableId, row.rowId, row.version)
                                    tables = repository.tables(scopeId)
                                    deletedRows = repository.deletedRows(scopeId, selected.table.tableId)
                                } catch (_: Exception) { error = true }
                            }
                        }, modifier = Modifier.testTag("table_row_restore_${row.rowId}")) { Text(s(R.string.table_restore_row)) }
                        else {
                            TextButton(onClick = { beginRowEdit(row) }, modifier = Modifier.testTag("table_row_edit_${row.rowId}")) { Text(s(R.string.table_edit_row)) }
                            TextButton(onClick = { deleteRowTarget = row }, modifier = Modifier.testTag("table_row_delete_${row.rowId}")) { Text(s(R.string.table_delete_row)) }
                        }
                    }
                }
            }
            }
        }
    }
}

private fun parseTableInput(type: String, raw: String): Pair<Boolean, Any?> {
    val value = raw.trim()
    if (value.isEmpty()) return true to null
    return when (runCatching { ColumnType.valueOf(type) }.getOrNull()) {
        ColumnType.TEXT, ColumnType.PERSON_REF, ColumnType.PROJECT_REF -> true to value
        ColumnType.INTEGER -> value.toLongOrNull()?.let { true to it } ?: (false to null)
        ColumnType.DECIMAL -> if (runCatching { BigDecimal(value); true }.getOrDefault(false)) true to value else false to null
        ColumnType.BOOLEAN -> value.toBooleanStrictOrNull()?.let { true to it } ?: (false to null)
        ColumnType.DATE -> if (runCatching { LocalDate.parse(value); true }.getOrDefault(false)) true to value else false to null
        ColumnType.DATETIME -> if (runCatching { OffsetDateTime.parse(value); true }.getOrDefault(false)) true to value else false to null
        ColumnType.MONEY -> {
            val match = Regex("^([0-9]{1,14}(?:[.,][0-9]{1,2})?)(?:\\s+([A-Za-z]{3}))?$").matchEntire(value)
                ?: return false to null
            try {
                val minor = BigDecimal(match.groupValues[1].replace(',', '.')).movePointRight(2).longValueExact()
                val currency = match.groupValues[2].ifBlank { "AZN" }.uppercase()
                Currency.getInstance(currency)
                true to mapOf("minorUnits" to minor, "currency" to currency)
            } catch (_: Exception) { false to null }
        }
        null -> false to null
    }
}

private fun parseTableColumnDeclarations(input: String): List<Pair<String, ColumnType>>? {
    val tokens = input.split(',').map(String::trim)
    if (tokens.isEmpty() || tokens.any(String::isBlank)) return null
    val result = tokens.map { token ->
        val pieces = token.split(':', limit = 2)
        val name = pieces[0].trim()
        val type = if (pieces.size == 1) ColumnType.TEXT else
            runCatching { ColumnType.valueOf(pieces[1].trim().uppercase()) }.getOrNull()
        if (name.isBlank() || name.length > 100 || type !in setOf(ColumnType.TEXT, ColumnType.INTEGER, ColumnType.DECIMAL,
                ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.DATETIME, ColumnType.MONEY,
                ColumnType.PERSON_REF, ColumnType.PROJECT_REF)) return null
        name to requireNotNull(type)
    }
    if (result.map { it.first.lowercase() }.distinct().size != result.size) return null
    return result
}

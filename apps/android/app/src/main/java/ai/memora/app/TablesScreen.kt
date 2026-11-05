package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.LinkedViewSpec
import ai.memora.app.data.protectedstore.linkedViewPage
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.TableDataRow
import ai.memora.app.data.protectedstore.TableColumnRow
import ai.memora.app.data.protectedstore.TableSnapshot
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.launch
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
    var error by remember { mutableStateOf(false) }
    val selected = tables.firstOrNull { it.table.tableId == selectedTableId }

    LaunchedEffect(selectedTableId) {
        linkedQuery = ""
        linkedPage = 0
        linkedSortAscending = true
    }

    LaunchedEffect(scopeId, selectedTableId, showDeleted) {
        try {
            tables = repository.tables(scopeId)
            deletedRows = selectedTableId?.let { repository.deletedRows(scopeId, it) }.orEmpty()
            error = false
        } catch (_: Exception) { error = true }
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
                    LocalStrings.current.getString(R.string.table_linked_view_summary, table.linkedRows.size)
                    else LocalStrings.current.getString(ai.memora.app.R.string.table_summary, table.rows.size, table.columns.size), color = colors.muted)
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected.table.title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.ink)
                if (selected.table.mode == "editable") TextButton(onClick = { beginRowEdit(null) }, modifier = Modifier.testTag("table_add_row")) { Text(s(R.string.table_add_row)) }
            }
            if (selected.table.mode == "linked_view") {
                OutlinedTextField(value = linkedQuery, onValueChange = { linkedQuery = it; linkedPage = 0 },
                    modifier = Modifier.fillMaxWidth().testTag("linked_view_filter"),
                    label = { Text(s(R.string.table_linked_view_filter)) }, singleLine = true)
                TextButton(onClick = { linkedSortAscending = !linkedSortAscending; linkedPage = 0 },
                    modifier = Modifier.testTag("linked_view_sort")) {
                    Text(s(if (linkedSortAscending) R.string.table_linked_view_sort_ascending else R.string.table_linked_view_sort_descending))
                }
                val pageSize = 25
                val viewPage = linkedViewPage(selected.linkedRows, linkedQuery, linkedSortAscending, linkedPage, pageSize)
                Text(LocalStrings.current.getString(R.string.table_linked_view_count, viewPage.matchingCount, selected.linkedTotalCount),
                    color = colors.muted)
                if (selected.linkedTotalCount > selected.linkedRows.size) {
                    Text(s(R.string.table_linked_view_limit), color = colors.muted)
                }
                if (viewPage.rows.isEmpty()) Text(s(R.string.table_linked_view_empty), color = colors.muted)
                viewPage.rows.forEach { source ->
                    MemoraCard(modifier = Modifier.testTag("linked_view_source_${source.objectId}")) {
                        Text(source.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.ink)
                        source.body?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted) }
                        Text(LocalStrings.current.getString(R.string.table_linked_source_version, source.version),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.accent)
                    }
                }
                if (viewPage.pageCount > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { linkedPage = (viewPage.pageIndex - 1).coerceAtLeast(0) }, enabled = viewPage.pageIndex > 0,
                            modifier = Modifier.testTag("linked_view_previous")) { Text(s(R.string.table_linked_view_previous)) }
                        Text(LocalStrings.current.getString(R.string.table_linked_view_page, viewPage.pageIndex + 1, viewPage.pageCount), color = colors.muted)
                        TextButton(onClick = { linkedPage = (viewPage.pageIndex + 1).coerceAtMost(viewPage.pageCount - 1) }, enabled = viewPage.pageIndex + 1 < viewPage.pageCount,
                            modifier = Modifier.testTag("linked_view_next")) { Text(s(R.string.table_linked_view_next)) }
                    }
                }
            } else {
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

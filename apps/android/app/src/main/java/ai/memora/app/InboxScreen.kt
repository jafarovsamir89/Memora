package ai.memora.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ai.memora.app.data.CaptureArchiveSelection
import ai.memora.app.data.CaptureEntry
import ai.memora.app.data.CaptureOrigin
import ai.memora.app.data.CaptureRevisionRequest
import ai.memora.app.domain.TextSourceRules
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.LoadingState
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.Pill
import ai.memora.app.ui.SectionHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import java.time.Instant
import java.util.UUID

/** Local Inbox UI. The encrypted capture store and its retention rules remain the source of truth. */
@Composable
fun InboxScreen(
    drafts: List<CaptureEntry>, loading: Boolean, error: Boolean, managementError: Boolean,
    canManage: Boolean, retry: () -> Unit, capture: () -> Unit, export: () -> Unit,
    importFile: () -> Unit, importIssue: String?, importDone: Boolean, importStale: Boolean,
    archive: (List<CaptureArchiveSelection>, Boolean) -> Unit, managementBusy: Boolean,
    revise: (CaptureRevisionRequest) -> Unit, delete: (CaptureEntry) -> Unit,
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPrevious by rememberSaveable { mutableStateOf("") }
    var editingText by rememberSaveable { mutableStateOf("") }
    var pendingRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTime by rememberSaveable { mutableStateOf<String?>(null) }
    var historyId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("active") }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(drafts, pendingRevision) {
        val revisionId = pendingRevision
        if (revisionId != null && drafts.any { entry ->
                entry.source.id == editingId && entry.source.revisions.any { it.id == revisionId }
            }) {
            editingId = null
            pendingRevision = null
            pendingTime = null
        }
    }
    LaunchedEffect(search, filter, drafts) { selectedIds = emptyList() }

    val normalizedQuery = search.trim()
    val visible = drafts.asReversed().filter { entry ->
        (filter == "all" || entry.archived == (filter == "archived")) &&
            entry.source.revisions.last().text.contains(normalizedQuery, ignoreCase = true)
    }
    val selectedVisible = visible.filter { it.source.id in selectedIds }
    val editing = drafts.find { it.source.id == editingId }
    val history = drafts.find { it.source.id == historyId }
    val deleting = drafts.find { it.source.id == deletingId }

    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s(ai.memora.app.R.string.inbox_subtitle), color = colors.muted,
            style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = capture,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("inbox_new_capture"),
        ) { Text(s(ai.memora.app.R.string.new_memory)) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("inbox_list"),
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "inbox-search") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(200) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("inbox_search"),
                    label = { Text(s(ai.memora.app.R.string.inbox_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
            item(key = "inbox-filters") {
                Filters(
                    options = listOf(
                        "active" to s(ai.memora.app.R.string.inbox_active),
                        "archived" to s(ai.memora.app.R.string.inbox_archived),
                        "all" to s(ai.memora.app.R.string.inbox_all),
                    ),
                    selected = filter,
                    onSelect = { filter = it },
                )
            }
            item(key = "inbox-list-heading") {
                SectionHeading(s(ai.memora.app.R.string.inbox_session), badge = drafts.size.toString())
            }
            if (loading && drafts.isEmpty()) {
                item(key = "inbox-loading") {
                    LoadingState(s(ai.memora.app.R.string.capture_loading), testTag = "inbox_loading")
                }
            } else if (error) {
                item(key = "inbox-error") {
                    MemoraCard(tint = colors.softError, modifier = Modifier.testTag("inbox_error")) {
                        Text(s(ai.memora.app.R.string.capture_storage_error), color = colors.onSoftError,
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_retry")) {
                            Text(s(ai.memora.app.R.string.capture_retry))
                        }
                    }
                }
            } else if (visible.isEmpty()) {
                item(key = "inbox-empty") {
                    EmptyState(
                        title = s(if (drafts.isEmpty()) ai.memora.app.R.string.empty_drafts else ai.memora.app.R.string.inbox_no_matches_title),
                        body = s(if (drafts.isEmpty()) ai.memora.app.R.string.empty_drafts_body else ai.memora.app.R.string.inbox_no_results),
                        icon = Glyph.Inbox,
                        testTag = "inbox_empty",
                    )
                }
            }

            if (managementError) {
                item(key = "inbox-management-error") {
                    MemoraCard(tint = colors.softError, modifier = Modifier.testTag("capture_management_error")) {
                        Text(s(ai.memora.app.R.string.capture_management_error), color = colors.onSoftError,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (importIssue != null) {
                item(key = "inbox-import-error") {
                    MemoraCard(tint = colors.softError, modifier = Modifier.testTag("capture_import_error")) {
                        Text(importIssue, color = colors.onSoftError, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (importStale) {
                item(key = "inbox-import-stale") {
                    MemoraCard(tint = colors.softError, modifier = Modifier.testTag("capture_import_stale")) {
                        Text(s(ai.memora.app.R.string.capture_import_changed), color = colors.onSoftError,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (importDone) {
                item(key = "inbox-import-success") {
                    MemoraCard(tint = colors.softMint, modifier = Modifier.testTag("capture_import_success")) {
                        Text(s(ai.memora.app.R.string.capture_import_success), color = colors.onSoftMint,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (selectedVisible.isNotEmpty()) {
                item(key = "inbox-bulk-actions") {
                    MemoraCard(tint = colors.softAccent, modifier = Modifier.testTag("inbox_bulk_actions")) {
                        Text(
                            LocalStrings.current.getString(ai.memora.app.R.string.inbox_selected, selectedVisible.size),
                            color = colors.onSoftAccent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("inbox_selected"),
                        )
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedButton(
                                onClick = { archive(selectedVisible.map(::archiveSelection), true) },
                                enabled = canManage,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_archive_selected"),
                            ) { Text(s(ai.memora.app.R.string.inbox_archive_action)) }
                            OutlinedButton(
                                onClick = { archive(selectedVisible.map(::archiveSelection), false) },
                                enabled = canManage,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_restore_selected"),
                            ) { Text(s(ai.memora.app.R.string.inbox_restore_action)) }
                        }
                    }
                }
            }

            itemsIndexed(visible, key = { _, entry -> entry.source.id }) { index, entry ->
                val sourceText = entry.source.revisions.last().text
                val prefixLength = sourceText.offsetByCodePoints(0,
                    minOf(sourceText.codePointCount(0, sourceText.length), 80))
                val selectionDescription = s(ai.memora.app.R.string.inbox_select) + ": " + sourceText.substring(0, prefixLength)
                Box(Modifier.fillMaxWidth().testTag("capture_entry_${entry.source.id}")) {
                    MemoraCard(modifier = Modifier.testTag("draft_$index"), contentPadding = 16.dp) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill(
                            s(if (entry.origin == CaptureOrigin.IMPORTED) ai.memora.app.R.string.capture_imported else ai.memora.app.R.string.draft),
                            if (entry.origin == CaptureOrigin.IMPORTED) 0 else 2,
                        )
                        if (entry.archived) Pill(s(ai.memora.app.R.string.inbox_archived), 1)
                        Spacer(Modifier.weight(1f))
                        Checkbox(
                            checked = entry.source.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + entry.source.id else selectedIds - entry.source.id
                            },
                            enabled = canManage,
                            modifier = Modifier.testTag("capture_select_$index").semantics {
                                contentDescription = selectionDescription
                            },
                        )
                    }
                    Text(sourceText, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    Text(s(ai.memora.app.R.string.draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TextButton(
                            onClick = {
                                editingId = entry.source.id
                                editingPrevious = entry.source.revisions.last().id
                                editingText = sourceText
                                pendingRevision = null
                                pendingTime = null
                            },
                            enabled = canManage,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("capture_edit_$index"),
                        ) { Text(s(ai.memora.app.R.string.capture_edit)) }
                        TextButton(
                            onClick = { historyId = entry.source.id },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("capture_history_$index"),
                        ) { Text(s(ai.memora.app.R.string.capture_history)) }
                        TextButton(
                            onClick = { deletingId = entry.source.id },
                            enabled = canManage,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("capture_delete_$index"),
                        ) { Text(s(ai.memora.app.R.string.task_delete)) }
                    }
                    }
                }
            }

            item(key = "inbox-transfer-actions") {
                MemoraCard {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = export,
                            enabled = canManage,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("capture_export"),
                        ) { Text(s(ai.memora.app.R.string.capture_export)) }
                        OutlinedButton(
                            onClick = importFile,
                            enabled = canManage,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("capture_import"),
                        ) { Text(s(ai.memora.app.R.string.capture_import)) }
                    }
                }
            }
        }
    }

    if (editing != null) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) { editingId = null; pendingRevision = null; pendingTime = null } },
            title = { Text(s(ai.memora.app.R.string.capture_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s(ai.memora.app.R.string.capture_edit_hint))
                    OutlinedTextField(
                        editingText,
                        { if (TextSourceRules.validDraft(it)) editingText = it },
                        enabled = !managementBusy && pendingRevision == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp).testTag("capture_edit_text"),
                        label = { Text(s(ai.memora.app.R.string.capture_label)) },
                    )
                    if (managementError) Text(s(ai.memora.app.R.string.capture_edit_error),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capture_edit_error"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = pendingRevision ?: UUID.randomUUID().toString().also { pendingRevision = it }
                        val at = pendingTime ?: Instant.now().toString().also { pendingTime = it }
                        revise(CaptureRevisionRequest(editing.source.id, editingPrevious, id, editingText, Instant.parse(at)))
                    },
                    enabled = canManage && !managementBusy && editingText.isNotBlank() &&
                        (pendingRevision != null || editingText != editing.source.revisions.last().text),
                    modifier = Modifier.testTag("capture_edit_save"),
                ) { Text(s(if (pendingRevision == null) ai.memora.app.R.string.task_save else ai.memora.app.R.string.capture_retry)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingId = null; pendingRevision = null; pendingTime = null },
                    enabled = !managementBusy,
                ) { Text(s(ai.memora.app.R.string.task_cancel)) }
            },
        )
    }

    if (history != null) {
        AlertDialog(
            onDismissRequest = { historyId = null },
            title = { Text(s(ai.memora.app.R.string.capture_history)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s(ai.memora.app.R.string.capture_history_hint))
                    history.source.revisions.asReversed().forEach { revision ->
                        Text(LocalStrings.current.getString(ai.memora.app.R.string.capture_revision_number, revision.number),
                            style = MaterialTheme.typography.titleSmall)
                        Text(revision.text, modifier = Modifier.testTag("capture_revision_${revision.number}"))
                        Text(revision.recordedAt.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyId = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }

    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(s(ai.memora.app.R.string.task_delete)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s(ai.memora.app.R.string.capture_delete_confirm))
                    Text(s(ai.memora.app.R.string.capture_delete_history_warning))
                    Text(deleting.source.revisions.last().text, modifier = Modifier.testTag("capture_delete_target"))
                }
            },
            confirmButton = {
                TextButton(onClick = { deletingId = null; delete(deleting) }, modifier = Modifier.testTag("capture_delete_confirm")) {
                    Text(s(ai.memora.app.R.string.task_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(s(ai.memora.app.R.string.task_cancel)) } },
        )
    }
}

private fun archiveSelection(entry: CaptureEntry) = CaptureArchiveSelection(
    sourceId = entry.source.id,
    revisionId = entry.source.revisions.last().id,
    organizationVersion = entry.organizationVersion,
)

package ai.memora.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.launch

/** Read-only view of the same encrypted people/projects written through the agent tool registry. */
@Composable
internal fun SavedObjectsScreen(kind: DomainKind) {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val database = remember(context) { AgentDatabase.open(context) }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    val coroutineScope = rememberCoroutineScope()
    var records by remember(kind) { mutableStateOf<List<DomainObjectRow>>(emptyList()) }
    var query by remember(kind) { mutableStateOf("") }
    var selected by remember { mutableStateOf<DomainObjectRow?>(null) }
    var loading by remember(kind) { mutableStateOf(true) }
    var failed by remember(kind) { mutableStateOf(false) }

    fun refresh() {
        coroutineScope.launch {
            loading = true
            try {
                records = repository.domainObjects(scopeId, kind)
                failed = false
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(scopeId, kind) { refresh() }

    val filtered = records.filter { record ->
        val needle = query.trim().lowercase()
        needle.isEmpty() || record.title.lowercase().contains(needle) || record.body.orEmpty().lowercase().contains(needle)
    }

    ScreenColumn {
        val titleId = when (kind) {
            DomainKind.PERSON -> ai.memora.app.R.string.people
            DomainKind.PROJECT -> ai.memora.app.R.string.projects
            DomainKind.NOTE -> ai.memora.app.R.string.notes_title
        }
        ScreenHeading(s(titleId),
            s(ai.memora.app.R.string.saved_objects_subtitle))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag("saved_objects_search"),
            label = { Text(s(ai.memora.app.R.string.saved_objects_search)) },
            singleLine = true,
        )
        when {
            loading -> Text(s(ai.memora.app.R.string.ai_loading), color = colors.muted)
            failed -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_load_error))
            records.isEmpty() -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_empty))
            filtered.isEmpty() -> EmptyState(s(ai.memora.app.R.string.working_no_data), s(ai.memora.app.R.string.saved_objects_no_matches))
            else -> filtered.forEach { record ->
                MemoraCard(
                    onClick = { selected = record },
                    modifier = Modifier.testTag("saved_object_${record.objectId}"),
                ) {
                    Text(record.title, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    record.body?.takeIf(String::isNotBlank)?.let { body ->
                        Text(body.take(240) + if (body.length > 240) "…" else "", color = colors.muted,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    }
                    if (record.status == "archived") Text(s(ai.memora.app.R.string.saved_objects_archived), color = colors.muted,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    selected?.let { record ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(record.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val body = record.body?.takeIf(String::isNotBlank)
                    Text(body?.take(20_000) ?: s(ai.memora.app.R.string.saved_objects_no_details), color = colors.ink)
                    if (body != null && body.length > 20_000) {
                        Text(s(ai.memora.app.R.string.saved_objects_details_truncated), modifier = Modifier.padding(top = 8.dp), color = colors.muted)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(s(ai.memora.app.R.string.close)) } },
        )
    }
}

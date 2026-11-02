package ai.memora.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.LocalCalendarEventRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor

/** Visible view over the same local calendar that the assistant tools read and edit. */
@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(context) { ProtectedDomainRepository(AgentDatabase.open(context)) }
    var events by remember { mutableStateOf<List<LocalCalendarEventRow>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(scopeId, reload) {
        try {
            events = repository.allCalendarEvents(scopeId).takeLast(200).asReversed()
            unavailable = false
        } catch (_: Exception) { unavailable = true }
    }

    ScreenColumn {
        ScreenHeading(s(R.string.calendar_title), s(R.string.calendar_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { reload++ }) { Text(s(R.string.calendar_refresh)) }
        }
        when {
            unavailable -> MemoraCard { Text(s(R.string.capture_storage_error)) }
            events == null -> MemoraCard { Text(s(R.string.prototype_loading)) }
            events!!.isEmpty() -> MemoraCard { Text(s(R.string.calendar_empty)) }
            else -> events!!.forEach { event ->
                MemoraCard {
                    Text(event.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(event.startLocal + (event.endLocal?.let { " – $it" } ?: ""))
                    Text(event.zoneId, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    if (event.status != "active") Text(s(R.string.calendar_cancelled),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

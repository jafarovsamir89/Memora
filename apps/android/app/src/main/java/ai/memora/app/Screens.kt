package ai.memora.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.ui.*
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.domain.TextSourceRules



@Composable
fun Filters(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
fun MoreScreen(navigate: (String) -> Unit, about: () -> Unit, captureVoice: (() -> Unit)? = null, language: String = "az") {
    ScreenColumn {
        Text(s(R.string.more_subtitle), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        MoreGroupHeading(s(R.string.more_group_people_work), "more_group_people_work")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Folder, s(R.string.cases), s(R.string.cases_hint),
                testTag = "more_cases") { navigate("cases") }
            MoreDestinationCard(Glyph.Person, s(R.string.people), s(R.string.people_hint), tone = 1,
                testTag = "more_people") { navigate("people") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Tasks, s(R.string.nav_tasks), s(R.string.working_tasks_hint),
                testTag = "more_tasks") { navigate("tasks") }
            MoreDestinationCard(Glyph.Folder, s(R.string.projects), s(R.string.projects_hint), tone = 3,
                testTag = "more_projects") { navigate("projects") }
        }
        MoreGroupHeading(s(R.string.more_group_planning_memory), "more_group_planning_memory")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Calendar, s(R.string.calendar_title), s(R.string.calendar_hint), tone = 1,
                testTag = "more_calendar") { navigate("calendar") }
            MoreDestinationCard(Glyph.Note, s(R.string.notes_title), s(R.string.notes_hint),
                testTag = "more_notes") { navigate("notes") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Inbox, s(R.string.inbox), s(R.string.inbox_hint), tone = 1,
                testTag = "more_inbox") { navigate("inbox") }
            MoreDestinationCard(Glyph.Mic, s(R.string.voice_memory_title), s(R.string.voice_memory_help),
                testTag = "more_voice_memory") { navigate("voice_memory") }
        }
        MoreGroupHeading(s(R.string.more_group_data_settings), "more_group_data_settings")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Grid, s(R.string.tables_title), s(R.string.tables_subtitle),
                testTag = "more_tables") { navigate("tables") }
            MoreDestinationCard(Glyph.Shield, s(R.string.backup_title), s(R.string.backup_subtitle), tone = 1,
                testTag = "more_portable_backup") { navigate("portable_backup") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MoreDestinationCard(Glyph.Wallet, s(R.string.finance), s(R.string.finance_hint), tone = 3,
                testTag = "more_finance") { navigate("finance") }
            MoreDestinationCard(Glyph.Settings, s(R.string.settings), s(R.string.settings_hint), tone = 3,
                testTag = "more_settings") { navigate("settings") }
        }
        if (captureVoice != null) MemoraCard {
            MenuRow(Glyph.Mic, s(R.string.nav_capture), s(R.string.working_capture_hint), tone = 1,
                testTag = "more_voice_capture", onClick = captureVoice)
        }
        MemoraCard(tint = colors.softMint, onClick = about, modifier = Modifier.testTag("more_privacy")) {
            Icon(Glyph.Shield, null, tint = colors.green)
            Text(s(R.string.private_title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(s(R.string.private_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        SectionHeading(s(R.string.finance_reports_title))
        FinanceReportsSection(language)
    }
}

@Composable
private fun MoreGroupHeading(title: String, testTag: String) {
    Column(Modifier.fillMaxWidth().testTag(testTag)) { SectionHeading(title) }
}





@Composable
fun SettingsScreen(theme: String, changeTheme: (String) -> Unit, language: String, changeLanguage: (String) -> Unit, ready: Boolean, error: Boolean, retry: () -> Unit, about: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceConsent = remember(context) { context.getSharedPreferences("voice-first-consent", 0) }
    var granted by remember { mutableStateOf(voiceConsent.all.values.any { it == true }) }
    ScreenColumn {
        Text(s(R.string.settings_subtitle), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        if (error) {
            Text(s(R.string.settings_storage_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings_storage_error"))
            OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).testTag("settings_retry")) { Text(s(R.string.settings_retry)) }
        }
        MemoraCard(contentPadding = 16.dp) { GeminiAgentModelSettings() }
        MemoraCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(s(R.string.appearance))
                Filters(listOf("system" to s(R.string.theme_system), "light" to s(R.string.theme_light), "dark" to s(R.string.theme_dark)), theme, changeTheme, enabled = ready)
                SectionHeading(s(R.string.language))
                Filters(listOf("ru" to "Русский", "az" to "Azərbaycan", "en" to "English"), language, changeLanguage, enabled = ready)
            }
        }
        MemoraCard(contentPadding = 16.dp) { AttentionSettingsControls() }
        MemoraCard(contentPadding = 16.dp) { DevicePermissionSettings() }
        AppCheckDebugSettings()
        if (granted) OutlinedButton(onClick = {
            if (voiceConsent.edit().clear().commit()) granted = false
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("voice_revoke_consent")) { Text(s(R.string.voice_revoke_consent)) }
        MemoraCard {
            MenuRow(Glyph.Shield, s(R.string.privacy), s(R.string.privacy_hint), 1, onClick = about)
            MenuRow(Glyph.Spark, s(R.string.about), s(R.string.version), onClick = about)
        }
    }
}

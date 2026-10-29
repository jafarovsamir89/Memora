package ai.memora.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.memora.app.ui.*

@Composable
fun CaptureSheet(voice: Boolean, setVoice: (Boolean) -> Unit, draft: String, setDraft: (String) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.new_memory), style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Glyph.Close, s(R.string.close), tint = colors.muted) }
        }
        Filters(listOf("text" to s(R.string.text_tab), "voice" to s(R.string.voice_tab)), if (voice) "voice" else "text", { setVoice(it == "voice") })
        if (voice) {
            Waveform(Modifier.padding(vertical = 12.dp))
            Text(s(R.string.voice_title), style = MaterialTheme.typography.titleLarge, color = colors.ink)
            Text(s(R.string.voice_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            Button({ setVoice(false) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s(R.string.go_text)) }
        } else {
            OutlinedTextField(
                value = draft, onValueChange = { if (it.length <= 2000) setDraft(it) },
                modifier = Modifier.fillMaxWidth().testTag("capture_input"),
                label = { Text(s(R.string.capture_label)) }, placeholder = { Text(s(R.string.capture_placeholder)) },
                minLines = 4, maxLines = 8, shape = RoundedCornerShape(20.dp),
                supportingText = { Text("${draft.length}/2000") },
            )
            Text(s(R.string.draft_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Button(onSave, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("save_draft"), enabled = draft.isNotBlank()) {
                Icon(Glyph.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s(R.string.add_draft))
            }
        }
    }
}

@Composable
fun MemoryDetail(item: ExampleMemory, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(s(item.category), item.tone)
            Spacer(Modifier.weight(1f))
            IconButton(onClose) { Icon(Glyph.Close, s(R.string.close), tint = colors.muted) }
        }
        Text(s(item.title), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
        Text(s(item.body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
        Text(s(R.string.time_recent), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        MemoraCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Note, null, Modifier.size(19.dp), tint = colors.accent)
                Text(s(R.string.source), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            }
            Text(s(item.source), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            Text(s(R.string.source_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(s(R.string.sample_only), style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
fun ChatScreen() {
    var question by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    ScreenColumn {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { IconBubble(Glyph.Spark, size = 72) }
        Text(s(R.string.chat_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium,
            color = colors.ink, textAlign = TextAlign.Center)
        Text(s(R.string.chat_body), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
            color = colors.muted, textAlign = TextAlign.Center)
        val one = s(R.string.suggestion_one)
        val two = s(R.string.suggestion_two)
        OutlinedButton({ question = one }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(one) }
        OutlinedButton({ question = two }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(two) }
        if (submitted.isNotBlank()) {
            MemoraCard(tint = colors.softAccent) { Text(submitted, color = colors.ink, style = MaterialTheme.typography.bodyLarge) }
            MemoraCard { Text(s(R.string.chat_unavailable), color = colors.muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(question, { if (it.length <= 2000) question = it }, Modifier.weight(1f).testTag("chat_input"),
                label = { Text(s(R.string.question)) }, shape = RoundedCornerShape(20.dp), maxLines = 4)
            FilledIconButton({ submitted = question.trim(); question = "" }, Modifier.size(52.dp).testTag("chat_send"), enabled = question.isNotBlank()) {
                Icon(Glyph.Arrow, s(R.string.send))
            }
        }
    }
}

package ai.memora.app.voice

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.memora.app.R
import ai.memora.app.ui.s
import java.util.Locale

/** Uses the system TTS only after a user action; availability depends on the device. */
@Composable
fun ResponseSpeech(text: String, language: String) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var active = true
        val instance = TextToSpeech(context) { status ->
            if (active) { ready = status == TextToSpeech.SUCCESS; failed = !ready }
        }
        engine = instance
        onDispose { active = false; instance.stop(); instance.shutdown(); engine = null }
    }
    Text(s(R.string.ai_tts_notice))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val tts = engine
            val locale = VoiceLanguagePolicy.locale(language)
            val languageStatus = tts?.setLanguage(locale)
            failed = !ready || tts == null || languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
            // Some engines accept a locale but silently select a different language.
            if (!failed) failed = !VoiceLanguagePolicy.acceptsVoice(language, tts?.voice?.locale)
            if (!failed) failed = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "memora-response") != TextToSpeech.SUCCESS
        }, modifier = Modifier.testTag("response_speak"), enabled = ready) { Text(s(R.string.ai_speak)) }
        OutlinedButton(onClick = { engine?.stop() }, modifier = Modifier.testTag("response_stop")) { Text(s(R.string.ai_stop_speech)) }
    }
    if (failed) Text(s(R.string.ai_tts_unavailable))
}

package ai.memora.app.voice

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceFirstScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        context.getSharedPreferences("voice-first-consent", 0).edit().clear().commit()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @Test fun consentThenHoldAndReleaseSendsExactlyOneTurn() {
        var recordings = 0
        var transmissions = 0
        var answers = 0
        val stopped = CompletableDeferred<Unit>()
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { recordings++; stopped.await(); return byteArrayOf(1, 2) }
            override fun stop() { stopped.complete(Unit) }
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray): String { transmissions++; return "synthetic statement" }
        }
        val processor = object : VoiceTurnProcessor {
            override suspend fun handle(transcript: String) = VoiceFirstResult.Ready("synthetic answer", true)
        }
        val speech = object : SpeechOutput { override suspend fun speak(text: String) { answers++ } }
        compose.setContent { CompositionLocalProvider(LocalStrings provides context.resources) {
            MemoraTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceFirstScreen(gateway, recorder, processor, speech) } }
        } }
        compose.onNodeWithTag("voice_first_button").performClick()
        compose.onNodeWithTag("voice_first_consent").assertExists()
        assertEquals(0, recordings); assertEquals(0, transmissions)
        compose.onNodeWithTag("voice_first_consent").performClick()
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center) }
        compose.waitUntil(5000) { recordings == 1 }
        assertEquals(0, transmissions)
        compose.onNodeWithTag("voice_first_button").performTouchInput { up() }
        compose.waitUntil(5000) { answers == 1 }
        assertEquals(1, transmissions)
        compose.onNodeWithTag("voice_first_answer").assertTextContains("synthetic answer")
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center) }
        compose.waitUntil(5000) { recordings == 2 }
        compose.onNodeWithTag("voice_first_consent").assertDoesNotExist()
        assertEquals(1, transmissions)
        compose.onNodeWithTag("voice_first_button").performTouchInput { up() }
        compose.waitUntil(5000) { transmissions == 2 }
    }
}

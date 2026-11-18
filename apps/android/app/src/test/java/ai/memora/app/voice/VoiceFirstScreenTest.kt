package ai.memora.app.voice

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
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
        assertFalse(context.getSharedPreferences("voice-first-consent", 0)
            .getBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, false))
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
        compose.onNodeWithTag("voice_first_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice_first_consent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice_first_consent").assertExists()
        assertEquals(0, recordings); assertEquals(0, transmissions)
        compose.onNodeWithTag("voice_first_consent").performClick()
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center); advanceEventTime(600) }
        compose.waitUntil(5000) { recordings == 1 }
        assertEquals(0, transmissions)
        compose.onNodeWithTag("voice_first_button").performTouchInput { up() }
        compose.waitUntil(5000) { answers == 1 }
        assertEquals(1, transmissions)
        compose.onNodeWithTag("voice_first_answer").assertTextContains("synthetic answer")
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center); advanceEventTime(600) }
        compose.waitUntil(5000) { recordings == 2 }
        compose.onNodeWithTag("voice_first_consent").assertDoesNotExist()
        assertEquals(1, transmissions)
        compose.onNodeWithTag("voice_first_button").performTouchInput { up() }
        compose.waitUntil(5000) { transmissions == 2 }
    }

    @Test fun agentFailureDisplaysSafeDiagnosticCode() {
        val stopped = CompletableDeferred<Unit>()
        var recordingStarted = false
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { recordingStarted = true; stopped.await(); return byteArrayOf(1, 2) }
            override fun stop() { stopped.complete(Unit) }
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray) = "synthetic statement"
        }
        val processor = object : VoiceTurnProcessor {
            override suspend fun handle(transcript: String) = VoiceFirstResult.Failed("model_permission_denied")
        }
        val speech = object : SpeechOutput { override suspend fun speak(text: String) { fail("failed turn must not be spoken") } }
        compose.setContent { CompositionLocalProvider(LocalStrings provides context.resources) {
            MemoraTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { VoiceFirstScreen(gateway, recorder, processor, speech) } }
        } }
        compose.onNodeWithTag("voice_first_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice_first_consent").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice_first_consent").performClick()
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center); advanceEventTime(600) }
        compose.waitUntil(5_000) { recordingStarted }
        compose.onNodeWithTag("voice_first_button").performTouchInput { up() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("model_permission_denied", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("voice_first_status").assertTextContains("model_permission_denied", substring = true)
    }

    @Test fun deniedMicrophoneDoesNotReachAudioOrTranscriptionPorts() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        context.getSharedPreferences("voice-first-consent", 0).edit()
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, true).commit()
        var recordings = 0
        var transcriptions = 0
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { recordings++; return byteArrayOf(1, 2) }
            override fun stop() {}
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray): String { transcriptions++; return "synthetic" }
        }
        val processor = object : VoiceTurnProcessor {
            override suspend fun handle(transcript: String) = VoiceFirstResult.Ready("synthetic", false)
        }
        val speech = object : SpeechOutput { override suspend fun speak(text: String) {} }
        compose.setContent { CompositionLocalProvider(LocalStrings provides context.resources) {
            MemoraTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
                VoiceFirstScreen(gateway, recorder, processor, speech)
            } }
        } }

        compose.onNodeWithTag("voice_first_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals("Denied permission must not start microphone capture", 0, recordings)
        assertEquals("Denied permission must not send audio for transcription", 0, transcriptions)
    }

    @Test fun shortPhysicalTapDoesNotStartRecordingButAccessibleToggleDoes() {
        context.getSharedPreferences("voice-first-consent", 0).edit()
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, true).commit()
        var recordings = 0
        val stopped = CompletableDeferred<Unit>()
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { recordings++; stopped.await(); return byteArrayOf(1, 2) }
            override fun stop() { stopped.complete(Unit) }
        }
        val gateway = object : AudioNoteGateway { override suspend fun transcribe(wav: ByteArray) = "synthetic tap test" }
        val processor = object : VoiceTurnProcessor { override suspend fun handle(transcript: String) = VoiceFirstResult.Ready("synthetic answer", false) }
        val speech = object : SpeechOutput { override suspend fun speak(text: String) {} }
        var shortTaps = 0
        compose.setContent { CompositionLocalProvider(LocalStrings provides context.resources) {
            MemoraTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
                VoiceFirstScreen(gateway, recorder, processor, speech, onShortTap = { shortTaps++ })
            } }
        } }
        compose.onNodeWithTag("voice_first_button").performTouchInput { down(center); advanceEventTime(100); up() }
        assertEquals("A short touch should leave the microphone idle", 0, recordings)
        assertEquals("A short touch should focus the text alternative", 1, shortTaps)
        compose.onNodeWithTag("voice_first_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { recordings == 1 }
        compose.onNodeWithTag("voice_first_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice_first_answer").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun cancellingLongPressStopsCaptureBeforeTranscription() {
        context.getSharedPreferences("voice-first-consent", 0).edit()
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, true).commit()
        val releaseRecorder = CompletableDeferred<Unit>()
        var recordingStarted = false
        var recorderStopped = false
        var transcriptions = 0
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { recordingStarted = true; releaseRecorder.await(); return byteArrayOf(1, 2) }
            override fun stop() { recorderStopped = true; releaseRecorder.complete(Unit) }
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray): String { transcriptions++; return "synthetic cancelled phrase" }
        }
        val processor = object : VoiceTurnProcessor {
            override suspend fun handle(transcript: String) = VoiceFirstResult.Ready("unexpected", false)
        }
        val speech = object : SpeechOutput { override suspend fun speak(text: String) { fail("cancelled speech must not be produced") } }
        compose.setContent { CompositionLocalProvider(LocalStrings provides context.resources) {
            MemoraTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
                VoiceFirstScreen(gateway, recorder, processor, speech)
            } }
        } }
        compose.onNodeWithTag("voice_first_button").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(androidx.compose.ui.geometry.Offset(500f, 500f))
            up()
        }
        compose.waitUntil(5_000) { recordingStarted && recorderStopped }
        assertEquals("A cancelled hold must not send captured audio for transcription", 0, transcriptions)
    }
}

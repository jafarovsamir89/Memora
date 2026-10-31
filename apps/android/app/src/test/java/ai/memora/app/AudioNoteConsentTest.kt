package ai.memora.app

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioNoteConsentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consentThenEditableReviewAndExplicitApplyPreserveExistingDraft() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var records = 0; var calls = 0; var draft = "Əvvəlki qeyd"
        val audio = ByteArray(48) { 1 }
        val finish = CompletableDeferred<Unit>()
        val recorder = object : AudioNoteRecorder {
            override suspend fun record(): ByteArray { records++; finish.await(); return audio }
            override fun stop() { finish.complete(Unit) }
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray): String { calls++; return "İlqar, 120 deyil, 20 manat. Завтра." }
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { AudioNoteInput(draft, { draft = it }, {}, gateway, recorder) }
            }
        }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_decline").performClick()
        compose.runOnIdle { assertEquals(0, records); assertEquals(0, calls) }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_confirm").performClick()
        compose.onNodeWithTag("audio_note_finish").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("audio_note_review").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals("Əvvəlki qeyd", draft); assertEquals(1, calls) }
        compose.onNodeWithTag("audio_note_review").performTextReplacement("İlqar, 20 manat. Завтра.")
        compose.onNodeWithTag("audio_note_apply").performClick()
        compose.runOnIdle { assertEquals("Əvvəlki qeyd\nİlqar, 20 manat. Завтра.", draft); assertTrue(audio.all { it == 0.toByte() }) }
    }

    @Test fun cancellationWhileRecordingNeverSendsAudio() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var calls = 0; var stops = 0
        val pending = CompletableDeferred<ByteArray>()
        val recorder = object : AudioNoteRecorder {
            override suspend fun record() = pending.await()
            override fun stop() { stops++ }
        }
        val gateway = object : AudioNoteGateway { override suspend fun transcribe(wav: ByteArray): String { calls++; return "unused" } }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { AudioNoteInput("", {}, {}, gateway, recorder) }
            }
        }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_confirm").performClick()
        compose.onNodeWithTag("audio_note_cancel").performClick()
        compose.runOnIdle { pending.complete(ByteArray(48)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, calls); assertTrue(stops > 0) }
        compose.onNodeWithTag("audio_note_review").assertDoesNotExist()
    }

    @Test fun writingSuggestionRetainsOriginalAndCanBeRevertedBeforeExplicitApply() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val raw = "Sabah pozvoni Leyle, 20 manat değil."
        val suggested = "Sabah позвони Leyle, 20 manat deyil."
        var draft = ""; var suggestions = 0
        val recorder = object : AudioNoteRecorder {
            override suspend fun record() = ByteArray(48)
            override fun stop() = Unit
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray) = raw
            override suspend fun suggestWriting(raw: String): String { suggestions++; return suggested }
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { AudioNoteInput(draft, { draft = it }, {}, gateway, recorder) }
            }
        }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("audio_note_review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio_note_original").assertTextEquals(raw)
        compose.onNodeWithTag("audio_note_review").assertTextContains(suggested)
        compose.runOnIdle { assertEquals("", draft); assertEquals(1, suggestions) }
        compose.onNodeWithTag("audio_note_use_original").performClick()
        compose.onNodeWithTag("audio_note_review").assertTextContains(raw)
        compose.onNodeWithTag("audio_note_apply").performClick()
        compose.runOnIdle { assertEquals(raw, draft) }
    }

    @Test fun cancellingDuringWritingDiscardsTheLateSuggestion() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val pending = CompletableDeferred<String>()
        var entered = false; var draft = "unchanged"
        val audio = ByteArray(48) { 1 }
        val recorder = object : AudioNoteRecorder {
            override suspend fun record() = audio
            override fun stop() = Unit
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray) = "Sabah."
            override suspend fun suggestWriting(raw: String): String { entered = true; return pending.await() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { AudioNoteInput(draft, { draft = it }, {}, gateway, recorder) }
            }
        }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_confirm").performClick()
        compose.waitUntil(10_000) { entered }
        compose.onNodeWithTag("audio_note_cancel").performClick()
        compose.runOnIdle { pending.complete("late") }
        compose.waitForIdle()
        compose.onNodeWithTag("audio_note_review").assertDoesNotExist()
        compose.runOnIdle { assertEquals("unchanged", draft); assertTrue(audio.all { it == 0.toByte() }) }
    }

    @Test fun writingServiceFailureRetainsTheRecognizedTextForReview() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val raw = "Sabah 20 manat deyil, 30 manat."
        var draft = ""
        val recorder = object : AudioNoteRecorder {
            override suspend fun record() = ByteArray(48)
            override fun stop() = Unit
        }
        val gateway = object : AudioNoteGateway {
            override suspend fun transcribe(wav: ByteArray) = raw
            override suspend fun suggestWriting(raw: String): String = error("Synthetic service failure")
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { AudioNoteInput(draft, { draft = it }, {}, gateway, recorder) }
            }
        }
        compose.onNodeWithTag("audio_note_start").performClick()
        compose.onNodeWithTag("audio_note_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("audio_note_review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio_note_review").assertTextContains(raw)
        compose.runOnIdle { assertEquals("", draft) }
        compose.onNodeWithTag("audio_note_apply").performClick()
        compose.runOnIdle { assertEquals(raw, draft) }
    }
}

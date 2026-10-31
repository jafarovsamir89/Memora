package ai.memora.app

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.CloudVoiceGateway
import ai.memora.app.voice.CloudVoiceHandle
import ai.memora.app.voice.CloudVoiceInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ru-w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CloudVoiceConsentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun audioDoesNotStartBeforeConsentAndStopReleasesSession() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var connects = 0
        var starts = 0
        var stops = 0
        var closes = 0
        val handle = object : CloudVoiceHandle {
            override suspend fun begin() { starts++ }
            override fun stop() { stops++ }
            override suspend fun close() { closes++ }
            override fun isClosed() = false
        }
        val gateway = object : CloudVoiceGateway {
            override suspend fun connect(onTranscript: (String?, String?) -> Unit): CloudVoiceHandle { connects++; return handle }
        }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { CloudVoiceInput(gateway) } }
        }
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_cancel").performClick()
        compose.runOnIdle { assertEquals(0, connects); assertEquals(0, starts) }
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cloud_voice_stop").assertExists().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, starts); assertEquals(1, stops); assertEquals(1, closes) }
        compose.onNodeWithTag("cloud_voice_start").assertExists()
    }

    @Test fun leavingScreenStopsAndClosesActiveSession() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var stops = 0
        var closes = 0
        val show = mutableStateOf(true)
        val handle = object : CloudVoiceHandle {
            override suspend fun begin() = Unit
            override fun stop() { stops++ }
            override suspend fun close() { closes++ }
            override fun isClosed() = false
        }
        val gateway = object : CloudVoiceGateway { override suspend fun connect(onTranscript: (String?, String?) -> Unit) = handle }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { if (show.value) CloudVoiceInput(gateway) } }
        }
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_confirm").performClick()
        compose.onNodeWithTag("cloud_voice_stop").assertExists()
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(1, closes) }
    }

    @Test fun stoppingWhileConnectingNeverStartsLateMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var starts = 0
        val pending = CompletableDeferred<CloudVoiceHandle>()
        val handle = object : CloudVoiceHandle {
            override suspend fun begin() { starts++ }
            override fun stop() = Unit
            override suspend fun close() = Unit
            override fun isClosed() = false
        }
        val gateway = object : CloudVoiceGateway { override suspend fun connect(onTranscript: (String?, String?) -> Unit) = pending.await() }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { CloudVoiceInput(gateway) } }
        }
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_confirm").performClick()
        compose.onNodeWithTag("cloud_voice_stop").performClick()
        compose.runOnIdle { pending.complete(handle) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("cloud_voice_start").assertExists()
    }

    @Test fun immediateConnectionFailureAllowsRetryWithNewConsent() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var attempts = 0
        val gateway = object : CloudVoiceGateway {
            override suspend fun connect(onTranscript: (String?, String?) -> Unit): CloudVoiceHandle {
                attempts++
                error("Synthetic connection failure")
            }
        }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { CloudVoiceInput(gateway) } }
        }
        repeat(2) {
            compose.onNodeWithTag("cloud_voice_start").performClick()
            compose.onNodeWithTag("cloud_voice_confirm").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("cloud_voice_start").assertExists()
        }
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun lateTranscriptAfterStopIsNotShownOrCarriedIntoNextSession() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var callback: ((String?, String?) -> Unit)? = null
        val handle = object : CloudVoiceHandle {
            override suspend fun begin() = Unit
            override fun stop() = Unit
            override suspend fun close() = Unit
            override fun isClosed() = false
        }
        val gateway = object : CloudVoiceGateway {
            override suspend fun connect(onTranscript: (String?, String?) -> Unit): CloudVoiceHandle {
                callback = onTranscript
                return handle
            }
        }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { CloudVoiceInput(gateway) } }
        }
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_confirm").performClick()
        compose.runOnIdle { callback?.invoke("first test", null) }
        compose.onNodeWithTag("cloud_voice_input_transcript").assertTextContains("first test", substring = true)
        val oldCallback = callback
        compose.onNodeWithTag("cloud_voice_stop").performClick()
        compose.waitForIdle()
        compose.runOnIdle { oldCallback?.invoke("late test", null) }
        compose.onNodeWithTag("cloud_voice_input_transcript").assertTextContains("first test", substring = true)
        compose.onNodeWithText("late test", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("cloud_voice_start").performClick()
        compose.onNodeWithTag("cloud_voice_confirm").performClick()
        compose.runOnIdle { oldCallback?.invoke("late test", null) }
        compose.onNodeWithTag("cloud_voice_input_transcript").assertDoesNotExist()
        compose.onNodeWithTag("cloud_voice_stop").performClick()
    }
}

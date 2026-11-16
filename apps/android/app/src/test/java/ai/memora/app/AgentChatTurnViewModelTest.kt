package ai.memora.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstResult
import ai.memora.app.voice.VoiceTurnActionResult
import ai.memora.app.voice.VoiceTurnProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentChatTurnViewModelTest {
    @Before fun useImmediateMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restoreMainDispatcher() { Dispatchers.resetMain() }

    @Test fun voiceTurnContinuesAfterSecretaryCompositionStopsAwaitingIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AgentChatTurnViewModel(AgentVoiceTurnProcessor(context))
        val releaseResponse = CompletableDeferred<Unit>()
        val caller = launch {
            model.handleVoice("synthetic voice request", onProgress = {}) { _, report ->
                report(VoiceTurnProgress.Stage("responding"))
                releaseResponse.await()
                VoiceFirstResult.Ready("synthetic voice response", false)
            }
        }
        withTimeout(5_000) {
            while (!model.state.value.busy || model.state.value.stage != "responding") delay(1)
        }

        caller.cancelAndJoin()
        assertTrue(model.state.value.busy)
        releaseResponse.complete(Unit)
        withTimeout(5_000) {
            while (model.state.value.busy || model.state.value.completionVersion != 1) delay(1)
        }
        assertFalse(model.state.value.busy)
        assertEquals(1, model.state.value.completionVersion)
        assertEquals(null, model.state.value.failureCode)
    }

    @Test fun verifiedWriteRemainsVisibleInsteadOfRestoringAResendDraftAfterReplyFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = AgentChatTurnViewModel(AgentVoiceTurnProcessor(context))
        val turnId = java.util.UUID.randomUUID().toString()
        val resultId = java.util.UUID.randomUUID().toString()
        model.sendText("synthetic write request") { report ->
            report(VoiceTurnProgress.ConfirmedActions(turnId, listOf(VoiceTurnActionResult(
                resultId = resultId,
                turnId = turnId,
                toolName = "record_money_event",
                status = "APPLIED",
                code = "receipt_recorded",
                targetKind = "finance",
                targetId = java.util.UUID.randomUUID().toString(),
            ))))
            VoiceFirstResult.Failed("reply_timeout_after_write")
        }

        withTimeout(5_000) {
            while (model.state.value.busy || model.state.value.failureCode == null) delay(1)
        }
        assertEquals("synthetic write request", model.state.value.pendingMessage)
        assertEquals(turnId, model.state.value.turnId)
        assertEquals(resultId, model.state.value.actions.single().resultId)
        assertEquals(null, model.state.value.failedDraft)
        assertEquals(0, model.state.value.completionVersion)
    }
}

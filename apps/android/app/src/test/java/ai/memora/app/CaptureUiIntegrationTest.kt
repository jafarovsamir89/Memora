package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.CapturePersistence
import ai.memora.app.data.CaptureRead
import ai.memora.app.data.CaptureWrite
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.LocalCapturePersistence
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ru-w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureUiIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private fun disk(): CapturePersistence {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return LocalCapturePersistence(EncryptedCaptureStore(File(context.cacheDir, "capture-ui-${UUID.randomUUID()}.bin")) { key })
    }
    private fun awaitReady() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("capture_input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun persistedDraftAndAcceptedEntrySurviveNewViewModel() {
        val disk = disk()
        val owner = mutableStateOf(Owner())
        val showInbox = mutableStateOf(false)
        compose.setContent {
            key(owner.value) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner.value) {
                    MemoraApp(initialRoute = if (showInbox.value) "inbox" else "home", capturePersistenceOverride = disk)
                }
            }
        }
        compose.onNodeWithTag("capture_text").performClick()
        awaitReady()
        compose.onNodeWithTag("capture_input").performTextInput("synthetic durable text")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("capture_input").fetchSemanticsNodes()
            (disk.load() as? CaptureRead.Ready)?.snapshot?.draft == "synthetic durable text"
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.value.viewModelStore.clear(); owner.value = Owner() }
        compose.onNodeWithTag("capture_text").performClick()
        awaitReady()
        compose.onNodeWithTag("capture_input").assertTextContains("synthetic durable text")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { owner.value.viewModelStore.clear(); showInbox.value = true; owner.value = Owner() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("synthetic durable text").assertIsDisplayed()
        compose.runOnIdle { owner.value.viewModelStore.clear() }
    }

    @Test fun failedAutosaveKeepsTextAndAllowsExplicitRetry() {
        val disk = disk()
        val fail = AtomicBoolean(true)
        val unreliable = object : CapturePersistence by disk {
            override fun saveDraft(expectedVersion: Long, text: String): CaptureWrite {
                return if (fail.get()) CaptureWrite.Unavailable else disk.saveDraft(expectedVersion, text)
            }
        }
        val owner = Owner()
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { MemoraApp(capturePersistenceOverride = unreliable) }
        }
        compose.onNodeWithTag("capture_text").performClick()
        awaitReady()
        compose.onNodeWithTag("capture_input").performTextInput("synthetic unsaved text")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_input").assertTextContains("synthetic unsaved text")
        compose.onNodeWithTag("save_draft").assertIsNotEnabled()
        fail.set(false)
        compose.onNodeWithTag("capture_retry").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("capture_input").fetchSemanticsNodes()
            (disk.load() as? CaptureRead.Ready)?.snapshot?.draft == "synthetic unsaved text"
        }
        compose.waitForIdle()
        compose.onNodeWithTag("capture_input").assertTextContains("synthetic unsaved text")
    }

    @Test fun unknownSubmitCanBeRetriedWithoutCreatingADuplicate() {
        val disk = disk()
        val calls = AtomicInteger()
        val unreliable = object : CapturePersistence by disk {
            override fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
                text: String, recordedAt: Instant, zone: String): CaptureWrite {
                val result = disk.submit(commandId, expectedVersion, sourceId, revisionId, text, recordedAt, zone)
                return if (calls.incrementAndGet() == 1 && result == CaptureWrite.Recorded) CaptureWrite.Unavailable else result
            }
        }
        compose.setContent { MemoraApp(capturePersistenceOverride = unreliable) }
        compose.onNodeWithTag("capture_text").performClick()
        awaitReady()
        compose.onNodeWithTag("capture_input").performTextInput("synthetic one")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_input").assertIsNotEnabled()
        compose.onNodeWithTag("capture_retry").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("synthetic one").assertIsDisplayed()
        assertEquals(1, (disk.load() as CaptureRead.Ready).snapshot.captures.size)
        assertEquals(2, calls.get())
    }

    @Test fun deleteNeedsConfirmationAndExportExplainsPlaintext() {
        val disk = disk()
        compose.setContent { MemoraApp(capturePersistenceOverride = disk) }
        compose.onNodeWithTag("capture_text").performClick()
        awaitReady()
        compose.onNodeWithTag("capture_input").performTextInput("synthetic removal")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_export").performScrollTo().performClick()
        compose.onNodeWithText("JSON-файл содержит все локальные тестовые записи, все версии и текущий черновик в открытом виде. Копия останется в выбранном месте, в том числе после удаления записей из приложения.").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("capture_delete_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_delete_target").assertTextContains("synthetic removal")
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("draft_0").assertExists()
        compose.onNodeWithTag("capture_delete_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_delete_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isEmpty() }
        val snapshot = (disk.load() as CaptureRead.Ready).snapshot
        assertEquals(0, snapshot.captures.size)
        assertEquals(1, snapshot.tombstones.size)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY) fun correctingSourcePreservesHistoryAfterUnknownWriteResult() {
        val disk = disk()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(CaptureWrite.Recorded, disk.saveDraft(0, "Original synthetic"))
        assertEquals(CaptureWrite.Recorded, disk.submit("command", 1, "source", "revision", "Original synthetic", now, "UTC"))
        val uncertain = object : CapturePersistence by disk {
            override fun revise(sourceId: String, expectedRevisionId: String, revisionId: String, text: String, recordedAt: Instant): CaptureWrite {
                disk.revise(sourceId, expectedRevisionId, revisionId, text, recordedAt)
                return CaptureWrite.Unavailable
            }
        }
        compose.setContent { MemoraApp(initialRoute = "inbox", capturePersistenceOverride = uncertain) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_edit_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_edit_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_edit_text").performTextClearance()
        compose.onNodeWithTag("capture_edit_text").performTextInput("Corrected synthetic")
        compose.onNodeWithTag("capture_edit_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_edit_text").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Corrected synthetic").assertExists()
        compose.onNodeWithTag("capture_history_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_revision_1").assertTextContains("Original synthetic")
        compose.onNodeWithTag("capture_revision_2").assertTextContains("Corrected synthetic")
        assertEquals(2, (disk.load() as CaptureRead.Ready).snapshot.captures.single().source.revisions.size)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY) fun failedCorrectionKeepsTextAndRetriesTheSameOperation() {
        val disk = disk()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        disk.saveDraft(0, "Before synthetic")
        disk.submit("command", 1, "source", "revision", "Before synthetic", now, "UTC")
        val attempts = mutableListOf<Pair<String, Instant>>()
        val unreliable = object : CapturePersistence by disk {
            override fun revise(sourceId: String, expectedRevisionId: String, revisionId: String, text: String, recordedAt: Instant): CaptureWrite {
                attempts.add(revisionId to recordedAt)
                return if (attempts.size == 1) CaptureWrite.Unavailable else disk.revise(sourceId, expectedRevisionId, revisionId, text, recordedAt)
            }
        }
        compose.setContent { MemoraApp(initialRoute = "inbox", capturePersistenceOverride = unreliable) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_edit_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_edit_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_edit_text").performTextClearance()
        compose.onNodeWithTag("capture_edit_text").performTextInput("After synthetic")
        compose.onNodeWithTag("capture_edit_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_edit_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture_edit_text").assertTextContains("After synthetic")
        compose.onNodeWithTag("capture_edit_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("capture_edit_text").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, attempts.size)
        assertEquals(attempts[0], attempts[1])
        assertEquals(2, (disk.load() as CaptureRead.Ready).snapshot.captures.single().source.revisions.size)
    }

    @Test fun searchAndBatchArchiveRespectTheSelectedFilter() {
        val disk = disk()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(CaptureWrite.Recorded, disk.saveDraft(0, "Salam Bakı"))
        assertEquals(CaptureWrite.Recorded, disk.submit("c1", 1, "s1", "r1", "Salam Bakı", now, "Asia/Baku"))
        assertEquals(CaptureWrite.Recorded, disk.saveDraft(2, "Salam Gəncə"))
        assertEquals(CaptureWrite.Recorded, disk.submit("c2", 3, "s2", "r2", "Salam Gəncə", now, "Asia/Baku"))
        compose.setContent { MemoraApp(initialRoute = "inbox", capturePersistenceOverride = disk) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("inbox_search").performTextInput("GƏNCƏ")
        compose.onNodeWithText("Salam Gəncə").assertExists()
        compose.onNodeWithText("Salam Bakı").assertDoesNotExist()
        compose.onNodeWithTag("capture_select_0").assertContentDescriptionContains("Salam Gəncə", substring = true)
        compose.onNodeWithTag("capture_select_0").performScrollTo().performClick()
        compose.onNodeWithTag("inbox_selected").assertExists()
        compose.onNodeWithTag("inbox_search").performScrollTo().performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("inbox_selected").assertDoesNotExist()
        compose.onNodeWithTag("capture_select_0").performScrollTo().performClick()
        compose.onNodeWithTag("capture_select_1").performScrollTo().performClick()
        compose.onNodeWithTag("inbox_archive_selected").performScrollTo().performClick()
        compose.waitUntil(10_000) { (disk.load() as? CaptureRead.Ready)?.snapshot?.captures?.all { it.archived } == true }
        compose.onNodeWithTag("filter_archived").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Salam Bakı").assertExists()
        compose.onNodeWithText("Salam Gəncə").assertExists()
    }
}

package ai.memora.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.AppearanceStore
import ai.memora.app.data.AppearanceSettings
import ai.memora.app.data.AtomicAppearanceStore
import ai.memora.app.data.CapturePersistence
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.LocalCapturePersistence
import org.junit.Before

/** Runs Android 13 UI semantics and native rasterization on the JVM, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiShellTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var captureStore: CapturePersistence
    private lateinit var appearanceStore: AppearanceStore
    private lateinit var taskStore: ai.memora.app.data.EncryptedTaskStore

    @Test fun revokeClearsSavedVoiceConsents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("voice-first-consent", 0)
        prefs.edit().putBoolean("google-note-v1", true).putBoolean("google-live-v1", true)
            .putBoolean("google-audio-and-answer-v1", true).commit()
        compose.setContent { MemoraApp(initialRoute = "settings", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("voice_revoke_consent").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(prefs.all.isEmpty()) }
        compose.onNodeWithTag("voice_revoke_consent").assertDoesNotExist()
    }

    @Before fun setupCaptureStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(File(context.cacheDir, "ui-settings-${UUID.randomUUID()}"), AppearanceSettings("ru", "light"))
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        captureStore = LocalCapturePersistence(EncryptedCaptureStore(File(context.cacheDir, "ui-capture-${UUID.randomUUID()}.bin")) { key })
        taskStore = ai.memora.app.data.EncryptedTaskStore(File(context.cacheDir, "ui-tasks-${UUID.randomUUID()}.bin")) { key }
    }

    private fun awaitCaptureReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("capture_input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitAppearance(language: String, theme: String) {
        // Compose becoming idle does not imply that Dispatchers.IO has saved settings.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("filter_$language") and isSelected() and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("filter_$theme") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(AppearanceSettings(language, theme), appearanceStore.read())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File(System.getProperty("memora.screenshots"), "$name.png")
        output.parentFile?.mkdirs()
        // captureToImage waits for a real compositor frame, which Robolectric cannot
        // deliver. Draw the real View hierarchy through Robolectric native graphics.
        // https://github.com/robolectric/robolectric/issues/8071
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 && it.height > 0 }
            check(views.isNotEmpty()) { "No visible Android window to render" }
            val bitmap = Bitmap.createBitmap(views.maxOf { it.width }, views.maxOf { it.height }, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            views.forEach { it.draw(canvas) }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun navigationSearchAndSource() {
        val now = java.time.Instant.parse("2026-01-01T12:00:00Z")
        captureStore.saveDraft(0, "İlqar, yaddaş yoxlaması")
        captureStore.submit("cmd", 1, "synthetic-source", "r1", "İlqar, yaddaş yoxlaması", now, "Asia/Baku")
        compose.setContent { MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("voice_first_button").assertIsDisplayed()
        compose.onNodeWithTag("nav_home").assertIsDisplayed()
        compose.onNodeWithTag("nav_memory").assertIsDisplayed()
        compose.onNodeWithTag("nav_tasks").assertIsDisplayed()
        compose.onNodeWithTag("nav_more").assertIsDisplayed()
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
        screenshot("01-home-light")
        compose.onNodeWithTag("nav_memory").performClick()
        screenshot("02-memory-light")
        compose.onNodeWithTag("memory_search").performTextInput("İLQAR")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("memory_synthetic-source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_synthetic-source").performScrollTo().performClick()
        compose.onNodeWithTag("stored_source_text").assertTextContains("İlqar, yaddaş yoxlaması")
        screenshot("03-source-light")
    }

    @Test fun textDraftGoesToInboxWithoutAi() {
        compose.setContent { MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").performScrollTo().performClick()
        awaitCaptureReady()
        compose.onNodeWithTag("save_draft").assertIsNotEnabled()
        compose.onNodeWithTag("capture_input").performTextInput("A synthetic preview note")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A synthetic preview note").assertIsDisplayed()
        compose.onNodeWithText("Сохранено локально").assertIsDisplayed()
        screenshot("04-inbox-light")
    }

    @Test fun completingTaskChangesDoneFilter() {
        taskStore.append(ai.memora.app.domain.TaskEvent.Created("synthetic-task", "local", "owner", "command", "Synthetic task"))
        compose.setContent { MemoraApp(initialRoute = "tasks", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore, taskStoreOverride = taskStore) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic task").fetchSemanticsNodes().isNotEmpty() }
        screenshot("05-tasks-light")
        compose.onNodeWithText("Synthetic task").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Synthetic task").fetchSemanticsNodes()
            (taskStore.load() as? ai.memora.app.data.StoreRead.Ready)?.tasks?.single()?.completed == true
        }
    }

    @Test fun darkThemeAndLanguageSwitch() {
        compose.setContent { MemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_dark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_dark").performClick()
        awaitAppearance("ru", "dark")
        screenshot("06-settings-dark")
        compose.onNodeWithTag("filter_en").performClick()
        awaitAppearance("en", "dark")
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithTag("filter_az").performClick()
        awaitAppearance("az", "dark")
        compose.onNodeWithText("Görünüş").assertExists()
        compose.onNodeWithTag("filter_ru").performClick()
        awaitAppearance("ru", "dark")
        compose.onNodeWithTag("nav_home").performClick()
        screenshot("07-home-dark")
    }

    @Test fun storedAppearanceIsAppliedAtStartup() {
        appearanceStore.write(AppearanceSettings("az", "dark"))
        compose.setContent { MemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Görünüş").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").assertIsSelected()
        compose.onNodeWithTag("filter_dark").assertIsSelected()
    }

    @Test fun freshProfileUsesAzerbaijaniWithoutAddingDemoMemories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = AtomicAppearanceStore(File(context.cacheDir, "fresh-${UUID.randomUUID()}"))
        compose.setContent { MemoraApp(initialRoute = "settings", capturePersistenceOverride = captureStore, appearanceStoreOverride = fresh) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isSelected() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Görünüş").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("demo_info").assertDoesNotExist()
        compose.onNodeWithTag("memory_sensor").assertDoesNotExist()
        compose.onNodeWithTag("task_sensor").assertDoesNotExist()
        screenshot("12-home-azerbaijani")
    }

    @Test fun settingsWriteFailureRequiresReloadAndNeverShowsFalseSuccess() {
        var stored = AppearanceSettings("ru", "light")
        var failWrites = true
        val store = object : AppearanceStore {
            override fun read() = stored
            override fun write(settings: AppearanceSettings) {
                if (failWrites) throw java.io.IOException("synthetic failure")
                stored = settings
            }
        }
        compose.setContent { MemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = store) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings_storage_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").assertIsNotEnabled()
        compose.onNodeWithText("Оформление").assertExists()
        failWrites = false
        compose.onNodeWithTag("settings_retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Görünüş").fetchSemanticsNodes().isNotEmpty() }
        assert(stored.language == "az")
    }

    @Test fun financeShowsEmptyStateWithoutInventedAmounts() {
        compose.setContent { MemoraApp(initialRoute = "finance", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithText("Данных пока нет").assertExists()
        compose.onNodeWithText("230 ₼").assertDoesNotExist()
        screenshot("08-finance-light")
        compose.onNodeWithText("300 ₼").assertDoesNotExist()
    }

    @Test fun voiceInputRequiresAnExplicitStart() {
        compose.setContent { MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_capture").performClick()
        compose.onNodeWithTag("audio_note_start").assertExists()
        compose.onNodeWithTag("audio_note_review").assertDoesNotExist()
        compose.onNodeWithTag("audio_note_processing").assertDoesNotExist()
        screenshot("09-voice-preview")
    }

    @Test fun draftSurvivesUiRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").performScrollTo().performClick()
        awaitCaptureReady()
        compose.onNodeWithTag("capture_input").performTextInput("Synthetic saved state")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("capture_input").assertTextContains("Synthetic saved state")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic saved state").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun smallPhoneWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.4f)) {
                MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").assertExists().performScrollTo().performClick()
        compose.onNodeWithTag("capture_input").assertExists()
        screenshot("10-small-phone-large-text")
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun tabletLandscapeKeepsNavigationReachable() {
        compose.setContent { MemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_search").assertIsDisplayed()
        screenshot("11-tablet-landscape")
    }
}

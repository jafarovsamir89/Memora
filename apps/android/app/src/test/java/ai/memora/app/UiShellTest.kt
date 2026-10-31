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

/** Runs Android 13 UI semantics and native rasterization on the JVM, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiShellTest {
    @get:Rule val compose = createComposeRule()

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
        compose.setContent { MemoraApp(initialTheme = "light") }
        compose.onNodeWithTag("capture_voice").assertIsDisplayed()
        screenshot("01-home-light")
        compose.onNodeWithTag("nav_memory").performClick()
        screenshot("02-memory-light")
        compose.onNodeWithTag("memory_search").performTextInput("BMW")
        compose.onNodeWithTag("memory_sensor").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("Источник").assertIsDisplayed()
        screenshot("03-source-light")
    }

    @Test fun textDraftGoesToInboxWithoutAi() {
        compose.setContent { MemoraApp(initialTheme = "light") }
        compose.onNodeWithTag("capture_text").performClick()
        compose.onNodeWithTag("save_draft").assertIsNotEnabled()
        compose.onNodeWithTag("capture_input").performTextInput("A synthetic preview note")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.onNodeWithText("A synthetic preview note").assertIsDisplayed()
        compose.onNodeWithText("Черновик").assertIsDisplayed()
        screenshot("04-inbox-light")
    }

    @Test fun completingTaskChangesDoneFilter() {
        compose.setContent { MemoraApp(initialRoute = "tasks", initialTheme = "light") }
        screenshot("05-tasks-light")
        compose.onNodeWithTag("task_sensor").performClick()
        compose.onNodeWithTag("task_sensor").assertDoesNotExist()
        compose.onNodeWithTag("filter_done").performClick()
        compose.onNodeWithTag("task_sensor").assertExists()
    }

    @Test fun darkThemeAndLanguageSwitch() {
        compose.setContent { MemoraApp(initialRoute = "settings", initialTheme = "light") }
        compose.onNodeWithTag("filter_dark").performClick()
        screenshot("06-settings-dark")
        compose.onNodeWithTag("filter_en").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithTag("filter_az").performClick()
        compose.onNodeWithText("Görünüş").assertExists()
        compose.onNodeWithTag("filter_ru").performClick()
        compose.onNodeWithTag("nav_home").performClick()
        screenshot("07-home-dark")
    }

    @Test fun financePreviewHasCoverageAndCorrectAmounts() {
        compose.setContent { MemoraApp(initialRoute = "finance", initialTheme = "light") }
        compose.onNodeWithText("230 ₼").assertIsDisplayed()
        compose.onNodeWithText("По 2 демонстрационным операциям, не баланс счёта").assertExists()
        screenshot("08-finance-light")
        compose.onNodeWithTag("filter_debts").performClick()
        compose.onNodeWithText("300 ₼").assertExists()
    }

    @Test fun voiceInputRequiresAnExplicitStart() {
        compose.setContent { MemoraApp(initialTheme = "light") }
        compose.onNodeWithTag("capture_voice").performClick()
        compose.onNodeWithTag("cloud_voice_start").assertExists()
        compose.onNodeWithText("Тестовый разговор с Google Gemini. Память и задачи не подключены. Используйте вымышленные данные.").assertExists()
        screenshot("09-voice-preview")
    }

    @Test fun draftSurvivesUiRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemoraApp(initialTheme = "light") }
        compose.onNodeWithTag("capture_text").performClick()
        compose.onNodeWithTag("capture_input").performTextInput("Synthetic saved state")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("capture_input").assertTextContains("Synthetic saved state")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic saved state").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun smallPhoneWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.4f)) {
                MemoraApp(initialTheme = "light")
            }
        }
        compose.onNodeWithTag("capture_text").assertExists().performScrollTo().performClick()
        compose.onNodeWithTag("capture_input").assertExists()
        screenshot("10-small-phone-large-text")
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun tabletLandscapeKeepsNavigationReachable() {
        compose.setContent { MemoraApp(initialTheme = "light") }
        compose.onNodeWithTag("nav_memory").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_search").assertIsDisplayed()
        screenshot("11-tablet-landscape")
    }
}

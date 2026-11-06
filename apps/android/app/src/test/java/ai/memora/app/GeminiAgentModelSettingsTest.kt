package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.ai.GeminiAgentModel
import ai.memora.app.data.GeminiAgentModelSelectionStore
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.ui.ScreenColumn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
class GeminiAgentModelSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectorShowsAllSupportedModelsAndPersistsTheChosenModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("assistant-model-selection-v1", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { ScreenColumn { GeminiAgentModelSettings() } }
            }
        }

        val defaultTag = "ai_model_gemini_3_5_flash_lite"
        val alternativeTag = "ai_model_gemini_3_1_flash_lite"
        val advancedTag = "ai_model_gemini_3_6_flash"
        compose.onNodeWithTag("ai_model_settings_description").assertIsDisplayed()
        compose.onNodeWithTag(defaultTag).assertIsSelected()
        compose.onNodeWithTag(alternativeTag).assertIsDisplayed()
        compose.onNodeWithTag(advancedTag).assertIsDisplayed()
        compose.onNodeWithTag(alternativeTag).assertHasClickAction()
        compose.onNodeWithTag(advancedTag).performTouchInput { click() }
        compose.waitUntil(5_000) {
            GeminiAgentModelSelectionStore(context).selectedModel() == GeminiAgentModel.FLASH_36 ||
                compose.onAllNodesWithTag("ai_model_save_failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(advancedTag).assertIsSelected()
        compose.onNodeWithTag(defaultTag).assertIsDisplayed()
        assertEquals(GeminiAgentModel.FLASH_36, GeminiAgentModelSelectionStore(context).selectedModel())
    }
}

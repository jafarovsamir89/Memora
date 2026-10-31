package ai.memora.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import ai.memora.app.ai.AssistantGateway
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ru-w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantConsentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelNeverSendsAndConfirmationSendsOnlyTheDisplayedText() {
        val sent = mutableListOf<String>()
        val fake = object : AssistantGateway {
            override suspend fun answer(text: String): String { sent.add(text); return "Synthetic answer" }
        }
        compose.setContent {
            val resources = LocalContext.current.resources
            CompositionLocalProvider(LocalStrings provides resources) { MemoraTheme("light") { ChatScreen(gatewayOverride = fake) } }
        }
        compose.onNodeWithTag("chat_input").performTextInput("Synthetic question")
        compose.onNodeWithTag("chat_send").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithText("Отправить в Google").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("Synthetic question"), sent) }
        compose.onNodeWithText("Synthetic answer").assertExists()
    }
}

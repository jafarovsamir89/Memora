package ai.memora.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import ai.memora.app.data.CaptureImportPreview
import ai.memora.app.data.CaptureImportTicket
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ru-w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureImportDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewShowsScopeAndCancelDoesNotApply() {
        var applied = 0
        val open = mutableStateOf(true)
        val preview = CaptureImportPreview.Ready(2, 1, true, CaptureImportTicket("file", "store"), listOf("synthetic note"), "synthetic draft")
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") {
                    if (open.value) CaptureImportDialog(preview, { applied++ }, { open.value = false })
                }
            }
        }
        compose.onNodeWithText("Новых записей: 2. Уже существуют: 1.").assertExists()
        compose.onNodeWithText("• synthetic note").assertExists()
        compose.onNodeWithText("Начало черновика: synthetic draft").assertExists()
        compose.onNodeWithTag("capture_import_cancel").performClick()
        compose.runOnIdle { assertEquals(0, applied) }
    }

    @Test fun confirmationRequiresExplicitAction() {
        var applied = 0
        val preview = CaptureImportPreview.Ready(1, 0, false, CaptureImportTicket("file", "store"), listOf("synthetic"), null)
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides LocalContext.current.resources) {
                MemoraTheme("light") { CaptureImportDialog(preview, { applied++ }, {}) }
            }
        }
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithTag("capture_import_confirm").performClick()
        compose.runOnIdle { assertEquals(1, applied) }
    }
}

package ai.memora.app

import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.ui.ScreenColumn
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class TaskPrototypeUiTest {
    @get:Rule(order = 0) val agentDatabase = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun syntheticTaskCanBeCreatedAndCompleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedTaskStore(File(context.cacheDir, "ui-${java.util.UUID.randomUUID()}")) { key }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides agentDatabase.database) {
                MemoraTheme("light") { ScreenColumn { TaskPrototype(store) } }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("prototype_input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("prototype_input").performScrollTo().performTextInput("Synthetic test task")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("prototype_add") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("prototype_add").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic test task").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("prototype_input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Synthetic test task").performScrollTo().performClick()
        compose.waitUntil(10_000) { (store.load() as? ai.memora.app.data.StoreRead.Ready)?.tasks?.singleOrNull()?.completed == true }
    }

    @Test fun taskCanBeEditedAndDeletedWithConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedTaskStore(File(context.cacheDir, "ui-${java.util.UUID.randomUUID()}")) { key }
        val created = (ai.memora.app.domain.TaskRules.create("sample", "local", "owner", "create", "Before") as ai.memora.app.domain.TaskDecision.Changed).event
        store.append(created)
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides agentDatabase.database) {
                MemoraTheme("light") { ScreenColumn { TaskPrototype(store) } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("edit_sample").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit_sample").performScrollTo().performClick()
        compose.onNodeWithTag("task_edit_input").performTextClearance()
        compose.onNodeWithTag("task_edit_input").performTextInput("After")
        compose.onNodeWithTag("task_due_input").performScrollTo().performTextInput("2030-10-31 09:00")
        compose.onNodeWithTag("task_person_input").performScrollTo().performTextInput("Alex")
        compose.onNodeWithTag("task_project_input").performScrollTo().performTextInput("Workshop")
        compose.onNodeWithTag("task_edit_save").performClick()
        compose.waitUntil(10_000) { (store.load() as? ai.memora.app.data.StoreRead.Ready)?.tasks?.singleOrNull()?.title == "After" }
        val updated = (store.load() as ai.memora.app.data.StoreRead.Ready).tasks.single()
        org.junit.Assert.assertEquals("2030-10-31T09:00", updated.dueLocal)
        org.junit.Assert.assertEquals("Alex", updated.personLabel)
        org.junit.Assert.assertEquals("Workshop", updated.projectLabel)
        // Disk persistence can finish before Compose clears the save dialog/busy state.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("delete_sample") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("task_edit_input").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("delete_sample").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task_delete_confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task_delete_confirm").performClick()
        compose.waitUntil(10_000) { (store.load() as? ai.memora.app.data.StoreRead.Ready)?.tasks?.isEmpty() == true }
    }
}

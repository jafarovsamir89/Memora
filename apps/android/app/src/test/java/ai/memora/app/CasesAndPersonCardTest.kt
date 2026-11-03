package ai.memora.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.domain.TaskEvent
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CasesAndPersonCardTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun casesScreenOpensCaseWithSourceTimeline() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val domain = ProtectedDomainRepository(databaseRule.database)
        val cases = ProtectedCaseRepository(databaseRule.database)
        domain.createObject(scope, "synthetic-case-person", DomainKind.PERSON, "Synthetic Mechanic", "Fictional card", null,
            "2026-11-03T09:00:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Mechanic", "receipt", 25_000, "AZN", "synthetic event", null,
            "synthetic-finance-person", "synthetic-case-receipt", "synthetic-case-command",
            "2026-11-03T09:10:00Z", "2026-11-03T09:10:00Z")
        cases.createCase(scope, "synthetic-case-bmw", "Synthetic BMW repair", "Fictional repair details",
            nextAction = "Wait for fictional parts", sourceTurnId = "00000000-0000-4000-8000-000000000201",
            now = "2026-11-03T09:00:00Z")
        cases.addParticipant(scope, "synthetic-case-bmw", "synthetic-case-person", role = "mechanic",
            participantId = "synthetic-participant", now = "2026-11-03T09:01:00Z")
        cases.addEvidence(scope, "synthetic-case-bmw", "money_event", "synthetic-case-receipt", "advance",
            evidenceId = "synthetic-case-evidence", now = "2026-11-03T09:11:00Z")

        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") { CasesScreen() }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_synthetic-case-bmw").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_synthetic-case-bmw").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Money received: Synthetic Mechanic").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithTag("case_detail_title").assertTextEquals("Synthetic BMW repair")
        compose.onNodeWithText("Synthetic Mechanic").assertExists()
        compose.onAllNodesWithText("Money received: Synthetic Mechanic").assertCountEquals(2)
        compose.onAllNodesWithText("250 AZN").assertCountEquals(2)
        compose.onNodeWithTag("case_detail_next_action").assertTextEquals("Next step: Wait for fictional parts")
        compose.onNodeWithTag("case_evidence_source_synthetic-case-evidence").performScrollTo().performClick()
        compose.onNodeWithTag("case_source_detail_dialog").assertIsDisplayed()
        compose.onNodeWithTag("case_source_detail_title").assertTextEquals("Money received: Synthetic Mechanic")
        compose.onNodeWithTag("case_source_detail_close").performClick()
        compose.onNodeWithTag("case_timeline_evidence:synthetic-case-evidence").performScrollTo().performClick()
        compose.onNodeWithTag("case_source_detail_title").assertTextEquals("Money received: Synthetic Mechanic")
        compose.onNodeWithTag("case_source_detail_close").performClick()
        compose.onNodeWithTag("case_action_complete").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { databaseRule.database.protectedStoreDao().caseRecord(scope, "synthetic-case-bmw")?.status == "completed" }
        }
        assertEquals(2, runBlocking { databaseRule.database.protectedStoreDao().caseRevisions("synthetic-case-bmw").size })
    }

    @Test fun personCardRequiresVisibleOwnerConfirmationBeforeShowingFinanceHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        repository.createObject(scope, "synthetic-person-card", DomainKind.PERSON, "Synthetic Samir", "Synthetic person", null,
            "2026-11-03T10:00:00Z")
        repository.recordFinanceEvent(scope, "Synthetic Samir", "receipt", 10_000, "AZN", "synthetic event", null,
            "synthetic-finance-samir", "synthetic-samir-receipt", "synthetic-samir-command",
            "2026-11-03T10:01:00Z", "2026-11-03T10:01:00Z")

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { SavedObjectsScreen(DomainKind.PERSON) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved_object_synthetic-person-card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("saved_object_synthetic-person-card").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_identity_confirm_action").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_identity_confirm_action").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100 AZN").assertDoesNotExist()
        compose.onNodeWithTag("person_identity_confirm_action").performScrollTo().performClick()
        compose.onNodeWithTag("person_identity_confirm_accept").assertIsDisplayed().performClick()

        compose.waitUntil(10_000) {
            runBlocking {
                databaseRule.database.protectedStoreDao()
                    .activeIdentityLinkForFinancePerson(scope, "synthetic-finance-samir") != null
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_story_synthetic-samir-receipt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_story_synthetic-samir-receipt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100 AZN").assertExists()
    }

    @Test fun personCardShowsOnlyExplicitlyLinkedRecordsAndOpensTheirSource() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        repository.createObject(scope, "synthetic-linked-person", DomainKind.PERSON, "Synthetic Samir", null, null,
            "2026-11-03T10:00:00Z")
        repository.createObject(scope, "synthetic-linked-project", DomainKind.PROJECT, "Synthetic Workshop", "Fictional project details", null,
            "2026-11-03T10:01:00Z")
        repository.createObject(scope, "synthetic-unlinked-project", DomainKind.PROJECT, "Unlinked Workshop", null, null,
            "2026-11-03T10:02:00Z")
        repository.link(scope, "synthetic-person-project-link", "synthetic-linked-person", "synthetic-linked-project", "works_on")

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { SavedObjectsScreen(DomainKind.PERSON) }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("saved_object_synthetic-linked-person").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("saved_object_synthetic-linked-person").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_linked_object_synthetic-linked-project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_linked_object_synthetic-linked-project").assertExists()
        compose.onNodeWithText("Unlinked Workshop").assertDoesNotExist()
        compose.onNodeWithTag("person_linked_object_synthetic-linked-project").performScrollTo()
        screenshot("18-person-card-linked-records")
        compose.onNodeWithTag("person_linked_object_synthetic-linked-project").performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Workshop")
        compose.onNodeWithText("Fictional project details").assertExists()
        screenshot("19-person-linked-project-detail")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Samir")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_linked_object_synthetic-linked-project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("saved_object_detail_close").performClick()
    }

    @Test fun personTimelineOpensExplicitTaskAndCalendarSourcesAndReturnsToCard() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val cases = ProtectedCaseRepository(databaseRule.database)
        repository.createObject(scope, "synthetic-source-person", DomainKind.PERSON, "Synthetic Planner", null, null,
            "2026-11-03T10:00:00Z")
        repository.appendTaskEvent(TaskEvent.Created("synthetic-linked-task", scope, "synthetic-test-owner",
            "synthetic-linked-task-create", "Synthetic follow-up task"))
        repository.createCalendarEvent(CalendarInput("synthetic-linked-calendar", scope, "Synthetic appointment",
            "2026-11-07T11:00", "2026-11-07T11:30", "Asia/Baku", "minute"))
        cases.createCase(scope, "synthetic-source-case", "Synthetic planning case", "Fictional planning summary",
            nextAction = "Wait for synthetic confirmation",
            sourceTurnId = "00000000-0000-4000-8000-000000000202", now = "2026-11-03T10:01:00Z")
        cases.addParticipant(scope, "synthetic-source-case", "synthetic-source-person", now = "2026-11-03T10:02:00Z")
        cases.addEvidence(scope, "synthetic-source-case", "task", "synthetic-linked-task", "follow_up",
            evidenceId = "synthetic-task-evidence", now = "2026-11-03T10:03:00Z")
        cases.addEvidence(scope, "synthetic-source-case", "calendar_event", "synthetic-linked-calendar", "appointment",
            evidenceId = "synthetic-calendar-evidence", now = "2026-11-03T10:04:00Z")

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { SavedObjectsScreen(DomainKind.PERSON) }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("saved_object_synthetic-source-person").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("saved_object_synthetic-source-person").performScrollTo().performClick()
        val taskStoryTag = "person_story_case-evidence:synthetic-task-evidence"
        val calendarStoryTag = "person_story_case-evidence:synthetic-calendar-evidence"
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(taskStoryTag).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(calendarStoryTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag(taskStoryTag).assertCountEquals(1)
        compose.onAllNodesWithTag(calendarStoryTag).assertCountEquals(1)
        compose.onNodeWithTag(taskStoryTag).performScrollTo()
        screenshot("20-person-card-explicit-sources")

        compose.onNodeWithTag("person_related_case_synthetic-source-case").performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic planning case")
        compose.onNodeWithTag("person_case_summary").assertTextEquals("Fictional planning summary")
        compose.onNodeWithTag("person_case_next_action").assertTextEquals("Next step: Wait for synthetic confirmation")
        check(compose.onAllNodesWithText("Synthetic Planner").fetchSemanticsNodes().isNotEmpty())
        screenshot("22-person-case-details")

        val caseTaskSourceTag = "person_case_detail_source_synthetic-task-evidence"
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(caseTaskSourceTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(caseTaskSourceTag).performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic follow-up task")
        screenshot("23-person-case-task-source")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic planning case")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Planner")

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(taskStoryTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(taskStoryTag).performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic follow-up task")
        compose.onNodeWithText("Relationship: Follow-up").assertExists()
        screenshot("21-person-task-source-detail")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Planner")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(calendarStoryTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(calendarStoryTag).performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic appointment")
        compose.onNodeWithText("Relationship: Appointment").assertExists()
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Planner")
        compose.onNodeWithTag("saved_object_detail_close").performClick()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val root = System.getProperty("memora.screenshots") ?: error("Screenshot output path is missing")
        val output = File(root, "$name.png")
        output.parentFile?.mkdirs()
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
}

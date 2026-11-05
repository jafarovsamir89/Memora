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
import ai.memora.app.data.protectedstore.MemoryRevisionRow
import ai.memora.app.data.protectedstore.MemorySourceRow
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.PersonAliasRepository
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

    @Test fun casesScreenSearchesVerifiedSourceWordingAndFiltersStatus() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val cases = ProtectedCaseRepository(databaseRule.database)
        cases.createCase(scope, "synthetic-search-bmw", "Synthetic engine repair", "Fictional repair",
            sourceTurnId = "synthetic-search-bmw-source", now = "2026-11-03T09:00:00Z",
            sourceText = "Synthetic: replacement gearbox arrives Friday.",
            sourceQuote = "replacement gearbox arrives Friday")
        cases.createCase(scope, "synthetic-search-waiting", "Synthetic travel booking", "Fictional booking",
            status = "waiting", sourceTurnId = "synthetic-search-waiting-source", now = "2026-11-03T10:00:00Z")

        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") { CasesScreen() }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("case_synthetic-search-bmw").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("case_synthetic-search-waiting").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("cases_filter_waiting").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("case_synthetic-search-waiting").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("cases_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("case_synthetic-search-waiting").assertExists()
        compose.onNodeWithTag("case_synthetic-search-bmw").assertDoesNotExist()

        compose.onNodeWithTag("cases_filter_all").performClick()
        compose.onNodeWithTag("cases_search").performTextInput("gearbox arrives Friday")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("case_synthetic-search-bmw").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("cases_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("case_synthetic-search-bmw").assertIsDisplayed()
        compose.onNodeWithTag("case_synthetic-search-waiting").assertDoesNotExist()

        compose.onNodeWithTag("cases_filter_waiting").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("cases_clear_filters").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("case_synthetic-search-bmw").assertDoesNotExist()
        compose.onNodeWithTag("cases_clear_filters").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("case_synthetic-search-waiting").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("case_synthetic-search-bmw").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("cases_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("case_synthetic-search-bmw").assertExists()
    }

    @Test fun casesScreenOpensCaseWithSourceTimeline() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val domain = ProtectedDomainRepository(databaseRule.database)
        val cases = ProtectedCaseRepository(databaseRule.database)
        domain.createObject(scope, "synthetic-case-person", DomainKind.PERSON, "Synthetic Mechanic", "Fictional card", null,
            "2026-11-03T09:00:00Z")
        domain.createObject(scope, "synthetic-case-project", DomainKind.PROJECT, "Synthetic BMW project", "Fictional project source", null,
            "2026-11-03T09:02:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Mechanic", "receipt", 25_000, "AZN", "synthetic event", null,
            "synthetic-finance-person", "synthetic-case-receipt", "synthetic-case-command",
            "2026-11-03T09:10:00Z", "2026-11-03T09:10:00Z")
        cases.createCase(scope, "synthetic-case-bmw", "Synthetic BMW repair", "Fictional repair details",
            nextAction = "Wait for fictional parts", sourceTurnId = "00000000-0000-4000-8000-000000000201",
            now = "2026-11-03T09:00:00Z", sourceText = "Synthetic: the blue replacement part arrives Friday.",
            sourceQuote = "the blue replacement part arrives Friday")
        cases.addParticipant(scope, "synthetic-case-bmw", "synthetic-case-person", role = "mechanic",
            participantId = "synthetic-participant", now = "2026-11-03T09:01:00Z")
        cases.addEvidence(scope, "synthetic-case-bmw", "money_event", "synthetic-case-receipt", "advance",
            evidenceId = "synthetic-case-evidence", now = "2026-11-03T09:11:00Z")
        cases.addEvidence(scope, "synthetic-case-bmw", "domain_object", "synthetic-case-project", "repair_project",
            evidenceId = "synthetic-case-project-evidence", now = "2026-11-03T09:12:00Z")
        databaseRule.database.protectedStoreDao().putSources(listOf(MemorySourceRow("synthetic-case-memory", "synthetic-case-memory-command",
            null, "Asia/Baku", "voice", false, 1, scope)))
        databaseRule.database.protectedStoreDao().putRevisions(listOf(MemoryRevisionRow("synthetic-case-memory", "synthetic-case-memory-revision",
            1, "Fictional repair note: blue parts arrive Friday.", "2026-11-03T09:13:00Z")))
        cases.addEvidence(scope, "synthetic-case-bmw", "memory_revision", "synthetic-case-memory", "repair_note",
            sourceRevisionId = "synthetic-case-memory-revision", evidenceId = "synthetic-case-memory-evidence",
            now = "2026-11-03T09:14:00Z")

        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") { CasesScreen() }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_synthetic-case-bmw").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_synthetic-case-bmw").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Money received: Synthetic Mechanic").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithTag("case_detail_title").assertTextEquals("Synthetic BMW repair")
        compose.onNodeWithTag("case_timeline_exact_excerpt_case:synthetic-case-bmw:1").assertExists()
        compose.onNodeWithText("Synthetic Mechanic").assertExists()
        compose.onAllNodesWithText("Money received: Synthetic Mechanic").assertCountEquals(2)
        compose.onAllNodesWithText("250 AZN").assertCountEquals(2)
        compose.onNodeWithTag("case_evidence_source_synthetic-case-project-evidence").assertExists()
        compose.onAllNodesWithText("Synthetic BMW project").assertCountEquals(2)
        compose.onNodeWithTag("case_evidence_source_synthetic-case-memory-evidence").performScrollTo().assertExists()
        compose.onAllNodesWithText("Fictional repair note: blue parts arrive Friday.").assertCountEquals(2)
        compose.onNodeWithTag("case_detail_next_action").assertTextEquals("Next step: Wait for fictional parts")
        compose.onNodeWithTag("case_evidence_source_synthetic-case-evidence").performScrollTo().performClick()
        compose.onNodeWithTag("case_source_detail_dialog").assertIsDisplayed()
        compose.onNodeWithTag("case_source_detail_title").assertTextEquals("Money received: Synthetic Mechanic")
        compose.onNodeWithTag("case_source_detail_close").performClick()
        compose.onNodeWithTag("case_evidence_source_synthetic-case-project-evidence").performScrollTo().performClick()
        compose.onNodeWithTag("case_source_detail_title").assertTextEquals("Synthetic BMW project")
        compose.onNodeWithTag("case_source_detail_close").performClick()
        compose.onNodeWithTag("case_evidence_source_synthetic-case-memory-evidence").performScrollTo().performClick()
        compose.onAllNodesWithText("Fictional repair note: blue parts arrive Friday.").assertCountEquals(3)
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

    @Test fun personCardShowsSavedAliasesAndEditControls() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val domain = ProtectedDomainRepository(databaseRule.database)
        domain.createObject(scope, "synthetic-alias-ui-person", DomainKind.PERSON,
            "Synthetic Canonical Person", null, null, "2026-11-03T10:00:00Z")
        val aliases = PersonAliasRepository(databaseRule.database)
        aliases.add(scope, "synthetic-alias-ui-person", "Synthetic Alternate Name", nowMillis = 10)
        val retired = aliases.add(scope, "synthetic-alias-ui-person", "Synthetic Retired Name", nowMillis = 11)
        aliases.retire(scope, retired.aliasId, retired.version, nowMillis = 12)
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { SavedObjectsScreen(DomainKind.PERSON) }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("saved_object_synthetic-alias-ui-person").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("saved_object_synthetic-alias-ui-person").performScrollTo().performClick()
        val rows = runBlocking { aliases.list(scope, "synthetic-alias-ui-person") }
        val active = rows.single { it.deletedAtMillis == null }
        val retiredRow = rows.single { it.deletedAtMillis != null }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_alias_item_${active.aliasId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_alias_item_${active.aliasId}").assertExists()
        compose.onNodeWithTag("person_alias_retired_${retiredRow.aliasId}").assertExists()
        compose.onNodeWithTag("person_alias_restore_${retiredRow.aliasId}").assertExists()
        compose.onNodeWithTag("person_alias_open_action").assertExists()
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
        assertEquals(listOf("synthetic-linked-task"), ai.memora.app.data.protectedstore.ProtectedMemoryRepository(databaseRule.database)
            .tasks().tasks.map { it.id })
        repository.recordFinanceEvent(scope, "Synthetic Planner", "receipt", 5_000, "AZN", "Synthetic advance", null,
            "synthetic-planner-finance", "synthetic-planner-receipt", "synthetic-planner-receipt-command",
            "2026-11-03T10:01:00Z", "2026-11-03T10:01:00Z")
        repository.createCalendarEvent(CalendarInput("synthetic-linked-calendar", scope, "Synthetic appointment",
            "2026-11-07T11:00", "2026-11-07T11:30", "Asia/Baku", "minute"))
        assertEquals("Synthetic appointment", databaseRule.database.protectedStoreDao()
            .calendarEvent(scope, "synthetic-linked-calendar")?.title)
        cases.createCase(scope, "synthetic-source-case", "Synthetic planning case", "Fictional planning summary",
            nextAction = "Wait for synthetic confirmation",
            sourceTurnId = "00000000-0000-4000-8000-000000000202", now = "2026-11-03T10:01:00Z")
        cases.addParticipant(scope, "synthetic-source-case", "synthetic-source-person", now = "2026-11-03T10:02:00Z")
        cases.addEvidence(scope, "synthetic-source-case", "task", "synthetic-linked-task", "follow_up",
            evidenceId = "synthetic-task-evidence", now = "2026-11-03T10:03:00Z")
        cases.addEvidence(scope, "synthetic-source-case", "calendar_event", "synthetic-linked-calendar", "appointment",
            evidenceId = "synthetic-calendar-evidence", now = "2026-11-03T10:04:00Z")
        cases.addEvidence(scope, "synthetic-source-case", "money_event", "synthetic-planner-receipt", "advance",
            evidenceId = "synthetic-planner-receipt-evidence-1", now = "2026-11-03T10:04:30Z")
        cases.createCase(scope, "synthetic-source-case-2", "Synthetic follow-up case", "Second fictional case",
            sourceTurnId = "00000000-0000-4000-8000-000000000203", now = "2026-11-03T10:05:00Z")
        cases.addParticipant(scope, "synthetic-source-case-2", "synthetic-source-person", now = "2026-11-03T10:06:00Z")
        cases.addEvidence(scope, "synthetic-source-case-2", "task", "synthetic-linked-task", "follow_up",
            evidenceId = "synthetic-task-evidence-2", now = "2026-11-03T10:07:00Z")
        cases.addEvidence(scope, "synthetic-source-case-2", "money_event", "synthetic-planner-receipt", "advance",
            evidenceId = "synthetic-planner-receipt-evidence-2", now = "2026-11-03T10:07:30Z")
        repeat(7) { index ->
            val caseId = "synthetic-expanded-case-$index"
            val at = "2026-11-03T10:${(8 + index).toString().padStart(2, '0')}:00Z"
            cases.createCase(scope, caseId, "Synthetic expanded case $index", null,
                sourceTurnId = "synthetic-expanded-source-$index", now = at)
            cases.addParticipant(scope, caseId, "synthetic-source-person", now = at)
        }

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
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_related_cases_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_related_case_synthetic-source-case").assertDoesNotExist()
        compose.onNodeWithTag("person_related_cases_toggle").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_related_case_synthetic-source-case").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_related_case_synthetic-source-case").assertExists()
        compose.onNodeWithTag("person_related_cases_toggle").assertExists()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("person_task_link_open") and androidx.compose.ui.test.isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_task_link_open").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_task_candidate_synthetic-linked-task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_task_candidate_synthetic-linked-task").performClick()
        compose.onNodeWithTag("person_task_link_confirm").performClick()
        val directTaskLinkTag = "person_task_link_synthetic-linked-task"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(directTaskLinkTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(directTaskLinkTag).assertExists()
        compose.onNodeWithTag("person_task_open_synthetic-linked-task").performClick()
        compose.onNodeWithTag("saved_object_task_title").assertTextEquals("Synthetic follow-up task")
        screenshot("26-person-direct-task-detail")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Planner")
        val taskPersonLink = runBlocking {
            databaseRule.database.protectedStoreDao().taskPersonLink(scope, "synthetic-linked-task", "synthetic-source-person")
        }
        requireNotNull(taskPersonLink)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_task_unlink_${taskPersonLink.linkId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_task_unlink_${taskPersonLink.linkId}").performClick()
        compose.onNodeWithTag("person_task_unlink_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(directTaskLinkTag).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("linked", "retired"), runBlocking {
            databaseRule.database.protectedStoreDao().taskPersonLinkRevisions(scope, taskPersonLink.linkId).map { it.action }
        })
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("person_calendar_link_open") and androidx.compose.ui.test.isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_calendar_link_open").performScrollTo().performClick()
        compose.onNodeWithTag("person_calendar_candidate_synthetic-linked-calendar").assertExists()
        compose.onNodeWithTag("person_calendar_candidate_synthetic-linked-calendar").performClick()
        compose.onNodeWithTag("person_calendar_link_confirm").performClick()
        val directCalendarLinkTag = "person_calendar_link_synthetic-linked-calendar"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(directCalendarLinkTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(directCalendarLinkTag).performScrollTo()
        screenshot("27-person-calendar-direct-link")
        val calendarPersonLink = requireNotNull(runBlocking {
            databaseRule.database.protectedStoreDao().calendarPersonLink(scope, "synthetic-linked-calendar", "synthetic-source-person")
        })
        compose.onNodeWithTag("person_calendar_unlink_${calendarPersonLink.linkId}").performScrollTo().performClick()
        compose.onNodeWithTag("person_calendar_unlink_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(directCalendarLinkTag).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("linked", "retired"), runBlocking {
            databaseRule.database.protectedStoreDao().calendarPersonLinkRevisions(scope, calendarPersonLink.linkId).map { it.action }
        })
        assertEquals("Synthetic appointment", runBlocking {
            databaseRule.database.protectedStoreDao().calendarEvent(scope, "synthetic-linked-calendar")?.title
        })
        val taskStoryTag = "person_story_source-task-synthetic-linked-task-"
        val calendarStoryTag = "person_story_source-calendar_event-synthetic-linked-calendar-"
        val receiptStoryTag = "person_story_source-money_event-synthetic-planner-receipt-"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(taskStoryTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag(calendarStoryTag).assertCountEquals(1)
        compose.onAllNodesWithTag(receiptStoryTag).assertCountEquals(1)
        compose.onAllNodesWithTag(taskStoryTag).assertCountEquals(1)
        compose.onAllNodesWithTag(calendarStoryTag).assertCountEquals(1)
        assertEquals(1, runBlocking { databaseRule.database.protectedStoreDao().moneyEvents(scope).size })
        if (compose.onAllNodesWithTag("person_related_case_synthetic-source-case").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("person_related_cases_toggle").performScrollTo().performClick()
        }
        compose.onNodeWithTag("person_related_case_synthetic-source-case").assertExists()
        compose.onNodeWithTag("person_related_case_synthetic-source-case-2").assertExists()
        compose.onNodeWithTag(taskStoryTag).performScrollTo()
        screenshot("20-person-card-explicit-sources")
        compose.onNodeWithTag(receiptStoryTag).performScrollTo()
        screenshot("24-person-card-single-finance-source")
        compose.onNodeWithTag(receiptStoryTag).performScrollTo().performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Money received: Synthetic Planner")
        compose.onNodeWithText("50 AZN").assertExists()
        screenshot("25-person-single-receipt-source")
        compose.onNodeWithTag("saved_object_detail_back").performClick()
        compose.onNodeWithTag("saved_object_detail_title").assertTextEquals("Synthetic Planner")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_related_cases_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("person_related_case_synthetic-source-case").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("person_related_cases_toggle").performScrollTo().performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_related_case_synthetic-source-case").fetchSemanticsNodes().isNotEmpty()
        }

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

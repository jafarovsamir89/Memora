package ai.memora.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.domain.TaskEvent
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EntityDetailScreensTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun secretaryCasePersonFinanceTaskExactSourceAndBackChainUsesStableIds() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val domain = ProtectedDomainRepository(databaseRule.database)
        val cases = ProtectedCaseRepository(databaseRule.database)
        val dao = databaseRule.database.protectedStoreDao()
        val personId = UUID.randomUUID().toString()
        val financePersonId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val caseId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()
        val evidenceId = UUID.randomUUID().toString()
        val turnId = UUID.randomUUID().toString()
        val actionResultId = UUID.randomUUID().toString()
        val at = "2026-11-16T10:00:00Z"

        domain.createObject(scope, personId, DomainKind.PERSON, "Synthetic Elchin", "Fictional vehicle owner", null, at)
        PersonAliasRepository(databaseRule.database).add(scope, personId, "Synthetic E.", nowMillis = 1_000L)
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 15_000, "AZN", "Synthetic advance", "Fictional advance",
            financePersonId, eventId, UUID.randomUUID().toString(), at, at)
        cases.confirmFinancePersonIdentity(scope, financePersonId, personId, confirmedBy = "owner", now = at)
        cases.createCase(scope, caseId, "Synthetic BMW repair", "Fictional repair summary", nextAction = "Wait for fictional parts",
            sourceTurnId = turnId, now = at)
        cases.addParticipant(scope, caseId, personId, role = "owner", now = at)
        cases.addEvidence(scope, caseId, "money_event", eventId, "advance", evidenceId = evidenceId, now = at)
        domain.appendTaskEvent(TaskEvent.Created(taskId, scope, "synthetic-owner", UUID.randomUUID().toString(), "Synthetic order parts"))
        cases.confirmTaskPersonLink(scope, taskId, personId, "assignee", confirmedBy = "owner", now = at)
        cases.addEvidence(scope, caseId, "task", taskId, "follow_up", now = at)
        dao.putConversationMessages(listOf(AgentConversationMessageRow(
            scopeId = scope, turnId = turnId, role = "user", message = "Synthetic source: confirm the fictional parts order.", createdAtMillis = 1_000L,
        ), AgentConversationMessageRow(
            scopeId = scope, turnId = turnId, role = "model", message = "Memora synthetic completion.", createdAtMillis = 1_001L,
        )))
        dao.putActionResults(listOf(AgentActionResultRow(
            resultId = actionResultId, scopeId = scope, turnId = turnId,
            toolName = "create_task", status = "APPLIED", code = "ok", targetKind = "task", targetId = taskId,
            createdAtMillis = 1_000L,
        )))

        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") {
                    val screenStates = rememberSaveableStateHolder()
                    var navigation by remember {
                        mutableStateOf(AppNavigationState.start(AppRoute.screen(AppDestination.SECRETARY)))
                    }
                    Column(Modifier.fillMaxSize()) {
                        Text(navigation.current.encode(), Modifier.testTag("entity_chain_current_route"))
                        TextButton(modifier = Modifier.testTag("entity_chain_back"), onClick = { navigation = navigation.back() }) {
                            Text("Back")
                        }
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            screenStates.SaveableStateProvider(navigation.current.stateKey) {
                                if (navigation.current.destination == AppDestination.SECRETARY) {
                                    TextButton(modifier = Modifier.testTag("entity_chain_open_case"), onClick = {
                                        navigation = navigation.navigate(requireNotNull(AppRoute.target(AppTargetKind.CASE, caseId)))
                                    }) { Text("Synthetic Secretary action") }
                                } else {
                                    ActionTargetDetailScreen(navigation.current) { navigation = navigation.navigate(it) }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("entity_chain_open_case").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_detail_evidence_$evidenceId").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("source_detail_evidence_$evidenceId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("source_detail_open_object").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("source_detail_evidence_$evidenceId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_detail_person_$personId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_detail_screen_$personId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_detail_verified_aliases").assertTextContains("Synthetic E.")
        compose.onNodeWithTag("person_detail_section_tasks").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_task_open_$taskId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_task_open_$taskId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entity_chain_current_route").assertTextEquals("target:task:$taskId")
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_detail_screen_$personId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_detail_section_finance").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("person_detail_section_finance").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("person_detail_section_finance").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("person_finance_event_$eventId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("person_finance_event_$eventId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_detail_amount").assertTextContains("150.00 AZN")
        compose.onNodeWithTag("finance_detail_task_$taskId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task_detail_source_$turnId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("source_detail_screen_$turnId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source_detail_turn_text_0").assertTextContains("Synthetic source", substring = true)
        compose.onNodeWithTag("source_detail_turn_text_1").assertTextEquals("Memora synthetic completion.")
        compose.onNodeWithTag("source_detail_action_status_$actionResultId").assertTextContains(
            context.getString(R.string.action_status_applied), substring = true)
        compose.onNodeWithTag("source_detail_action_target_$actionResultId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entity_chain_current_route").assertTextEquals("target:task:$taskId")
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("source_detail_screen_$turnId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source_detail_open_conversation").performScrollTo().performClick()
        compose.onNodeWithTag("entity_chain_current_route").assertTextEquals("conversation:$turnId")
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("source_detail_screen_$turnId").fetchSemanticsNodes().isNotEmpty() }

        listOf("task_detail_header", "finance_detail_header", "person_detail_screen_$personId",
            "case_detail_header").forEach { expected ->
            compose.onNodeWithTag("entity_chain_back").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(expected).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithTag("entity_chain_back").performClick()
        compose.onNodeWithTag("entity_chain_current_route").assertTextContains("screen:chat")
    }

    @Test fun exactCaseRouteUpdatesStatusUsingExistingRepositoryAndShowsReadback() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val caseId = UUID.randomUUID().toString()
        val cases = ProtectedCaseRepository(databaseRule.database)
        cases.createCase(scope, caseId, "Synthetic status case", "Fictional summary", sourceTurnId = UUID.randomUUID().toString())
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") { CaseTargetDetailScreen(requireNotNull(AppRoute.target(AppTargetKind.CASE, caseId))) {} }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_action_complete").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { databaseRule.database.protectedStoreDao().caseRecord(scope, caseId)?.status == "completed" }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_detail_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_detail_status").assertTextContains(context.getString(R.string.case_status_completed))
    }

    @Test fun financeDetailDoesNotResolveAnEventOutsideCurrentScope() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val foreignScope = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val at = "2026-11-16T11:00:00Z"
        repository.recordFinanceEvent(foreignScope, "Synthetic foreign person", "receipt", 7_000, "AZN",
            "Synthetic foreign source", null, UUID.randomUUID().toString(), eventId, UUID.randomUUID().toString(), at, at)

        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") {
                    FinanceTargetDetailScreen(requireNotNull(AppRoute.target(AppTargetKind.FINANCE, eventId))) {}
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entity_detail_missing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.entity_record_unavailable)).assertExists()
    }

    @Test fun financeDetailLabelsDebtAsNonIncomeAndShowsExistingCorrectionHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val at = "2026-11-16T11:00:00Z"
        val debtId = UUID.randomUUID().toString()
        repository.recordFinanceEvent(scope, "Synthetic debt contact", "debt", 5_000, "AZN",
            "Fictional debt source", "Fictional debt", UUID.randomUUID().toString(), debtId,
            UUID.randomUUID().toString(), at, at)
        val correctionId = UUID.randomUUID().toString()
        repository.correctFinanceEventAmount(scope, debtId, 6_500, correctionId,
            "Synthetic corrected amount", "2026-11-16T11:05:00Z")
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources, LocalAgentDatabaseOverride provides databaseRule.database) {
                MemoraTheme("light") {
                    FinanceTargetDetailScreen(requireNotNull(AppRoute.target(AppTargetKind.FINANCE, debtId))) {}
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_detail_direction").assertTextContains(
            context.getString(R.string.finance_direction_debt), substring = true)
        compose.onNodeWithTag("finance_detail_amount").assertTextContains("65", substring = true)
        compose.onNodeWithTag("finance_detail_adjustment_$correctionId").assertExists()
        assertEquals(1, runBlocking { databaseRule.database.protectedStoreDao().adjustments(scope, debtId).size })
    }
}

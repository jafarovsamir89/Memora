package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
}

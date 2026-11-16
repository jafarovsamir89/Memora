package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
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
class ProjectNotesScreenUiTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun projectsPageThroughResultsAndOpenExactStableId() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val ids = (0 until 35).map { UUID.nameUUIDFromBytes("synthetic-project-$it".toByteArray()).toString() }
        ids.forEachIndexed { index, id ->
            repository.createObject(scope, id, DomainKind.PROJECT, "Synthetic Project %02d".format(index),
                "Fictional project body $index", null, "2026-11-16T09:00:00Z")
        }
        repository.createObject("synthetic-other-scope", UUID.randomUUID().toString(), DomainKind.PROJECT,
            "Foreign scope project", "Must remain hidden", null, "2026-11-16T09:00:00Z")

        var opened: AppRoute? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") { ProjectNotesScreen(DomainKind.PROJECT) { opened = it } }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_notes_count").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("project_notes_count").assertExists()
        compose.onNodeWithTag("project_note_${ids.first()}").assertExists()
        compose.onNodeWithTag("project_note_${ids.last()}").assertDoesNotExist()
        compose.onNodeWithText("Foreign scope project").assertDoesNotExist()
        compose.onNodeWithTag("project_notes_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("project_notes_load_more"),
        )
        compose.onNodeWithTag("project_notes_load_more").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_notes_load_more").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("project_notes_list").performScrollToNode(
            androidx.compose.ui.test.hasTestTag("project_note_${ids.last()}"),
        )
        compose.onNodeWithTag("project_note_${ids.last()}").performClick()
        assertEquals(AppRoute.target(AppTargetKind.PROJECT, ids.last()), opened)
        compose.onNodeWithTag("project_note_${ids.last()}").assertExists()
    }

    @Test fun noteSearchFindsResultsPastFirstPageAndReturnsExactNoteRoute() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        repeat(34) { index ->
            repository.createObject(scope, UUID.nameUUIDFromBytes("synthetic-note-$index".toByteArray()).toString(),
                DomainKind.NOTE, "Synthetic Note %02d".format(index), "Fictional ordinary text $index", null,
                "2026-11-16T09:00:00Z")
        }
        val targetId = UUID.nameUUIDFromBytes("synthetic-rare-note".toByteArray()).toString()
        repository.createObject(scope, targetId, DomainKind.NOTE, "Synthetic rare archive", "Fictional azimuthneedle detail",
            null, "2026-11-16T09:00:00Z")

        var opened: AppRoute? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") { ProjectNotesScreen(DomainKind.NOTE) { opened = it } }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_notes_count").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("project_notes_search").performTextInput("azimuthneedle")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_note_$targetId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("project_note_$targetId").performClick()
        assertEquals(AppRoute.target(AppTargetKind.NOTE, targetId), opened)
    }

    @Test fun exactProjectDetailShowsOnlySameScopeLinksAndOpensLinkedNote() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val projectId = UUID.nameUUIDFromBytes("synthetic-project-detail".toByteArray()).toString()
        val noteId = UUID.nameUUIDFromBytes("synthetic-project-detail-note".toByteArray()).toString()
        val personId = UUID.nameUUIDFromBytes("synthetic-project-detail-person".toByteArray()).toString()
        val foreignId = UUID.nameUUIDFromBytes("synthetic-project-detail-foreign".toByteArray()).toString()
        repository.createObject(scope, projectId, DomainKind.PROJECT, "Synthetic windmill project", "Fictional progress note",
            null, "2026-11-16T09:00:00Z")
        repository.createObject(scope, noteId, DomainKind.NOTE, "Synthetic site visit", "Fictional inspection notes",
            null, "2026-11-16T09:00:00Z")
        repository.createObject(scope, personId, DomainKind.PERSON, "Synthetic Leyla", "Fictional contact",
            null, "2026-11-16T09:00:00Z")
        repository.createObject("synthetic-other-scope", foreignId, DomainKind.NOTE,
            "Foreign private note", "Must not appear", null, "2026-11-16T09:00:00Z")
        repository.link(scope, UUID.randomUUID().toString(), projectId, noteId, "related_note")
        repository.link(scope, UUID.randomUUID().toString(), projectId, personId, "owner_confirmed_contact")

        var opened: AppRoute? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") {
                    ActionTargetDetailScreen(
                        requireNotNull(AppRoute.target(AppTargetKind.PROJECT, projectId)),
                    ) { opened = it }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_note_detail_$projectId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("project_note_detail_body").assertExists()
        compose.onNodeWithTag("project_note_$noteId").assertExists()
        compose.onNodeWithTag("project_note_$personId").assertExists()
        compose.onNodeWithText("Foreign private note").assertDoesNotExist()
        compose.onNodeWithTag("project_note_$noteId").performClick()
        assertEquals(AppRoute.target(AppTargetKind.NOTE, noteId), opened)
    }

    @Test fun aProjectIdCannotResolveAsANoteRoute() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val id = UUID.nameUUIDFromBytes("synthetic-wrong-kind-target".toByteArray()).toString()
        ProtectedDomainRepository(databaseRule.database).createObject(scope, id, DomainKind.PROJECT,
            "Synthetic project only", "Fictional body", null, "2026-11-16T09:00:00Z")

        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") {
                    ActionTargetDetailScreen(requireNotNull(AppRoute.target(AppTargetKind.NOTE, id))) { }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("project_note_detail_missing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("project_note_detail_${id}").assertDoesNotExist()
    }
}

package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.CaptureSessionState
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryArchiveScreenTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun memorySearchOpensAndDeletesOneWholeConversationTurn() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedConversationRepository(databaseRule.database)
        repository.appendTurn(scopeId, "synthetic-memory-turn", "Synthetic archive needle from user", "Synthetic answer with provenance")

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("memory_search").performTextInput("archive needle")
        compose.mainClock.advanceTimeBy(300)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("memory_turn_synthetic-memory-turn").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("memory_turn_synthetic-memory-turn").performScrollTo().performClick()
        compose.onNodeWithTag("memory_turn_delete").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_turn_delete_confirm").assertIsDisplayed().performClick()

        compose.waitUntil(10_000) { runBlocking { repository.turn(scopeId, "synthetic-memory-turn").isEmpty() } }
        assertTrue(repository.turn(scopeId, "synthetic-memory-turn").isEmpty())
    }

    @Test fun ownerCanAddProfileFactManuallyAndSeeThatItHasNoConversationSource() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedProfileMemoryRepository(databaseRule.database)

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("profile_fact_add").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile_fact_add").performScrollTo().performClick()
        compose.onNodeWithTag("profile_fact_editor").performTextInput("Synthetic owner preference for Azerbaijani")
        compose.onNodeWithTag("profile_fact_category_identity").performClick()
        compose.onNodeWithTag("profile_fact_editor_save").performClick()

        compose.waitUntil(10_000) { runBlocking { repository.list(scopeId).isNotEmpty() } }
        val saved = repository.list(scopeId).single()
        assertTrue(saved.sourceTurnIds.isEmpty())
        assertEquals("user_entered", saved.revisions.single().provenance)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("profile_fact_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile_fact_0").performScrollTo().performClick()
        compose.onNodeWithTag("profile_fact_manual_source").assertIsDisplayed()
    }

    @Test fun conversationBackedProfileFactShowsItsSourceInsteadOfManualProvenance() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedProfileMemoryRepository(databaseRule.database)
        repository.saveUserStated(scopeId, "preference", "Synthetic likes concise summaries",
            "synthetic-profile-source-turn", "Synthetic: I like concise summaries.")

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("profile_fact_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("profile_fact_0").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("profile_fact_conversation_source").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("profile_fact_conversation_source").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("profile_fact_manual_source").fetchSemanticsNodes().isEmpty())
    }

    @Test fun olderConversationPageCanBeOpenedFromMemoryScreen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedConversationRepository(databaseRule.database)
        repeat(35) { index ->
            repository.appendTurn(scopeId, "synthetic-page-turn-$index", "Synthetic conversation $index", "Synthetic reply $index")
        }

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("memory_turn_synthetic-page-turn-5").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("memory_turn_synthetic-page-turn-0").assertCountEquals(0)
        compose.onNodeWithTag("memory_conversation_load_more").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("memory_turn_synthetic-page-turn-0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun finiteRetentionChoiceShowsWarningBeforePruning() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedConversationRepository(databaseRule.database)
        val now = System.currentTimeMillis()
        repository.appendTurn(scopeId, "synthetic-old-retention-turn", "Synthetic old dialogue", "Synthetic old answer",
            now - 40L * 86_400_000L)

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("memory_retention").performScrollTo().performClick()
        compose.onNodeWithTag("memory_retention_option_30").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_retention_confirm").assertIsDisplayed()
        assertTrue(repository.turn(scopeId, "synthetic-old-retention-turn").isNotEmpty())
        compose.onNodeWithTag("memory_retention_confirm").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.turn(scopeId, "synthetic-old-retention-turn").isEmpty() } }
    }

    @Test fun memoryShowsAndDeletesDerivedEpisodeWithoutDeletingItsSourceTurn() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scopeId = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedConversationRepository(databaseRule.database)
        repository.appendTurn(scopeId, "synthetic-episode-ui-turn", "Synthetic source: request a repair invoice next week.",
            "Synthetic reply.")
        val episode = repository.saveEpisode(scopeId, "Synthetic repair follow-up",
            "Request the repair invoice next week.", listOf("synthetic-episode-ui-turn"))

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") {
                        WorkingMemoryScreen(CaptureSessionState(ready = true, loading = false), {}, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("memory_episode_0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("memory_episode_0").performScrollTo().performClick()
        compose.onNodeWithTag("memory_episode_delete").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_episode_delete_confirm").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { runBlocking { repository.episode(scopeId, episode.episodeId) == null } }
        assertTrue(repository.turn(scopeId, "synthetic-episode-ui-turn").isNotEmpty())
    }
}

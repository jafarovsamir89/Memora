package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
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
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
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

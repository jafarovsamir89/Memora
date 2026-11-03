package ai.memora.app

import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProtectedConversationRepositoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun searchFindsFullTurnBeyondFormer240MessageWindow() = runBlocking {
        val repository = ProtectedConversationRepository(databaseRule.database)
        repeat(130) { index ->
            val marker = if (index == 0) "synthetic archive needle" else "synthetic ordinary dialogue $index"
            repository.appendTurn("scope-memory", "turn-$index", marker, "synthetic reply $index", 1_800_000_000_000L + index)
        }

        val found = repository.searchTurns("scope-memory", "archive needle", limit = 5)
        assertEquals(1, found.size)
        assertEquals("turn-0", found.single().turnId)
        assertEquals("synthetic archive needle", found.single().userMessage)
        assertEquals("synthetic reply 0", found.single().assistantMessage)
        assertEquals(100, repository.recentTurns("scope-memory", 100).size)
    }

    @Test fun deletingTurnRemovesUserAndAssistantTogetherOnlyInRequestedScope() = runBlocking {
        val repository = ProtectedConversationRepository(databaseRule.database)
        repository.appendTurn("scope-a", "same-turn-id", "synthetic first user", "synthetic first answer")
        repository.appendTurn("scope-a", "keep-turn", "synthetic keep user", "synthetic keep answer")
        repository.appendTurn("scope-b", "same-turn-id", "synthetic other-scope user", "synthetic other-scope answer")

        assertTrue(repository.deleteTurn("scope-a", "same-turn-id"))
        assertTrue(repository.turn("scope-a", "same-turn-id").isEmpty())
        assertEquals(2, repository.turn("scope-a", "keep-turn").size)
        assertEquals(2, repository.turn("scope-b", "same-turn-id").size)
        assertFalse(repository.deleteTurn("scope-a", "same-turn-id"))
    }

    @Test fun retentionIsPersistedAndPrunesExpiredWholeTurnsOnlyAfterExplicitSelection() = runBlocking {
        val repository = ProtectedConversationRepository(databaseRule.database)
        val now = System.currentTimeMillis()
        val old = now - 31L * 86_400_000L
        repository.appendTurn("scope-retention", "old-turn", "synthetic old user", "synthetic old answer", old)
        repository.appendTurn("scope-retention", "current-turn", "synthetic current user", "synthetic current answer", now)
        assertEquals(0, repository.retentionDays("scope-retention"))
        assertEquals(0, repository.applyRetention("scope-retention", now))
        assertEquals(2, repository.turn("scope-retention", "old-turn").size)

        assertTrue(repository.setRetentionDays("scope-retention", 30, now) > 0)
        assertEquals(30, repository.retentionDays("scope-retention"))
        assertTrue(repository.turn("scope-retention", "old-turn").isEmpty())
        assertEquals(2, repository.turn("scope-retention", "current-turn").size)
        assertTrue(runCatching { repository.setRetentionDays("scope-retention", 7, now) }.isFailure)
    }
}

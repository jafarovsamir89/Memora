package ai.memora.app

import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.AgentActionResultRow
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

    @Test fun actionCardsPersistWithTheirSourceTurnAndDeleteWithThatTurn() = runBlocking {
        val repository = ProtectedConversationRepository(databaseRule.database)
        val turnId = java.util.UUID.randomUUID().toString()
        val targetId = java.util.UUID.randomUUID().toString()
        val applied = AgentActionResultRow(java.util.UUID.randomUUID().toString(), "scope-actions", turnId,
            "record_money_event", "APPLIED", "receipt_recorded", "finance", targetId, 1_800_000_000_000L)
        val pending = AgentActionResultRow(java.util.UUID.randomUUID().toString(), "scope-actions", turnId,
            "delete_memory_object", "NEEDS_CONFIRMATION", "confirmation_required", "memory", null, 1_800_000_000_001L)
        repository.appendTurn("scope-actions", turnId, "synthetic source statement", "synthetic answer",
            1_800_000_000_000L, listOf(applied, pending))

        assertEquals(listOf(applied, pending), repository.actionResults("scope-actions", turnId))
        assertEquals(listOf(applied, pending), repository.actionResultsForTurns("scope-actions", listOf(turnId))[turnId])
        assertTrue(repository.deleteTurn("scope-actions", turnId))
        assertTrue(repository.actionResults("scope-actions", turnId).isEmpty())
        assertTrue(repository.turn("scope-actions", turnId).isEmpty())
    }

    @Test fun retentionIsPersistedAndPrunesExpiredWholeTurnsOnlyAfterExplicitSelection() = runBlocking {
        val repository = ProtectedConversationRepository(databaseRule.database)
        val now = System.currentTimeMillis()
        val old = now - 31L * 86_400_000L
        val oldId = java.util.UUID.randomUUID().toString()
        val currentId = java.util.UUID.randomUUID().toString()
        repository.appendTurn("scope-retention", "old-turn", "synthetic old user", "synthetic old answer", old,
            listOf(AgentActionResultRow(oldId, "scope-retention", "old-turn", "create_task", "APPLIED", "task_created",
                "task", java.util.UUID.randomUUID().toString(), old)))
        repository.appendTurn("scope-retention", "current-turn", "synthetic current user", "synthetic current answer", now,
            listOf(AgentActionResultRow(currentId, "scope-retention", "current-turn", "create_task", "APPLIED", "task_created",
                "task", java.util.UUID.randomUUID().toString(), now)))
        assertEquals(0, repository.retentionDays("scope-retention"))
        assertEquals(0, repository.applyRetention("scope-retention", now))
        assertEquals(2, repository.turn("scope-retention", "old-turn").size)

        assertTrue(repository.setRetentionDays("scope-retention", 30, now) > 0)
        assertEquals(30, repository.retentionDays("scope-retention"))
        assertTrue(repository.turn("scope-retention", "old-turn").isEmpty())
        assertTrue(repository.actionResults("scope-retention", "old-turn").isEmpty())
        assertEquals(2, repository.turn("scope-retention", "current-turn").size)
        assertEquals(1, repository.actionResults("scope-retention", "current-turn").size)
        assertTrue(runCatching { repository.setRetentionDays("scope-retention", 7, now) }.isFailure)
    }
}

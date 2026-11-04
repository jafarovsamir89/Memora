package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileMemoryRepositoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun explicitFactsAreScopedDeduplicatedVersionedAndUserEditable() = runBlocking {
        val repo = ProtectedProfileMemoryRepository(databaseRule.database)
        val scope = "synthetic-profile-scope"
        val otherScope = "synthetic-other-profile-scope"
        val source1 = "synthetic-profile-turn-1"
        val first = repo.saveUserStated(scope, "preference", "I prefer concise Azerbaijani answers",
            source1, "Synthetic: Please keep your answers concise and in Azerbaijani.", nowMillis = 1_000)
        assertEquals(1L, first.revision)
        assertEquals(listOf(source1), first.sourceTurnIds)
        assertEquals("user_stated", first.revisions.single().provenance)

        val source2 = "synthetic-profile-turn-2"
        val duplicate = repo.saveUserStated(scope, "preference", "I prefer concise Azerbaijani answers.",
            source2, "Synthetic repeat: concise Azerbaijani answers work best for me.", nowMillis = 2_000)
        assertEquals(first.factId, duplicate.factId)
        assertEquals(1L, duplicate.revision)
        assertEquals(listOf(source1, source2), duplicate.sourceTurnIds)
        assertTrue(repo.list(scope, "Azerbaijani concise").any { it.factId == first.factId })
        assertTrue(repo.list(otherScope, "Azerbaijani").isEmpty())

        val correctedSource = "synthetic-profile-turn-correction"
        val corrected = repo.correctFromUser(scope, first.factId, 1, "preference", "I prefer detailed answers",
            correctedSource, "Synthetic correction: detailed answers suit me better.", nowMillis = 3_000)
        assertEquals(2L, corrected.revision)
        assertEquals(listOf(1L, 2L), corrected.revisions.map { it.revision })
        assertEquals("I prefer concise Azerbaijani answers", corrected.revisions.first().fact)
        assertEquals("user_corrected", corrected.revisions.last().provenance)
        assertEquals(correctedSource, corrected.revisions.last().sourceTurnId)
        assertEquals(listOf(source1, source2, correctedSource), corrected.sourceTurnIds)

        val manuallyEdited = repo.editManually(scope, first.factId, 2, "preference", "Detailed replies only when useful", nowMillis = 4_000)
        assertEquals(3L, manuallyEdited.revision)
        assertEquals("user_edited", manuallyEdited.revisions.last().provenance)
        assertNull(manuallyEdited.revisions.last().sourceTurnId)

        assertFalse(repo.delete(scope, first.factId, 2))
        assertTrue(repo.delete(scope, first.factId, 3))
        assertNull(repo.get(scope, first.factId))
        assertTrue(repo.list(scope).isEmpty())
    }

    @Test fun exactSourceTurnDeletionAndRetentionRemoveDerivedProfileFacts() = runBlocking {
        val db = databaseRule.database
        val repo = ProtectedProfileMemoryRepository(db)
        val conversations = ProtectedConversationRepository(db)
        val now = 1_800_000_000_000L
        val oldTurn = "synthetic-profile-retention-old"
        val retainedTurn = "synthetic-profile-retention-new"
        val oldFact = repo.saveUserStated("synthetic-profile-retention", "goal", "Finish synthetic repair plan",
            oldTurn, "Synthetic: I want to finish the repair plan.", nowMillis = now - 40L * 86_400_000L)
        val retainedFact = repo.saveUserStated("synthetic-profile-retention", "context", "Work from Baku",
            retainedTurn, "Synthetic: I work from Baku.", nowMillis = now)

        assertEquals(1, conversations.setRetentionDays("synthetic-profile-retention", 30, nowMillis = now))
        assertNull(repo.get("synthetic-profile-retention", oldFact.factId))
        assertEquals(retainedFact.factId, repo.get("synthetic-profile-retention", retainedFact.factId)?.factId)

        assertTrue(conversations.deleteTurn("synthetic-profile-retention", retainedTurn))
        assertNull(repo.get("synthetic-profile-retention", retainedFact.factId))
        assertTrue(repo.list("synthetic-profile-retention").isEmpty())
    }

    @Test fun correctingProfileFactInvalidatesOnlyEpisodesBasedOnSupersededSources() = runBlocking {
        val db = databaseRule.database
        val profiles = ProtectedProfileMemoryRepository(db)
        val conversations = ProtectedConversationRepository(db)
        val scope = "synthetic-correction-propagation"
        val originalSource = "synthetic-fact-source-original"
        val duplicateSource = "synthetic-fact-source-repeat"
        val correctionSource = "synthetic-fact-source-correction"
        val unrelatedSource = "synthetic-unrelated-episode-source"

        val fact = profiles.saveUserStated(scope, "preference", "Prefers short replies",
            originalSource, "Synthetic: I prefer short replies.", nowMillis = 1_000)
        profiles.saveUserStated(scope, "preference", "Prefers short replies",
            duplicateSource, "Synthetic: Short replies are best for me.", nowMillis = 2_000)
        conversations.appendTurn(scope, unrelatedSource, "Synthetic: Keep the repair estimate for Friday.",
            "Synthetic assistant reply.", 3_000)
        val firstDerived = conversations.saveEpisode(scope, "Synthetic reply preference",
            "The owner prefers short replies.", listOf(originalSource), nowMillis = 4_000)
        val repeatedDerived = conversations.saveEpisode(scope, "Synthetic concise response preference",
            "Short replies are preferred.", listOf(duplicateSource), nowMillis = 5_000)
        val unrelatedDerived = conversations.saveEpisode(scope, "Synthetic repair follow-up",
            "Keep the repair estimate for Friday.", listOf(unrelatedSource), nowMillis = 6_000)

        val corrected = profiles.correctFromUser(scope, fact.factId, fact.revision, "preference",
            "Prefers detailed replies", correctionSource,
            "Synthetic correction: I prefer detailed replies now.", nowMillis = 7_000)

        assertEquals("Prefers detailed replies", corrected.fact)
        assertEquals(2L, corrected.revision)
        assertNull(conversations.episode(scope, firstDerived.episodeId))
        assertNull(conversations.episode(scope, repeatedDerived.episodeId))
        assertEquals(unrelatedDerived.episodeId,
            conversations.episode(scope, unrelatedDerived.episodeId)?.episodeId)
        assertTrue(conversations.turn(scope, originalSource).any { it.role == "user" })
        assertTrue(conversations.turn(scope, duplicateSource).any { it.role == "user" })
        assertTrue(conversations.turn(scope, correctionSource).any { it.role == "user" })
        assertEquals(listOf(1L, 2L), profiles.get(scope, fact.factId)?.revisions?.map { it.revision })

        val correctionDerived = conversations.saveEpisode(scope, "Synthetic corrected preference",
            "The owner now prefers detailed replies.", listOf(correctionSource), nowMillis = 8_000)
        val manuallyEdited = profiles.editManually(scope, fact.factId, 2, "preference",
            "Prefers detailed replies when a topic is complex", nowMillis = 9_000)
        assertEquals(3L, manuallyEdited.revision)
        assertEquals(listOf(1L, 2L, 3L), manuallyEdited.revisions.map { it.revision })
        assertNull(conversations.episode(scope, correctionDerived.episodeId))
        assertTrue(conversations.turn(scope, correctionSource).any { it.role == "user" })
    }

    @Test fun editedFactsCannotCollideAndSourceMessagesAreNotModelAuthored() = runBlocking {
        val repo = ProtectedProfileMemoryRepository(databaseRule.database)
        val scope = "synthetic-profile-collision"
        val first = repo.saveUserStated(scope, "preference", "Prefers local backups",
            "synthetic-profile-source-a", "Synthetic: I prefer local backups.", nowMillis = 1)
        val second = repo.saveUserStated(scope, "preference", "Prefers encrypted exports",
            "synthetic-profile-source-b", "Synthetic: I prefer encrypted exports.", nowMillis = 2)

        val collision = runCatching {
            repo.correctFromUser(scope, first.factId, first.revision, "preference", second.fact,
                "synthetic-profile-source-c", "Synthetic: Make the first fact match the second.", nowMillis = 3)
        }
        assertTrue(collision.isFailure)
        assertEquals(first.fact, repo.get(scope, first.factId)?.fact)
        assertTrue(repo.delete(scope, first.factId, first.revision))
        assertTrue(repo.delete(scope, second.factId, second.revision))
    }
}

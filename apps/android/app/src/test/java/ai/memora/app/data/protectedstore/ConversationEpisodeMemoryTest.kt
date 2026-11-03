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
class ConversationEpisodeMemoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun episodeIsScopedVersionedSearchableAndInvalidatedWithAnySourceTurn() = runBlocking {
        val repo = ProtectedConversationRepository(databaseRule.database)
        val scope = "synthetic-episode-scope"
        val otherScope = "synthetic-other-scope"
        val firstTurn = "synthetic-turn-bmw-1"
        val secondTurn = "synthetic-turn-bmw-2"
        repo.appendTurn(scope, firstTurn, "Synthetic: repair shop quoted 300 manat for the BMW.", "Saved synthetic repair context.", 1_000)
        repo.appendTurn(scope, secondTurn, "Synthetic: I paid 100 manat on Friday; ask Elchin for the remaining invoice.", "Saved synthetic payment context.", 2_000)
        repo.appendTurn(otherScope, "synthetic-turn-foreign", "Synthetic foreign scope marker.", "Not visible here.", 2_000)

        val initial = repo.saveEpisode(scope, "Synthetic BMW repair", "Quoted 300; 100 paid Friday; request the remaining invoice from Elchin.",
            listOf(firstTurn, secondTurn), nowMillis = 3_000)
        val replay = repo.saveEpisode(scope, initial.title, initial.summary, listOf(secondTurn, firstTurn), nowMillis = 4_000)
        assertEquals(initial.episodeId, replay.episodeId)
        assertEquals(1L, replay.revision)
        assertEquals(3_000L, replay.updatedAtMillis)
        assertEquals(listOf(firstTurn, secondTurn), replay.sourceTurnIds)
        assertEquals(listOf(1L), replay.revisions.map { it.revision })
        assertEquals(1, repo.searchEpisodes(scope, "BMW invoice", limit = 5).size)
        assertTrue(repo.searchEpisodes(otherScope, "BMW invoice", limit = 5).isEmpty())
        assertFalse(repo.episodeSourcesAvailable(scope, listOf("synthetic-turn-foreign")))

        val revised = repo.saveEpisode(scope, initial.title, "Revised: 300 quote; 100 paid Friday; request the remaining invoice.",
            listOf(firstTurn, secondTurn), nowMillis = 5_000)
        assertEquals(2L, revised.revision)
        assertEquals(5_000L, revised.updatedAtMillis)
        assertEquals(listOf(1L, 2L), revised.revisions.map { it.revision })
        assertEquals(initial.summary, revised.revisions.first().summary)

        assertTrue(repo.deleteTurn(scope, firstTurn))
        assertTrue(repo.searchEpisodes(scope, "BMW invoice", limit = 5).isEmpty())
        assertNotNull(repo.turnEvidence(scope, secondTurn))
        assertNull(repo.episode(scope, revised.episodeId))
    }

    @Test fun retentionExpiryRemovesTheWholeEpisodeAndKeepsRetainedSources() = runBlocking {
        val repo = ProtectedConversationRepository(databaseRule.database)
        val scope = "synthetic-retention-scope"
        val now = 1_800_000_000_000L
        val oldTurn = "synthetic-retained-source-old"
        val newTurn = "synthetic-retained-source-new"
        repo.appendTurn(scope, oldTurn, "Synthetic old user turn.", "Synthetic reply.", now - 40L * 86_400_000L)
        repo.appendTurn(scope, newTurn, "Synthetic current user turn.", "Synthetic reply.", now)
        val episode = repo.saveEpisode(scope, "Synthetic retained context", "Combined old and current synthetic context.",
            listOf(oldTurn, newTurn), nowMillis = now)

        assertEquals(2, repo.setRetentionDays(scope, 30, nowMillis = now))
        assertTrue(repo.searchEpisodes(scope, "retained context", limit = 5).isEmpty())
        assertTrue(repo.turn(scope, oldTurn).isEmpty())
        assertEquals(2, repo.turn(scope, newTurn).size)
        assertNull(repo.episode(scope, episode.episodeId))
    }

    @Test fun lexicalSearchPagesPastTheNewestHundredEpisodes() = runBlocking {
        val db = databaseRule.database
        val scope = "synthetic-paged-search-scope"
        val dao = db.protectedStoreDao()
        val messages = (0..100).map { index ->
            AgentConversationMessageRow(scopeId = scope, turnId = "synthetic-paged-turn-$index", role = "user",
                message = "Synthetic source $index", createdAtMillis = index.toLong())
        }
        val episodes = (0..100).map { index ->
            ConversationEpisodeRow("synthetic-paged-episode-$index", scope,
                if (index == 0) "Old unique needle" else "Recent context $index",
                if (index == 0) "Find this older source-backed episode." else "Ordinary synthetic summary.",
                index.toLong(), index.toLong(), 1)
        }
        val links = (0..100).map { index -> ConversationEpisodeSourceRow(scope,
            "synthetic-paged-episode-$index", "synthetic-paged-turn-$index") }
        val revisions = episodes.map { episode -> ConversationEpisodeRevisionRow(scope, episode.episodeId,
            episode.revision, episode.title, episode.summary, episode.updatedAtMillis) }
        dao.putConversationMessages(messages)
        episodes.forEach { dao.putConversationEpisode(it) }
        dao.putConversationEpisodeSources(links)
        dao.putConversationEpisodeRevisions(revisions)

        val result = ProtectedConversationRepository(db).searchEpisodes(scope, "needle", limit = 5)
        assertEquals(listOf("synthetic-paged-episode-0"), result.map { it.episodeId })
        assertEquals(listOf("synthetic-paged-turn-0"), result.single().sourceTurnIds)
    }
}

package ai.memora.app

import android.os.Bundle
import android.os.SystemClock
import androidx.room.withTransaction
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Seeds and measures a bounded long archive only in the explicitly named synthetic emulator profile. */
internal object SyntheticConversationVolumeProbe {
    private const val MARKER = "synthetic-volume-conversation-"
    private const val LINEAGE_SENTINEL_TURN = "synthetic-stage-one-action-card"
    private const val SEARCH_NEEDLE = "longarchive needlezero"
    private const val PAGE_SIZE = 30
    private const val DAO_PAGE_SIZE = 200
    private const val REPETITIONS = 20

    fun run(database: AgentDatabase, scope: String, targetCount: Int): Bundle {
        require(targetCount in setOf(100, 1_000)) { "unsupported_synthetic_conversation_volume" }
        val dao = database.protectedStoreDao()
        val repository = ProtectedConversationRepository(database)

        val beforeRows = readAllRows(database, scope)
        check(beforeRows.all { it.turnId == LINEAGE_SENTINEL_TURN || it.turnId.startsWith(MARKER) }) {
            "unexpected_conversation_rows_in_synthetic_volume_scope"
        }
        val existing = beforeRows.filter { it.turnId.startsWith(MARKER) }
        check(existing.size % 2 == 0) { "conversation_fixture_has_orphan_message" }
        val existingCount = existing.size / 2
        check(existingCount <= targetCount) { "conversation_fixture_cannot_shrink" }
        for (index in 0 until existingCount) {
            check(matchesTurn(existing.filter { it.turnId == turnId(index) }, expectedTurn(scope, index))) {
                "conversation_fixture_not_contiguous_or_changed"
            }
        }
        val sentinelRows = beforeRows.filter { it.turnId == LINEAGE_SENTINEL_TURN }
        check(sentinelRows.isEmpty() || sentinelRows.map { it.role }.toSet() == setOf("user", "model")) {
            "unexpected_lineage_sentinel_shape"
        }

        var inserted = 0
        val seedStarted = SystemClock.elapsedRealtimeNanos()
        if (existingCount < targetCount) {
            val rows = (existingCount until targetCount).flatMap { expectedTurn(scope, it) }
            runBlocking { database.withTransaction { dao.putConversationMessages(rows) } }
            inserted = rows.size / 2
        }
        val seedMillis = (SystemClock.elapsedRealtimeNanos() - seedStarted) / 1_000_000L

        val afterRows = readAllRows(database, scope)
        val allFixtureRows = afterRows.filter { it.turnId.startsWith(MARKER) }
        check(allFixtureRows.size == targetCount * 2) { "conversation_fixture_row_count_mismatch" }
        for (index in 0 until targetCount) {
            check(matchesTurn(allFixtureRows.filter { it.turnId == turnId(index) }, expectedTurn(scope, index))) {
                "conversation_fixture_readback_mismatch"
            }
        }

        var cursor: Long? = null
        val allTurnIds = mutableListOf<String>()
        var pageCount = 0
        do {
            val page = runBlocking { repository.conversationTurnPage(scope, "", cursor, PAGE_SIZE) }
            allTurnIds += page.turns.map { it.turnId }
            cursor = page.nextBeforeSequence
            pageCount++
            check(pageCount <= (targetCount / PAGE_SIZE) + 4) { "conversation_pagination_did_not_advance" }
        } while (cursor != null)
        check(allTurnIds.size == targetCount + if (sentinelRows.isEmpty()) 0 else 1) {
            "conversation_pagination_count_mismatch"
        }
        check(allTurnIds.distinct().size == allTurnIds.size) { "conversation_pagination_duplicate_turn" }
        check(allTurnIds.containsAll((0 until targetCount).map(::turnId))) { "conversation_pagination_missing_turn" }

        val expectedOldest = turnId(0)
        val oldestSearch = runBlocking { repository.conversationTurnPage(scope, SEARCH_NEEDLE, limit = PAGE_SIZE) }
        check(oldestSearch.turns.singleOrNull()?.turnId == expectedOldest && !oldestSearch.hasMore) {
            "conversation_oldest_search_inexact"
        }
        val searchSamples = LongArray(REPETITIONS) {
            val started = SystemClock.elapsedRealtimeNanos()
            val page = runBlocking { repository.conversationTurnPage(scope, SEARCH_NEEDLE, limit = PAGE_SIZE) }
            check(page.turns.singleOrNull()?.turnId == expectedOldest && !page.hasMore) {
                "conversation_oldest_search_sample_mismatch"
            }
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        }

        return Bundle().apply {
            putString("conversation_volume_status", "synthetic_conversation_volume_passed")
            putInt("synthetic_conversation_turns", targetCount)
            putInt("conversation_rows_inserted_this_run", inserted)
            putLong("conversation_seed_ms", seedMillis)
            putInt("conversation_archive_pages", pageCount)
            putBoolean("conversation_pagination_exact", true)
            putString("conversation_oldest_search_expected_turn", expectedOldest)
            putString("conversation_fixture_digest_sha256", digest(allFixtureRows))
            putLong("conversation_oldest_search_p50_ms", percentile(searchSamples, 0.50))
            putLong("conversation_oldest_search_p95_ms", percentile(searchSamples, 0.95))
            putInt("conversation_oldest_search_sample_count", searchSamples.size)
            putString("conversation_measurement_scope", "warm_local_encrypted_room_archive_search_no_ui_network_or_model")
        }
    }

    private fun readAllRows(database: AgentDatabase, scope: String): List<AgentConversationMessageRow> = runBlocking {
        val dao = database.protectedStoreDao()
        buildList {
            var before: Long? = null
            while (true) {
                val page = dao.conversationPage(scope, before, DAO_PAGE_SIZE)
                addAll(page)
                if (page.size < DAO_PAGE_SIZE) break
                before = page.last().sequence
            }
        }
    }

    private fun expectedTurn(scope: String, index: Int): List<AgentConversationMessageRow> {
        val suffix = index.toString().padStart(4, '0')
        val filler = "Synthetic archived dialogue segment $suffix contains ordinary local test text. ".repeat(6)
        val language = when (index % 3) {
            0 -> "AZ: Bu arxiv sınağıdır, sabit yaddaş qeydi $suffix."
            1 -> "RU: Это синтетическая длинная история, запись $suffix."
            else -> "EN: This is a synthetic archived conversation, entry $suffix."
        }
        val userText = if (index == 0) "$SEARCH_NEEDLE. $language $filler" else "$language $filler"
        val assistantText = "Synthetic assistant response $suffix. $filler"
        val createdAt = 1_800_000_000_000L + index
        val id = turnId(index)
        return listOf(
            AgentConversationMessageRow(scopeId = scope, turnId = id, role = "user", message = userText, createdAtMillis = createdAt),
            AgentConversationMessageRow(scopeId = scope, turnId = id, role = "model", message = assistantText, createdAtMillis = createdAt),
        )
    }

    private fun matchesTurn(actual: List<AgentConversationMessageRow>, expected: List<AgentConversationMessageRow>): Boolean =
        actual.size == 2 && actual.sortedBy { it.role } == expected.sortedBy { it.role }
            .map { it.copy(sequence = actual.firstOrNull { row -> row.role == it.role }?.sequence ?: 0) }

    private fun turnId(index: Int): String = MARKER + index.toString().padStart(4, '0')

    private fun digest(rows: List<AgentConversationMessageRow>): String {
        val canonical = rows.sortedWith(compareBy<AgentConversationMessageRow> { it.turnId }.thenBy { it.role })
            .joinToString("\n") { "${it.turnId}|${it.role}|${it.createdAtMillis}|${it.message}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun percentile(values: LongArray, fraction: Double): Long {
        val sorted = values.sorted()
        return sorted[((sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }
}

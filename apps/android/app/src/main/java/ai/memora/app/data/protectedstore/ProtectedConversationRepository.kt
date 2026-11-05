package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.security.MessageDigest
import java.text.Normalizer

internal data class ConversationEvidence(
    val turnId: String,
    val role: String,
    val createdAtMillis: Long,
    val score: Int,
    val snippet: String,
)

internal data class ConversationTurnEvidence(
    val turnId: String,
    val createdAtMillis: Long,
    val userMessage: String,
    val assistantMessage: String,
    val matchedSnippet: String?,
)

internal data class ConversationTurnPage(
    val turns: List<ConversationTurnEvidence>,
    val nextBeforeSequence: Long?,
) {
    val hasMore: Boolean get() = nextBeforeSequence != null
}

internal data class ConversationEpisodeEvidence(
    val episodeId: String,
    val title: String,
    val summary: String,
    val sourceTurnIds: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val revision: Long,
    val revisions: List<ConversationEpisodeRevisionEvidence> = emptyList(),
    val score: Int = 0,
)

internal data class ConversationEpisodeRevisionEvidence(
    val revision: Long,
    val title: String,
    val summary: String,
    val recordedAtMillis: Long,
)

/** A single owner-scoped conversation shared by voice and text, stored only in the encrypted Room database. */
internal class ProtectedConversationRepository(private val db: AgentDatabase) {
    private val dao = db.protectedStoreDao()

    suspend fun recent(scopeId: String, limit: Int = 80): List<AgentConversationMessageRow> {
        require(scopeId.isNotBlank() && limit in 1..240)
        return dao.recentConversationMessages(scopeId, limit).asReversed()
    }

    suspend fun recentContext(scopeId: String, limit: Int = 12): List<AgentConversationMessageRow> =
        recent(scopeId, limit)

    suspend fun recentTurns(scopeId: String, limit: Int = 20): List<ConversationTurnEvidence> {
        require(scopeId.isNotBlank() && limit in 1..100)
        val rows = dao.recentConversationMessages(scopeId, limit * 2)
        return rows.groupBy { it.turnId }.values.mapNotNull(::toTurnEvidence)
            .sortedByDescending { it.createdAtMillis }.take(limit)
    }

    /**
     * Returns exhaustive, newest-first archive pages. The cursor is the user-message sequence of the
     * last displayed turn, so a look-ahead match is deliberately reconsidered on the next request.
     * Search stays local and does not inherit the smaller candidate cap used for model context.
     */
    suspend fun conversationTurnPage(
        scopeId: String,
        query: String,
        beforeSequence: Long? = null,
        limit: Int = 30,
    ): ConversationTurnPage {
        require(scopeId.isNotBlank() && query.length <= 300 && (beforeSequence == null || beforeSequence > 0) && limit in 1..100)
        val foldedQuery = fold(query.trim())
        val terms = TOKEN.findAll(foldedQuery).map { fold(it.value) }.filter { it.length > 1 && it !in STOP_WORDS }.distinct().take(12).toList()
        if (query.isNotBlank() && terms.isEmpty()) return ConversationTurnPage(emptyList(), null)

        val matches = mutableListOf<Pair<Long, ConversationTurnEvidence>>()
        var cursor = beforeSequence
        while (true) {
            val page = dao.conversationPage(scopeId, cursor, PAGE_SIZE)
            if (page.isEmpty()) return ConversationTurnPage(matches.map { it.second }, null)
            cursor = page.last().sequence
            for (message in page) {
                if (message.role != "user") continue
                val turnRows = dao.conversationMessagesForTurn(scopeId, message.turnId)
                    .filter { it.scopeId == scopeId && it.turnId == message.turnId }
                val turn = toTurnEvidence(turnRows) ?: continue
                val evidenceText = listOf(turn.userMessage, turn.assistantMessage).joinToString("\n")
                val snippet = if (terms.isEmpty()) null else {
                    val tokens = TOKEN.findAll(evidenceText).toList()
                    val firstMatch = tokens.firstOrNull { token ->
                        val candidate = fold(token.value)
                        terms.any { term -> searchTokenMatches(term, candidate) }
                    } ?: continue
                    val start = (firstMatch.range.first - 180).coerceAtLeast(0)
                    evidenceText.substring(start, (start + 900).coerceAtMost(evidenceText.length))
                }
                matches += message.sequence to turn.copy(matchedSnippet = snippet)
                if (matches.size > limit) {
                    val visible = matches.take(limit)
                    return ConversationTurnPage(visible.map { it.second }, visible.last().first)
                }
            }
            if (page.size < PAGE_SIZE) return ConversationTurnPage(matches.map { it.second }, null)
        }
    }

    /** Search every retained page locally; returned evidence remains bounded and source-linked by turn ID. */
    suspend fun searchTurns(scopeId: String, query: String, limit: Int = 30): List<ConversationTurnEvidence> {
        require(limit in 1..100)
        if (query.isBlank()) return recentTurns(scopeId, limit)
        val hits = search(scopeId, query, limit = (limit * 2).coerceAtMost(100))
        return hits.groupBy { it.turnId }.values.mapNotNull { turnHits ->
            val turnId = turnHits.first().turnId
            val rows = dao.conversationMessagesForTurn(scopeId, turnId)
            toTurnEvidence(rows)?.copy(matchedSnippet = turnHits.maxByOrNull { it.score }?.snippet)
        }.sortedWith(compareByDescending<ConversationTurnEvidence> { turn ->
            hits.filter { it.turnId == turn.turnId }.maxOfOrNull { it.score } ?: 0
        }.thenByDescending { it.createdAtMillis }).take(limit)
    }

    suspend fun turn(scopeId: String, turnId: String): List<AgentConversationMessageRow> {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return dao.conversationMessagesForTurn(scopeId, turnId).filter { it.scopeId == scopeId }
    }

    suspend fun turnEvidence(scopeId: String, turnId: String): ConversationTurnEvidence? {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return toTurnEvidence(dao.conversationMessagesForTurn(scopeId, turnId)
            .filter { it.scopeId == scopeId && it.turnId == turnId })
    }

    suspend fun sourceUserTurn(scopeId: String, turnId: String): AgentConversationMessageRow? {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return dao.conversationMessagesForTurn(scopeId, turnId)
            .firstOrNull { it.scopeId == scopeId && it.turnId == turnId && it.role == "user" }
    }

    suspend fun actionResults(scopeId: String, turnId: String): List<AgentActionResultRow> {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return dao.actionResultsForTurn(scopeId, turnId).filter { it.scopeId == scopeId && it.turnId == turnId }
    }

    suspend fun actionResultsForTurns(scopeId: String, turnIds: List<String>): Map<String, List<AgentActionResultRow>> {
        require(scopeId.isNotBlank() && turnIds.size <= 120 && turnIds.all(String::isNotBlank))
        if (turnIds.isEmpty()) return emptyMap()
        return dao.actionResultsForTurns(scopeId, turnIds.distinct()).filter { it.scopeId == scopeId && it.turnId in turnIds }
            .groupBy { it.turnId }
    }

    /** A whole user/assistant turn is the smallest deletion unit, so search can never show an orphan reply. */
    suspend fun deleteTurn(scopeId: String, turnId: String): Boolean {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return db.withTransaction {
            dao.deleteEpisodesForConversationTurn(scopeId, turnId)
            dao.deleteProfileFactsForConversationTurn(scopeId, turnId)
            dao.deleteActionResultsForTurn(scopeId, turnId)
            dao.deleteConversationTurn(scopeId, turnId) > 0
        }
    }

    suspend fun retentionDays(scopeId: String): Int {
        require(scopeId.isNotBlank())
        return dao.conversationRetention(scopeId)?.retentionDays ?: RETENTION_FOREVER
    }

    /** Changing to a finite period explicitly prunes older whole turns in the same transaction. */
    suspend fun setRetentionDays(scopeId: String, days: Int, nowMillis: Long = System.currentTimeMillis()): Int = db.withTransaction {
        require(scopeId.isNotBlank() && days in ALLOWED_RETENTION_DAYS)
        dao.putConversationRetention(ConversationRetentionRow(scopeId, days, nowMillis))
        pruneExpired(scopeId, days, nowMillis)
    }

    suspend fun applyRetention(scopeId: String, nowMillis: Long = System.currentTimeMillis()): Int = db.withTransaction {
        val days = dao.conversationRetention(scopeId)?.retentionDays ?: RETENTION_FOREVER
        check(days in ALLOWED_RETENTION_DAYS)
        pruneExpired(scopeId, days, nowMillis)
    }

    /** Search the full retained encrypted archive locally and return only bounded, relevance-ranked evidence snippets. */
    suspend fun search(scopeId: String, query: String, daysBack: Int? = null, limit: Int = 8): List<ConversationEvidence> {
        require(scopeId.isNotBlank() && query.length <= 300 && limit in 1..100 && (daysBack == null || daysBack in 1..365))
        val queryFolded = fold(query)
        val terms = TOKEN.findAll(queryFolded).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.distinct().take(12).toList()
        if (terms.isEmpty() && daysBack == null) return emptyList()
        val cutoff = daysBack?.let { System.currentTimeMillis() - it.toLong() * 86_400_000L }
        val hits = mutableListOf<ConversationEvidence>()
        var beforeSequence: Long? = null
        while (true) {
            val page = dao.conversationPage(scopeId, beforeSequence, PAGE_SIZE)
            if (page.isEmpty()) break
            beforeSequence = page.last().sequence
            page.asSequence().filter { cutoff == null || it.createdAtMillis >= cutoff }.mapNotNull { row ->
            val tokens = TOKEN.findAll(row.message).toList()
            val foldedTokens = tokens.map { fold(it.value) }
            val matched = terms.count { term -> foldedTokens.any { token -> searchTokenMatches(term, token) } }
            if (terms.isNotEmpty() && matched == 0) return@mapNotNull null
            val first = tokens.indices.firstOrNull { index ->
                val token = foldedTokens[index]
                terms.any { term -> searchTokenMatches(term, token) }
            }
            if (terms.isNotEmpty() && first == null) return@mapNotNull null
            val start = (first?.let { tokens[it].range.first - 180 } ?: 0).coerceAtLeast(0)
            val end = (start + 900).coerceAtMost(row.message.length)
            val score = matched * 10 + (if (queryFolded.isNotBlank() && fold(row.message).contains(queryFolded)) 8 else 0) +
                (if (row.role == "user") 1 else 0)
                ConversationEvidence(row.turnId, row.role, row.createdAtMillis, score, row.message.substring(start, end))
            }.forEach { hit ->
                hits += hit
                if (hits.size > MAX_SEARCH_HITS) {
                    hits.sortWith(compareByDescending<ConversationEvidence> { it.score }.thenByDescending { it.createdAtMillis })
                    while (hits.size > MAX_SEARCH_HITS) hits.removeAt(hits.lastIndex)
                }
            }
            if (page.size < PAGE_SIZE) break
        }
        return hits.sortedWith(compareByDescending<ConversationEvidence> { it.score }
            .thenByDescending { it.createdAtMillis }.thenBy { it.turnId }).take(limit)
    }

    /** Store a derived summary only when each linked source is a retained user turn in this owner scope. */
    suspend fun saveEpisode(scopeId: String, title: String, summary: String, sourceTurnIds: List<String>,
        nowMillis: Long = System.currentTimeMillis()): ConversationEpisodeEvidence {
        require(scopeId.isNotBlank() && title.isNotBlank() && title.length <= 160)
        require(summary.isNotBlank() && summary.length <= 1_200)
        val sources = sourceTurnIds.distinct().sorted()
        require(sources.size in 1..8 && sources.all { it.isNotBlank() && it.length <= 100 })
        require(nowMillis >= 0)
        return db.withTransaction {
            val sourceRows = sources.map { sourceId ->
                val rows = dao.conversationMessagesForTurn(scopeId, sourceId)
                check(rows.any { it.scopeId == scopeId && it.turnId == sourceId && it.role == "user" }) {
                    "episode_source_unavailable"
                }
                ConversationEpisodeSourceRow(scopeId, episodeId(scopeId, sources), sourceId)
            }
            val id = episodeId(scopeId, sources)
            val existing = dao.conversationEpisode(scopeId, id)
            val normalizedTitle = title.trim()
            val normalizedSummary = summary.trim()
            val next = when {
                existing == null -> ConversationEpisodeRow(id, scopeId, normalizedTitle, normalizedSummary,
                    nowMillis, nowMillis, 1)
                existing.title == normalizedTitle && existing.summary == normalizedSummary -> existing
                else -> existing.copy(title = normalizedTitle, summary = normalizedSummary,
                    updatedAtMillis = maxOf(nowMillis, existing.updatedAtMillis), revision = Math.addExact(existing.revision, 1))
            }
            if (existing == null) {
                dao.putConversationEpisode(next)
                dao.putConversationEpisodeSources(sourceRows)
                dao.putConversationEpisodeRevision(ConversationEpisodeRevisionRow(scopeId, id, next.revision,
                    next.title, next.summary, next.updatedAtMillis))
            } else if (next != existing) {
                check(dao.updateConversationEpisode(next) == 1) { "episode_revision_update_failed" }
                dao.putConversationEpisodeRevision(ConversationEpisodeRevisionRow(scopeId, id, next.revision,
                    next.title, next.summary, next.updatedAtMillis))
            }
            val storedSources = dao.conversationEpisodeSources(scopeId, id).map { it.turnId }.sorted()
            check(storedSources == sources) { "episode_source_readback_mismatch" }
            val storedRevisions = dao.conversationEpisodeRevisions(scopeId, id).map(::toEpisodeRevision)
            check(storedRevisions.lastOrNull()?.revision == next.revision &&
                storedRevisions.lastOrNull()?.summary == next.summary) { "episode_revision_readback_mismatch" }
            ConversationEpisodeEvidence(next.episodeId, next.title, next.summary, storedSources,
                next.createdAtMillis, next.updatedAtMillis, next.revision, storedRevisions)
        }
    }

    suspend fun episodeSourcesAvailable(scopeId: String, sourceTurnIds: List<String>): Boolean {
        require(scopeId.isNotBlank() && sourceTurnIds.size in 1..8 && sourceTurnIds.distinct().size == sourceTurnIds.size)
        return sourceTurnIds.all { sourceId ->
            sourceId.isNotBlank() && sourceId.length <= 100 &&
                dao.conversationMessagesForTurn(scopeId, sourceId).any { it.scopeId == scopeId && it.turnId == sourceId && it.role == "user" }
        }
    }

    /** Search the local derived layer, verifying every source link before returning a summary to the model/UI. */
    suspend fun searchEpisodes(scopeId: String, query: String, limit: Int = 8): List<ConversationEpisodeEvidence> {
        require(scopeId.isNotBlank() && query.length <= 300 && limit in 1..30)
        val terms = TOKEN.findAll(fold(query)).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.distinct().take(12).toList()
        return db.withTransaction {
            val hits = mutableListOf<ConversationEpisodeEvidence>()
            var beforeUpdatedAt: Long? = null
            var beforeEpisodeId: String? = null
            while (true) {
                val page = dao.conversationEpisodePage(scopeId, beforeUpdatedAt, beforeEpisodeId, EPISODE_PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) {
                    val episode = verifyEpisode(scopeId, row) ?: continue
                    if (terms.isEmpty()) {
                        hits += episode
                        if (hits.size >= limit) break
                    } else {
                        val contentTokens = TOKEN.findAll(fold("${episode.title} ${episode.summary}")).map { it.value }.toList()
                        val score = terms.count { term -> contentTokens.any { token -> searchTokenMatches(term, token) } }
                        if (score > 0) hits += episode.copy(score = score * 10 + episode.revision.coerceAtMost(9).toInt())
                    }
                }
                beforeUpdatedAt = page.last().updatedAtMillis
                beforeEpisodeId = page.last().episodeId
                if (terms.isEmpty() && hits.size >= limit) break
                if (page.size < EPISODE_PAGE_SIZE) break
                if (terms.isNotEmpty() && hits.size > MAX_EPISODE_SEARCH_HITS) {
                    hits.sortWith(compareByDescending<ConversationEpisodeEvidence> { it.score }
                        .thenByDescending { it.updatedAtMillis }.thenBy { it.episodeId })
                    while (hits.size > MAX_EPISODE_SEARCH_HITS) hits.removeAt(hits.lastIndex)
                }
            }
            hits.sortedWith(compareByDescending<ConversationEpisodeEvidence> { it.score }
                .thenByDescending { it.updatedAtMillis }.thenBy { it.episodeId }).take(limit)
        }
    }

    suspend fun episode(scopeId: String, episodeId: String): ConversationEpisodeEvidence? {
        require(scopeId.isNotBlank() && episodeId.isNotBlank())
        return db.withTransaction { dao.conversationEpisode(scopeId, episodeId)?.let { verifyEpisode(scopeId, it) } }
    }

    /** Deleting a derived episode leaves its source dialogue intact. */
    suspend fun deleteEpisode(scopeId: String, episodeId: String): Boolean {
        require(scopeId.isNotBlank() && episodeId.isNotBlank())
        return db.withTransaction { dao.deleteConversationEpisode(scopeId, episodeId) > 0 }
    }

    private suspend fun verifyEpisode(scopeId: String, row: ConversationEpisodeRow): ConversationEpisodeEvidence? {
        val sources = dao.conversationEpisodeSources(scopeId, row.episodeId).map { it.turnId }.distinct().sorted()
        val valid = sources.isNotEmpty() && sources.size <= 8 && sources.all { sourceId ->
            dao.conversationMessagesForTurn(scopeId, sourceId).any { it.scopeId == scopeId && it.turnId == sourceId && it.role == "user" }
        }
        if (!valid) {
            dao.deleteConversationEpisode(scopeId, row.episodeId)
            return null
        }
        val revisions = dao.conversationEpisodeRevisions(scopeId, row.episodeId).map(::toEpisodeRevision)
        val latestRevision = revisions.lastOrNull()
        val completeHistory = revisions.size.toLong() == row.revision &&
            revisions.withIndex().all { (index, revision) -> revision.revision == index + 1L }
        if (!completeHistory || latestRevision == null || latestRevision.revision != row.revision ||
            latestRevision.title != row.title || latestRevision.summary != row.summary) {
            dao.deleteConversationEpisode(scopeId, row.episodeId)
            return null
        }
        return ConversationEpisodeEvidence(row.episodeId, row.title, row.summary, sources,
            row.createdAtMillis, row.updatedAtMillis, row.revision, revisions)
    }

    private fun toEpisodeRevision(row: ConversationEpisodeRevisionRow) = ConversationEpisodeRevisionEvidence(
        row.revision, row.title, row.summary, row.recordedAtMillis)

    suspend fun appendTurn(scopeId: String, turnId: String, userMessage: String, assistantMessage: String,
        createdAtMillis: Long = System.currentTimeMillis(), actionResults: List<AgentActionResultRow> = emptyList()) {
        require(scopeId.isNotBlank() && turnId.isNotBlank() && turnId.length <= 100)
        require(userMessage.isNotBlank() && userMessage.length <= 20_000)
        require(assistantMessage.isNotBlank() && assistantMessage.length <= 20_000)
        require(actionResults.size <= 32 && actionResults.all { row ->
            row.scopeId == scopeId && row.turnId == turnId && isUuid(row.resultId) &&
                row.toolName.matches(Regex("[a-z][a-z0-9_]{0,63}")) &&
                row.status in ACTION_STATUSES && row.code.matches(Regex("[a-z0-9_]{1,64}")) &&
                (row.targetKind == null || row.targetKind in ACTION_TARGET_KINDS) &&
                (row.targetId == null || isUuid(row.targetId)) && row.createdAtMillis >= 0
        })
        db.withTransaction {
            val existing = dao.conversationMessagesForTurn(scopeId, turnId)
            if (existing.isNotEmpty()) {
                val existingUser = existing.singleOrNull { it.role == "user" }
                val existingModel = existing.singleOrNull { it.role == "model" }
                check(existingUser?.message == userMessage && (existingModel == null || existingModel.message == assistantMessage) &&
                    existing.all { it.role == "user" || it.role == "model" })
                if (existingModel == null) dao.putConversationMessages(listOf(AgentConversationMessageRow(
                    scopeId = scopeId, turnId = turnId, role = "model", message = assistantMessage,
                    createdAtMillis = createdAtMillis)))
                if (actionResults.isNotEmpty()) dao.putActionResults(actionResults)
                val days = dao.conversationRetention(scopeId)?.retentionDays ?: RETENTION_FOREVER
                pruneExpired(scopeId, days, System.currentTimeMillis())
                return@withTransaction
            }
            dao.putConversationMessages(listOf(
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "user", message = userMessage,
                    createdAtMillis = createdAtMillis),
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "model", message = assistantMessage,
                    createdAtMillis = createdAtMillis),
            ))
            if (actionResults.isNotEmpty()) dao.putActionResults(actionResults)
            val days = dao.conversationRetention(scopeId)?.retentionDays ?: RETENTION_FOREVER
            pruneExpired(scopeId, days, System.currentTimeMillis())
        }
    }

    suspend fun clear(scopeId: String) {
        require(scopeId.isNotBlank())
        db.withTransaction {
            dao.clearConversationEpisodes(scopeId)
            dao.clearProfileFacts(scopeId)
            dao.clearActionResults(scopeId)
            dao.clearConversation(scopeId)
        }
    }

    private suspend fun pruneExpired(scopeId: String, days: Int, nowMillis: Long): Int =
        if (days == RETENTION_FOREVER) 0 else {
            val cutoff = nowMillis - days.toLong() * DAY_MILLIS
            dao.pruneEpisodesForConversationTurnsBefore(scopeId, cutoff)
            dao.pruneProfileFactsForConversationTurnsBefore(scopeId, cutoff)
            dao.pruneActionResultsBefore(scopeId, cutoff)
            dao.pruneConversationTurnsBefore(scopeId, cutoff)
        }

    private fun episodeId(scopeId: String, sourceTurnIds: List<String>): String = sha256(
        "memora_episode_v1\u0000$scopeId\u0000${sourceTurnIds.sorted().joinToString("\u0000")}")

    private fun toTurnEvidence(rows: List<AgentConversationMessageRow>): ConversationTurnEvidence? {
        val user = rows.firstOrNull { it.role == "user" } ?: return null
        val assistant = rows.firstOrNull { it.role == "model" } ?: return null
        return ConversationTurnEvidence(user.turnId, rows.maxOf { it.createdAtMillis }, user.message,
            assistant.message, null)
    }

    private fun fold(value: String) = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(SEARCH_MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
        .replace("ch", "c").replace("sh", "s")

    private fun searchTokenMatches(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        if (minOf(query.length, candidate.length) < 4) return false
        return query.zip(candidate).takeWhile { (left, right) -> left == right }.size >= 4
    }

    private fun isUuid(value: String) = runCatching { java.util.UUID.fromString(value) }.isSuccess

        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val RETENTION_FOREVER = 0
        const val DAY_MILLIS = 86_400_000L
        const val PAGE_SIZE = 160
        const val MAX_SEARCH_HITS = 120
        const val EPISODE_PAGE_SIZE = 100
        const val MAX_EPISODE_SEARCH_HITS = 120
        val ACTION_STATUSES = setOf("APPLIED", "REJECTED", "CONFLICT", "FAILED", "UNKNOWN", "NEEDS_CONFIRMATION")
        val ACTION_TARGET_KINDS = setOf("memory", "finance", "case", "task", "calendar", "table")
        val ALLOWED_RETENTION_DAYS = setOf(RETENTION_FOREVER, 30, 90, 365)
        val TOKEN = Regex("[\\p{L}\\p{N}]+")
        val SEARCH_MARKS = Regex("\\p{M}+")
        val STOP_WORDS = setOf("and", "the", "that", "what", "when", "were", "was", "with", "about", "from", "this", "have", "did", "we",
            "last", "week", "weeks", "ago", "past", "previous", "discuss", "discussed", "talk", "talked", "said",
            "и", "а", "в", "во", "на", "о", "об", "что", "как", "когда", "где", "это", "мы", "был", "были", "про", "насчёт",
            "прошлой", "прошлую", "прошлый", "неделе", "неделю", "недель", "говорили", "обсуждали", "сказали",
            "və", "bu", "o", "bir", "nə", "necə", "haqqında", "ilə", "mən", "biz", "keçən", "həftə", "danışdıq", "müzakirə")
    }
}

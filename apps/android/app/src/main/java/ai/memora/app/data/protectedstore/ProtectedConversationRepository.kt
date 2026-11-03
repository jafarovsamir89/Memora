package ai.memora.app.data.protectedstore

import androidx.room.withTransaction

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

    /** A whole user/assistant turn is the smallest deletion unit, so search can never show an orphan reply. */
    suspend fun deleteTurn(scopeId: String, turnId: String): Boolean {
        require(scopeId.isNotBlank() && turnId.isNotBlank())
        return db.withTransaction { dao.deleteConversationTurn(scopeId, turnId) > 0 }
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
            val matched = terms.count { term -> foldedTokens.any { token -> token == term ||
                (minOf(token.length, term.length) >= 4 && (token.startsWith(term) || term.startsWith(token))) } }
            if (terms.isNotEmpty() && matched == 0) return@mapNotNull null
            val first = tokens.indices.firstOrNull { index ->
                val token = foldedTokens[index]
                terms.any { term -> token == term || (minOf(token.length, term.length) >= 4 &&
                    (token.startsWith(term) || term.startsWith(token))) }
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

    suspend fun appendTurn(scopeId: String, turnId: String, userMessage: String, assistantMessage: String,
        createdAtMillis: Long = System.currentTimeMillis()) {
        require(scopeId.isNotBlank() && turnId.isNotBlank() && turnId.length <= 100)
        require(userMessage.isNotBlank() && userMessage.length <= 20_000)
        require(assistantMessage.isNotBlank() && assistantMessage.length <= 20_000)
        db.withTransaction {
            val existing = dao.conversationMessagesForTurn(scopeId, turnId)
            if (existing.isNotEmpty()) {
                check(existing.map { it.role to it.message } == listOf("user" to userMessage, "model" to assistantMessage))
                return@withTransaction
            }
            dao.putConversationMessages(listOf(
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "user", message = userMessage,
                    createdAtMillis = createdAtMillis),
                AgentConversationMessageRow(scopeId = scopeId, turnId = turnId, role = "model", message = assistantMessage,
                    createdAtMillis = createdAtMillis),
            ))
            val days = dao.conversationRetention(scopeId)?.retentionDays ?: RETENTION_FOREVER
            pruneExpired(scopeId, days, System.currentTimeMillis())
        }
    }

    suspend fun clear(scopeId: String) {
        require(scopeId.isNotBlank())
        dao.clearConversation(scopeId)
    }

    private suspend fun pruneExpired(scopeId: String, days: Int, nowMillis: Long): Int =
        if (days == RETENTION_FOREVER) 0 else {
            val cutoff = nowMillis - days.toLong() * DAY_MILLIS
            dao.pruneConversationTurnsBefore(scopeId, cutoff)
        }

    private fun toTurnEvidence(rows: List<AgentConversationMessageRow>): ConversationTurnEvidence? {
        val user = rows.firstOrNull { it.role == "user" } ?: return null
        val assistant = rows.firstOrNull { it.role == "model" } ?: return null
        return ConversationTurnEvidence(user.turnId, rows.maxOf { it.createdAtMillis }, user.message,
            assistant.message, null)
    }

    private fun fold(value: String) = value.lowercase(java.util.Locale.ROOT)
        .replace('ı', 'i').replace('İ', 'i').replace('ё', 'е')

    private companion object {
        const val RETENTION_FOREVER = 0
        const val DAY_MILLIS = 86_400_000L
        const val PAGE_SIZE = 160
        const val MAX_SEARCH_HITS = 120
        val ALLOWED_RETENTION_DAYS = setOf(RETENTION_FOREVER, 30, 90, 365)
        val TOKEN = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf("and", "the", "that", "what", "when", "were", "was", "with", "about", "from", "this", "have", "did", "we",
            "last", "week", "weeks", "ago", "past", "previous", "discuss", "discussed", "talk", "talked", "said",
            "и", "а", "в", "во", "на", "о", "об", "что", "как", "когда", "где", "это", "мы", "был", "были", "про", "насчёт",
            "прошлой", "прошлую", "прошлый", "неделе", "неделю", "недель", "говорили", "обсуждали", "сказали",
            "və", "bu", "o", "bir", "nə", "necə", "haqqında", "ilə", "mən", "biz", "keçən", "həftə", "danışdıq", "müzakirə")
    }
}

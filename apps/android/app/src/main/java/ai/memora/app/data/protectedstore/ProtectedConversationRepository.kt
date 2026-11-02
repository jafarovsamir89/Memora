package ai.memora.app.data.protectedstore

import androidx.room.withTransaction

internal data class ConversationEvidence(
    val turnId: String,
    val role: String,
    val createdAtMillis: Long,
    val score: Int,
    val snippet: String,
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

    /** Search the bounded encrypted archive locally and return only small, relevance-ranked evidence snippets. */
    suspend fun search(scopeId: String, query: String, daysBack: Int? = null, limit: Int = 8): List<ConversationEvidence> {
        require(scopeId.isNotBlank() && query.length <= 300 && limit in 1..12 && (daysBack == null || daysBack in 1..365))
        val queryFolded = fold(query)
        val terms = TOKEN.findAll(queryFolded).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.distinct().take(12).toList()
        if (terms.isEmpty() && daysBack == null) return emptyList()
        val cutoff = daysBack?.let { System.currentTimeMillis() - it.toLong() * 86_400_000L }
        val hits = recent(scopeId, 240).asSequence().filter { cutoff == null || it.createdAtMillis >= cutoff }.mapNotNull { row ->
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
        }.toList()
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
            dao.trimConversation(scopeId, 240)
        }
    }

    suspend fun clear(scopeId: String) {
        require(scopeId.isNotBlank())
        dao.clearConversation(scopeId)
    }

    private fun fold(value: String) = value.lowercase(java.util.Locale.ROOT)
        .replace('ı', 'i').replace('İ', 'i').replace('ё', 'е')

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf("and", "the", "that", "what", "when", "were", "was", "with", "about", "from", "this", "have", "did", "we",
            "last", "week", "weeks", "ago", "past", "previous", "discuss", "discussed", "talk", "talked", "said",
            "и", "а", "в", "во", "на", "о", "об", "что", "как", "когда", "где", "это", "мы", "был", "были", "про", "насчёт",
            "прошлой", "прошлую", "прошлый", "неделе", "неделю", "недель", "говорили", "обсуждали", "сказали",
            "və", "bu", "o", "bir", "nə", "necə", "haqqında", "ilə", "mən", "biz", "keçən", "həftə", "danışdıq", "müzakirə")
    }
}

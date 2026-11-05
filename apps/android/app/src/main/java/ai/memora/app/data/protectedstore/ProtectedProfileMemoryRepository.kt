package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class ProfileFactRevisionEvidence(
    val revision: Long,
    val category: String,
    val fact: String,
    val provenance: String,
    val sourceTurnId: String?,
    val recordedAtMillis: Long,
)

internal data class ProfileFactEvidence(
    val factId: String,
    val scopeId: String,
    val category: String,
    val fact: String,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val sourceTurnIds: List<String>,
    val revisions: List<ProfileFactRevisionEvidence>,
    val score: Int = 0,
)

/** Owner-scoped, explicit personal memory. Model-inferred facts are intentionally unsupported. */
internal class ProtectedProfileMemoryRepository(private val db: AgentDatabase) {
    private val dao = db.protectedStoreDao()

    suspend fun list(scopeId: String, query: String = "", limit: Int = 30): List<ProfileFactEvidence> {
        require(scopeId.isNotBlank() && query.length <= 200 && limit in 1..100)
        val rows = dao.profileFacts(scopeId, MAX_PROFILE_FACTS)
        val terms = TOKEN.findAll(foldSearch(query)).map { it.value }.filter { it.length > 1 && it !in SEARCH_STOP_WORDS }
            .distinct().take(12).toList()
        val evidence = rows.mapNotNull { row ->
            val value = loadEvidence(scopeId, row.factId) ?: return@mapNotNull null
            if (terms.isEmpty()) value
            else {
                val haystack = TOKEN.findAll(foldSearch("${row.category} ${row.fact}")).map { it.value }.toList()
                val matched = terms.count { term -> haystack.any { token -> searchTokenMatches(term, token) } }
                if (matched == 0) null else value.copy(score = matched * 10 + if (terms.all { term -> haystack.any { searchTokenMatches(term, it) } }) 5 else 0)
            }
        }
        return if (terms.isEmpty()) evidence.take(limit)
        else evidence.sortedWith(compareByDescending<ProfileFactEvidence> { it.score }
            .thenByDescending { it.updatedAtMillis }.thenBy { it.factId }).take(limit)
    }

    suspend fun get(scopeId: String, factId: String): ProfileFactEvidence? {
        require(scopeId.isNotBlank() && factId.isNotBlank())
        return loadEvidence(scopeId, factId)
    }

    /** Saves an owner-authored profile fact without inventing a conversation source. */
    suspend fun createManually(
        scopeId: String,
        category: String,
        fact: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProfileFactEvidence = db.withTransaction {
        validate(scopeId, category, fact, nowMillis)
        val normalized = fact.trim()
        val digest = digest(scopeId, category, normalizeFact(normalized))
        check(dao.profileFactByDigest(scopeId, digest) == null) { "profile_fact_duplicate" }
        check(dao.profileFactCount(scopeId) < MAX_PROFILE_FACTS) { "profile_fact_limit_reached" }
        val id = UUID.randomUUID().toString()
        val row = ProfileFactRow(id, scopeId, category, normalized, digest, 1, nowMillis, nowMillis)
        dao.insertProfileFact(row)
        dao.insertProfileFactRevision(ProfileFactRevisionRow(scopeId, id, 1, category, normalized,
            "user_entered", null, nowMillis))
        requireReadback(scopeId, id).also { saved ->
            check(saved.fact == normalized && saved.sourceTurnIds.isEmpty() &&
                saved.revisions.singleOrNull()?.let { it.provenance == "user_entered" && it.sourceTurnId == null } == true) {
                "profile_fact_readback_mismatch"
            }
        }
    }

    /** Saves only a fact explicitly present in the current user message; source text is not model-authored. */
    suspend fun saveUserStated(
        scopeId: String,
        category: String,
        fact: String,
        sourceTurnId: String,
        sourceText: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProfileFactEvidence = db.withTransaction {
        validate(scopeId, category, fact, nowMillis)
        ensureCurrentUserSource(scopeId, sourceTurnId, sourceText, nowMillis)
        val normalizedFact = normalizeFact(fact)
        val digest = digest(scopeId, category, normalizedFact)
        val existing = dao.profileFactByDigest(scopeId, digest)
        if (existing != null) {
            dao.insertProfileFactSource(ProfileFactSourceRow(scopeId, existing.factId, sourceTurnId, "user_stated"))
            return@withTransaction requireReadback(scopeId, existing.factId)
        }
        check(dao.profileFactCount(scopeId) < MAX_PROFILE_FACTS) { "profile_fact_limit_reached" }
        val id = UUID.randomUUID().toString()
        val normalized = fact.trim()
        val row = ProfileFactRow(id, scopeId, category, normalized, digest, 1, nowMillis, nowMillis)
        dao.insertProfileFact(row)
        dao.insertProfileFactSource(ProfileFactSourceRow(scopeId, id, sourceTurnId, "user_stated"))
        dao.insertProfileFactRevision(ProfileFactRevisionRow(scopeId, id, 1, category, normalized,
            "user_stated", sourceTurnId, nowMillis))
        requireReadback(scopeId, id).also { saved ->
            check(saved.fact == normalized && saved.sourceTurnIds == listOf(sourceTurnId) &&
                saved.revisions.singleOrNull()?.sourceTurnId == sourceTurnId) { "profile_fact_readback_mismatch" }
        }
    }

    /** Version-checked update; a model write must be sourced to the exact current user turn. */
    suspend fun correctFromUser(
        scopeId: String,
        factId: String,
        expectedRevision: Long,
        category: String,
        fact: String,
        sourceTurnId: String,
        sourceText: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProfileFactEvidence = update(scopeId, factId, expectedRevision, category, fact,
        "user_corrected", sourceTurnId, sourceText, nowMillis)

    /** UI edits are explicit owner actions and keep a revision even without a conversation source. */
    suspend fun editManually(
        scopeId: String,
        factId: String,
        expectedRevision: Long,
        category: String,
        fact: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProfileFactEvidence = update(scopeId, factId, expectedRevision, category, fact,
        "user_edited", null, null, nowMillis)

    suspend fun delete(scopeId: String, factId: String, expectedRevision: Long): Boolean = db.withTransaction {
        require(scopeId.isNotBlank() && factId.isNotBlank() && expectedRevision > 0)
        dao.deleteProfileFact(scopeId, factId, expectedRevision) == 1
    }

    private suspend fun update(
        scopeId: String,
        factId: String,
        expectedRevision: Long,
        category: String,
        fact: String,
        provenance: String,
        sourceTurnId: String?,
        sourceText: String?,
        nowMillis: Long,
    ): ProfileFactEvidence = db.withTransaction {
        validate(scopeId, category, fact, nowMillis)
        require(factId.isNotBlank() && expectedRevision > 0)
        if (sourceTurnId != null) ensureCurrentUserSource(scopeId, sourceTurnId, requireNotNull(sourceText), nowMillis)
        val current = dao.profileFact(scopeId, factId) ?: error("profile_fact_not_found")
        check(current.revision == expectedRevision) { "profile_fact_revision_conflict" }
        val normalized = fact.trim()
        val digest = digest(scopeId, category, normalizeFact(normalized))
        val duplicate = dao.profileFactByDigest(scopeId, digest)
        check(duplicate == null || duplicate.factId == factId) { "profile_fact_duplicate" }
        if (current.category == category && current.fact == normalized) {
            if (sourceTurnId != null) dao.insertProfileFactSource(ProfileFactSourceRow(scopeId, factId, sourceTurnId, provenance))
            return@withTransaction requireReadback(scopeId, factId)
        }
        // Any episode built from the superseded statement can now contradict the owner's correction.
        // Invalidate only derived summaries that cite one of this fact's prior source turns; keep the
        // conversation and the complete profile-fact revision trail intact.
        val supersededSourceTurnIds = dao.profileFactSources(scopeId, factId).map { it.turnId }.distinct()
        val nextRevision = Math.addExact(current.revision, 1)
        val updatedAt = maxOf(nowMillis, current.updatedAtMillis)
        check(dao.updateProfileFact(scopeId, factId, expectedRevision, category, normalized, digest,
            nextRevision, updatedAt) == 1) { "profile_fact_revision_conflict" }
        if (sourceTurnId != null) dao.insertProfileFactSource(ProfileFactSourceRow(scopeId, factId, sourceTurnId, provenance))
        dao.insertProfileFactRevision(ProfileFactRevisionRow(scopeId, factId, nextRevision, category,
            normalized, provenance, sourceTurnId, updatedAt))
        supersededSourceTurnIds.forEach { sourceTurnId ->
            dao.deleteEpisodesForConversationTurn(scopeId, sourceTurnId)
        }
        requireReadback(scopeId, factId).also { saved ->
            check(saved.revision == nextRevision && saved.category == category && saved.fact == normalized &&
                saved.revisions.lastOrNull()?.sourceTurnId == sourceTurnId) { "profile_fact_readback_mismatch" }
        }
    }

    private suspend fun ensureCurrentUserSource(scopeId: String, turnId: String, sourceText: String, nowMillis: Long) {
        require(turnId.isNotBlank() && turnId.length <= 100 && sourceText.isNotBlank() && sourceText.length <= 20_000)
        val existing = dao.conversationMessagesForTurn(scopeId, turnId)
        val user = existing.firstOrNull { it.role == "user" }
        if (user == null) {
            check(existing.isEmpty()) { "profile_fact_source_turn_conflict" }
            dao.putConversationMessages(listOf(AgentConversationMessageRow(scopeId = scopeId,
                turnId = turnId, role = "user", message = sourceText, createdAtMillis = nowMillis)))
        } else {
            check(user.scopeId == scopeId && user.turnId == turnId && user.message == sourceText) {
                "profile_fact_source_turn_conflict"
            }
        }
    }

    private suspend fun requireReadback(scopeId: String, factId: String): ProfileFactEvidence =
        requireNotNull(loadEvidence(scopeId, factId)) { "profile_fact_readback_missing" }

    private suspend fun loadEvidence(scopeId: String, factId: String): ProfileFactEvidence? {
        val row = dao.profileFact(scopeId, factId) ?: return null
        val sources = dao.profileFactSources(scopeId, factId)
        val revisions = dao.profileFactRevisions(scopeId, factId).map { revision ->
            ProfileFactRevisionEvidence(revision.revision, revision.category, revision.fact,
                revision.provenance, revision.sourceTurnId, revision.recordedAtMillis)
        }
        check(revisions.lastOrNull()?.revision == row.revision && revisions.lastOrNull()?.fact == row.fact &&
            sources.all { it.scopeId == scopeId && it.factId == factId }) { "profile_fact_integrity_error" }
        return ProfileFactEvidence(row.factId, row.scopeId, row.category, row.fact, row.revision,
            row.createdAtMillis, row.updatedAtMillis, sources.map { it.turnId }, revisions)
    }

    private fun validate(scopeId: String, category: String, fact: String, nowMillis: Long) {
        require(scopeId.isNotBlank() && category in CATEGORIES)
        require(fact.isNotBlank() && fact.trim().length <= 600 && normalizeFact(fact).isNotBlank())
        require(nowMillis >= 0)
    }

    private fun digest(scopeId: String, category: String, normalizedFact: String): String = sha256(
        "profile_fact_v1\u0000$scopeId\u0000$category\u0000$normalizedFact")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun normalizeFact(value: String): String = TOKEN.findAll(fold(value)).joinToString(" ") { it.value }
    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun foldSearch(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(SEARCH_MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
        .replace("ch", "c").replace("sh", "s")
    private fun searchTokenMatches(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        if (minOf(query.length, candidate.length) < 4) return false
        return query.zip(candidate).takeWhile { (left, right) -> left == right }.size >= 4
    }
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val SEARCH_MARKS = Regex("\\p{M}+")
    private val SEARCH_STOP_WORDS = setOf("what", "whats", "find", "show", "about", "with", "the", "for", "and", "from",
        "what", "is", "was", "are", "to", "of", "in", "on", "me", "my", "who", "when", "where", "why", "how",
        "ne", "haqqinda", "haqda", "mene", "menim", "ve", "bu", "bir", "що", "как", "мне", "об", "про", "для", "и", "по", "у", "к", "в", "на", "с", "из", "от", "за")

    companion object {
        val CATEGORIES = setOf("identity", "preference", "goal", "context", "other")
        const val MAX_PROFILE_FACTS = 200
        private val WHITESPACE = Regex("\\s+")
    }
}

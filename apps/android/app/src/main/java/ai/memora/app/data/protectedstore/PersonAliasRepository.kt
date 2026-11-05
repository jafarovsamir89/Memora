package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal sealed interface FinanceAliasResolution {
    data object NotAlias : FinanceAliasResolution
    data class Resolved(val financePerson: PersonRow, val domainPerson: DomainObjectRow) : FinanceAliasResolution
    data object OwnerLinkRequired : FinanceAliasResolution
    data object RetiredAlias : FinanceAliasResolution
    data object Ambiguous : FinanceAliasResolution
}

/** Exact, owner-entered person aliases. This repository never merges identities by similarity. */
internal class PersonAliasRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()

    suspend fun list(scope: String, domainPersonId: String, includeRetired: Boolean = true): List<PersonAliasRow> {
        require(scope.isNotBlank() && domainPersonId.isNotBlank())
        return dao.personAliases(scope, domainPersonId).filter { includeRetired || it.deletedAtMillis == null }
    }

    suspend fun add(
        scope: String,
        domainPersonId: String,
        aliasText: String,
        actorId: String = "owner",
        sourceTurnId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): PersonAliasRow = db.withTransaction {
        require(scope.isNotBlank() && domainPersonId.isNotBlank() && actorId.isNotBlank())
        require(actorId.length <= 100 && (sourceTurnId == null || sourceTurnId.length <= 100))
        val display = displayAlias(aliasText)
        val normalized = normalizeAlias(display)
        require(normalized.isNotBlank() && normalized.length <= 120) { "invalid_person_alias" }
        val person = dao.domainObject(scope, domainPersonId)
            ?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
            ?: error("person_alias_target_unavailable")
        check(normalizeAlias(person.title) != normalized) { "person_alias_matches_canonical_name" }
        val activePeople = dao.activeObjects(scope).filter { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
        check(activePeople.none { it.objectId != domainPersonId && normalizeAlias(it.title) == normalized }) {
            "person_alias_canonical_collision"
        }
        val existingAliases = dao.personAliases(scope, domainPersonId)
        existingAliases.firstOrNull { it.deletedAtMillis == null && it.normalizedAlias == normalized }?.let { return@withTransaction it }
        val priorAliases = dao.personAliasesForExactAlias(scope, normalized)
        check(priorAliases.none { it.domainPersonId != domainPersonId }) { "person_alias_collision" }
        check(priorAliases.none { it.deletedAtMillis != null }) { "person_alias_restore_required" }
        check(dao.activePersonAliases(scope).none { it.normalizedAlias == normalized }) { "person_alias_collision" }
        val row = PersonAliasRow(
            aliasId = UUID.randomUUID().toString(), scopeId = scope, domainPersonId = domainPersonId,
            aliasText = display, normalizedAlias = normalized, activeAliasKey = activeKey(scope, normalized),
            version = 1, actorId = actorId, sourceTurnId = sourceTurnId,
            createdAtMillis = nowMillis, updatedAtMillis = nowMillis, deletedAtMillis = null,
        )
        dao.insertPersonAlias(row)
        dao.insertPersonAliasRevision(row.revision("added", nowMillis))
        check(dao.personAlias(scope, row.aliasId) == row && dao.personAliases(scope, domainPersonId).any { it.aliasId == row.aliasId }) {
            "person_alias_add_readback_mismatch"
        }
        row
    }

    suspend fun retire(
        scope: String,
        aliasId: String,
        expectedVersion: Long,
        actorId: String = "owner",
        nowMillis: Long = System.currentTimeMillis(),
    ): PersonAliasRow = db.withTransaction {
        require(actorId.isNotBlank() && actorId.length <= 100)
        val current = dao.personAlias(scope, aliasId)?.takeIf { it.deletedAtMillis == null }
            ?: error("person_alias_not_found")
        check(current.version == expectedVersion) { "person_alias_version_conflict" }
        val next = current.copy(version = current.version + 1, actorId = actorId,
            updatedAtMillis = nowMillis, deletedAtMillis = nowMillis, activeAliasKey = null)
        check(dao.retirePersonAlias(scope, aliasId, expectedVersion, next.version, nowMillis) == 1) {
            "person_alias_version_conflict"
        }
        dao.insertPersonAliasRevision(next.revision("retired", nowMillis))
        check(dao.personAlias(scope, aliasId) == next && dao.activePersonAliases(scope).none { it.aliasId == aliasId }) {
            "person_alias_retire_readback_mismatch"
        }
        next
    }

    suspend fun restore(
        scope: String,
        aliasId: String,
        expectedVersion: Long,
        actorId: String = "owner",
        nowMillis: Long = System.currentTimeMillis(),
    ): PersonAliasRow = db.withTransaction {
        require(actorId.isNotBlank() && actorId.length <= 100)
        val current = dao.personAlias(scope, aliasId)?.takeIf { it.deletedAtMillis != null }
            ?: error("person_alias_not_retired")
        check(current.version == expectedVersion) { "person_alias_version_conflict" }
        val person = dao.domainObject(scope, current.domainPersonId)
            ?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
            ?: error("person_alias_target_unavailable")
        check(normalizeAlias(person.title) != current.normalizedAlias) { "person_alias_matches_canonical_name" }
        val activePeople = dao.activeObjects(scope).filter { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
        check(activePeople.none { it.objectId != current.domainPersonId && normalizeAlias(it.title) == current.normalizedAlias }) {
            "person_alias_canonical_collision"
        }
        check(dao.activePersonAliases(scope).none { it.normalizedAlias == current.normalizedAlias }) { "person_alias_collision" }
        val next = current.copy(version = current.version + 1, actorId = actorId,
            updatedAtMillis = nowMillis, deletedAtMillis = null, activeAliasKey = activeKey(scope, current.normalizedAlias))
        check(dao.restorePersonAlias(scope, aliasId, expectedVersion, next.version, nowMillis, next.activeAliasKey!!) == 1) {
            "person_alias_version_conflict"
        }
        dao.insertPersonAliasRevision(next.revision("restored", nowMillis))
        check(dao.personAlias(scope, aliasId) == next && dao.activePersonAliases(scope).any { it == next }) {
            "person_alias_restore_readback_mismatch"
        }
        next
    }

    /** An exact alias never creates a ledger identity; it needs the owner's existing identity link. */
    suspend fun resolveFinanceAlias(scope: String, raw: String): FinanceAliasResolution {
        require(scope.isNotBlank())
        val normalized = normalizeAlias(raw)
        if (normalized.isBlank()) return FinanceAliasResolution.NotAlias
        val matches = dao.personAliasesForExactAlias(scope, normalized)
        if (matches.isEmpty()) return FinanceAliasResolution.NotAlias
        val active = matches.filter { it.deletedAtMillis == null }
        if (active.isEmpty()) return FinanceAliasResolution.RetiredAlias
        if (active.size != 1) return FinanceAliasResolution.Ambiguous
        val alias = active.single()
        if (dao.activeObjects(scope).any { it.kind == DomainKind.PERSON.name && it.deletedAt == null && normalizeAlias(it.title) == normalized })
            return FinanceAliasResolution.Ambiguous
        val domainPerson = dao.domainObject(scope, alias.domainPersonId)
            ?.takeIf { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
            ?: return FinanceAliasResolution.OwnerLinkRequired
        val link = dao.activeIdentityLinkForDomainPerson(scope, domainPerson.objectId)
            ?.takeIf { it.verifiedBy == "owner" }
            ?: return FinanceAliasResolution.OwnerLinkRequired
        val financePerson = dao.person(scope, link.financePersonId) ?: return FinanceAliasResolution.OwnerLinkRequired
        if (dao.activeIdentityLinkForFinancePerson(scope, financePerson.personId)?.linkId != link.linkId)
            return FinanceAliasResolution.Ambiguous
        return FinanceAliasResolution.Resolved(financePerson, domainPerson)
    }

    suspend fun exactActiveMatches(scope: String, raw: String): List<PersonAliasRow> {
        val normalized = normalizeAlias(raw)
        if (normalized.isBlank()) return emptyList()
        if (dao.activeObjects(scope).any { it.kind == DomainKind.PERSON.name && it.deletedAt == null && normalizeAlias(it.title) == normalized })
            return emptyList()
        return dao.personAliasesForExactAlias(scope, normalized).filter { it.deletedAtMillis == null }
    }

    /** Finds active owner-entered aliases that occur as a whole contiguous phrase in a natural query. */
    suspend fun activeAliasesMentionedInQuery(scope: String, raw: String): List<PersonAliasRow> {
        require(scope.isNotBlank() && raw.length <= 200)
        val queryTokens = phraseTokens(raw)
        if (queryTokens.isEmpty()) return emptyList()
        val canonicalNames = dao.activeObjects(scope)
            .filter { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
            .map { normalizeAlias(it.title) }
            .toSet()
        return dao.activePersonAliases(scope).filter { alias ->
            val aliasTokens = phraseTokens(alias.normalizedAlias)
            alias.normalizedAlias !in canonicalNames && aliasTokens.isNotEmpty() && queryTokens.containsPhrase(aliasTokens)
        }
    }

    private fun PersonAliasRow.revision(action: String, at: Long) = PersonAliasRevisionRow(
        aliasId = aliasId, version = version, scopeId = scopeId, domainPersonId = domainPersonId,
        action = action, aliasText = aliasText, normalizedAlias = normalizedAlias, actorId = actorId,
        sourceTurnId = sourceTurnId, recordedAtMillis = at,
    )

    private fun activeKey(scope: String, normalized: String) = "${scope.length}:$scope:$normalized"

    companion object {
        private val phraseSeparator = Regex("[^\\p{L}\\p{N}]+")

        private fun phraseTokens(value: String): List<String> = normalizeAlias(value)
            .split(phraseSeparator).filter(String::isNotEmpty)

        private fun List<String>.containsPhrase(phrase: List<String>): Boolean =
            phrase.size <= size && windowed(phrase.size).any { it == phrase }

        internal fun normalizeAlias(value: String): String = Normalizer.normalize(
            Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT),
            Normalizer.Form.NFC,
        )

        private fun displayAlias(value: String): String {
            val normalizedSpacing = Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
            require(normalizedSpacing.isNotBlank() && normalizedSpacing.length <= 120) { "invalid_person_alias" }
            return normalizedSpacing
        }
    }
}

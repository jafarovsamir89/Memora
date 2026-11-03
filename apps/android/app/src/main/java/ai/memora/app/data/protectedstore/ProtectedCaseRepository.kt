package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import ai.memora.app.domain.VoiceMoneyRules
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** A suggested exact-name candidate is display-only; callers must ask the owner before linking it. */
internal data class FinancePersonIdentityChoices(
    val financePerson: PersonRow,
    val candidates: List<DomainObjectRow>,
    val confirmedLink: PersonIdentityLinkRow?,
)

internal data class CaseEvidenceView(
    val evidence: CaseEvidenceRow,
    val title: String,
    val detail: String?,
    val sourceAvailable: Boolean,
    val eventKind: String? = null,
)

internal data class CaseTimelineEntry(
    val id: String,
    val at: String,
    val title: String,
    val detail: String?,
    val sourceKind: String,
    val sourceId: String,
    val sourceRevisionId: String?,
    val eventKind: String? = null,
)

internal data class CaseSnapshot(
    val record: CaseRecordRow,
    val participants: List<DomainObjectRow>,
    val evidence: List<CaseEvidenceView>,
    val timeline: List<CaseTimelineEntry>,
)

/**
 * Identity links are explicit and reversible. Cases keep canonical source IDs; no finance, task,
 * calendar or memory row is copied into the case tables.
 */
internal class ProtectedCaseRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()
    private val domainRepository by lazy { ProtectedDomainRepository(db) }

    suspend fun financePersonIdentityChoices(scope: String, financePersonId: String): FinancePersonIdentityChoices? {
        require(scope.isNotBlank() && financePersonId.isNotBlank())
        val financePerson = dao.person(scope, financePersonId) ?: return null
        val link = dao.activeIdentityLinkForFinancePerson(scope, financePersonId)
        val linkedPerson = link?.let { dao.domainObject(scope, it.domainPersonId) }
        val candidates = if (link != null) listOfNotNull(linkedPerson?.takeIf { it.deletedAt == null && it.kind == DomainKind.PERSON.name })
            else dao.activeDomainObjects(scope, DomainKind.PERSON.name, 500)
                .filter { normalizedName(it.title) == normalizedName(financePerson.displayName) }
        return FinancePersonIdentityChoices(financePerson, candidates, link)
    }

    /** This API is for an owner-confirmation UI only; no model tool calls it. */
    suspend fun confirmFinancePersonIdentity(
        scope: String,
        financePersonId: String,
        domainPersonId: String,
        linkId: String = UUID.randomUUID().toString(),
        confirmedBy: String,
        now: String = Instant.now().toString(),
    ): PersonIdentityLinkRow = db.withTransaction {
        require(scope.isNotBlank() && financePersonId.isNotBlank() && domainPersonId.isNotBlank())
        require(confirmedBy == "owner") { "identity_link_requires_owner" }
        require(linkId.isNotBlank() && linkId.length <= 100)
        Instant.parse(now)
        check(dao.person(scope, financePersonId) != null) { "finance_person_not_found" }
        val domainPerson = dao.domainObject(scope, domainPersonId)
        check(domainPerson?.kind == DomainKind.PERSON.name && domainPerson.deletedAt == null) { "domain_person_not_found" }

        val financeLink = dao.activeIdentityLinkForFinancePerson(scope, financePersonId)
        val domainLink = dao.activeIdentityLinkForDomainPerson(scope, domainPersonId)
        val existingLink = financeLink ?: domainLink
        if (existingLink != null) {
            check(financeLink != null && domainLink != null && financeLink.linkId == domainLink.linkId &&
                financeLink.financePersonId == financePersonId && financeLink.domainPersonId == domainPersonId) { "identity_link_conflict" }
            return@withTransaction existingLink
        }
        val row = PersonIdentityLinkRow(linkId, scope, financePersonId, domainPersonId, "owner", now, null)
        dao.insertPersonIdentityLink(row)
        check(dao.activeIdentityLinkForFinancePerson(scope, financePersonId) == row &&
            dao.activeIdentityLinkForDomainPerson(scope, domainPersonId) == row) { "identity_link_readback_mismatch" }
        row
    }

    suspend fun unlinkFinancePersonIdentity(scope: String, linkId: String, confirmedBy: String, now: String = Instant.now().toString()) = db.withTransaction {
        require(confirmedBy == "owner") { "identity_unlink_requires_owner" }
        Instant.parse(now)
        val current = dao.activePersonIdentityLinks(scope).firstOrNull { it.linkId == linkId } ?: error("identity_link_not_found")
        check(dao.unlinkPersonIdentity(scope, linkId, now) == 1) { "identity_link_conflict" }
        val readback = dao.activePersonIdentityLinks(scope).none { it.linkId == linkId }
        check(readback && dao.activeIdentityLinkForFinancePerson(scope, current.financePersonId) == null &&
            dao.activeIdentityLinkForDomainPerson(scope, current.domainPersonId) == null) { "identity_unlink_readback_mismatch" }
    }

    suspend fun createCase(
        scope: String,
        caseId: String,
        title: String,
        summary: String?,
        status: String = "open",
        nextAction: String? = null,
        sourceTurnId: String,
        now: String = Instant.now().toString(),
    ): CaseRecordRow = db.withTransaction {
        validateCase(scope, caseId, title, summary, status, nextAction, sourceTurnId, now)
        val normalizedTitle = title.trim()
        val normalizedSummary = summary.normalizedOptional()
        val normalizedNextAction = nextAction.normalizedOptional()
        val existing = dao.caseRecord(scope, caseId)
        if (existing != null) {
            check(existing.title == normalizedTitle && existing.summary == normalizedSummary && existing.status == status &&
                existing.nextAction == normalizedNextAction && existing.sourceTurnId == sourceTurnId) { "case_idempotency_conflict" }
            return@withTransaction existing
        }
        val row = CaseRecordRow(caseId, scope, normalizedTitle, normalizedSummary, status, normalizedNextAction,
            1, sourceTurnId, now, now, null)
        dao.insertCase(row)
        val revision = caseRevision(row, sourceTurnId, now)
        dao.insertCaseRevision(revision)
        check(dao.caseRecord(scope, caseId) == row && dao.caseRevisions(caseId).singleOrNull() == revision) { "case_create_readback_mismatch" }
        row
    }

    suspend fun updateCase(
        scope: String,
        caseId: String,
        expectedVersion: Long,
        title: String,
        summary: String?,
        status: String,
        nextAction: String?,
        sourceTurnId: String,
        now: String = Instant.now().toString(),
    ): CaseRecordRow = db.withTransaction {
        val current = dao.caseRecord(scope, caseId) ?: error("case_not_found")
        check(current.deletedAt == null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "case_version_conflict" }
        validateCase(scope, caseId, title, summary, status, nextAction, sourceTurnId, now)
        val nextVersion = expectedVersion + 1
        val normalizedTitle = title.trim()
        val normalizedSummary = summary.normalizedOptional()
        val normalizedNextAction = nextAction.normalizedOptional()
        check(dao.updateCase(scope, caseId, expectedVersion, nextVersion, normalizedTitle, normalizedSummary,
            status, normalizedNextAction, now) == 1) { "case_version_conflict" }
        val updated = requireNotNull(dao.caseRecord(scope, caseId))
        val revision = caseRevision(updated, sourceTurnId, now)
        dao.insertCaseRevision(revision)
        check(dao.caseRevisions(caseId).lastOrNull() == revision && updated.version == nextVersion) { "case_update_readback_mismatch" }
        updated
    }

    suspend fun addParticipant(
        scope: String,
        caseId: String,
        domainPersonId: String,
        role: String = "participant",
        participantId: String = UUID.randomUUID().toString(),
        now: String = Instant.now().toString(),
    ): CaseParticipantRow = db.withTransaction {
        require(scope.isNotBlank() && caseId.isNotBlank() && domainPersonId.isNotBlank())
        val cleanRole = role.trim()
        require(cleanRole.isNotBlank() && cleanRole.length <= 80 && cleanRole.none(Char::isISOControl))
        Instant.parse(now)
        val case = dao.caseRecord(scope, caseId)
        check(case?.deletedAt == null) { "case_not_found" }
        val person = dao.domainObject(scope, domainPersonId)
        check(person?.kind == DomainKind.PERSON.name && person.deletedAt == null) { "domain_person_not_found" }
        val old = dao.caseParticipant(scope, caseId, domainPersonId)
        if (old != null) {
            if (old.deletedAt == null) {
                check(old.role == cleanRole) { "participant_role_conflict" }
                return@withTransaction old
            }
            check(dao.restoreCaseParticipant(scope, old.participantId, cleanRole, now) == 1) { "participant_restore_conflict" }
            return@withTransaction requireNotNull(dao.caseParticipant(scope, caseId, domainPersonId)).copy(deletedAt = null,
                role = cleanRole, addedAt = now).also { restored -> check(restored == dao.caseParticipants(scope, caseId).first { it.participantId == old.participantId }) }
        }
        val row = CaseParticipantRow(participantId, scope, caseId, domainPersonId, cleanRole, now, null)
        dao.insertCaseParticipant(row)
        check(dao.caseParticipants(scope, caseId).any { it == row }) { "participant_readback_mismatch" }
        row
    }

    suspend fun removeParticipant(scope: String, caseId: String, domainPersonId: String, confirmedBy: String, now: String = Instant.now().toString()) = db.withTransaction {
        require(confirmedBy == "owner") { "participant_removal_requires_owner" }
        Instant.parse(now)
        val row = dao.caseParticipant(scope, caseId, domainPersonId)?.takeIf { it.deletedAt == null } ?: error("participant_not_found")
        check(dao.unlinkCaseParticipant(scope, row.participantId, now) == 1) { "participant_remove_conflict" }
        check(dao.caseParticipants(scope, caseId).none { it.participantId == row.participantId }) { "participant_remove_readback_mismatch" }
    }

    suspend fun addEvidence(
        scope: String,
        caseId: String,
        sourceKind: String,
        sourceId: String,
        relation: String = "related_to",
        sourceRevisionId: String? = null,
        evidenceId: String = UUID.randomUUID().toString(),
        now: String = Instant.now().toString(),
    ): CaseEvidenceRow = db.withTransaction {
        require(scope.isNotBlank() && caseId.isNotBlank() && sourceId.isNotBlank() && sourceId.length <= 120)
        require(relation.isNotBlank() && relation.length <= 80 && relation.none(Char::isISOControl))
        Instant.parse(now)
        val case = dao.caseRecord(scope, caseId)
        check(case?.deletedAt == null) { "case_not_found" }
        val resolved = resolveSource(scope, sourceKind, sourceId, sourceRevisionId, now)
            ?: error("case_source_not_found")
        val old = dao.caseEvidence(scope, caseId, sourceKind, sourceId)
        if (old != null) {
            if (old.deletedAt == null) {
                check(old.relation == relation && old.sourceRevisionId == sourceRevisionId) { "case_source_conflict" }
                return@withTransaction old
            }
            check(dao.restoreCaseEvidence(scope, old.evidenceId, relation, sourceRevisionId, resolved.first, now) == 1) {
                "case_source_restore_conflict"
            }
            return@withTransaction requireNotNull(dao.caseEvidence(scope, caseId, sourceKind, sourceId)).copy(
                deletedAt = null, relation = relation, sourceRevisionId = sourceRevisionId, occurredAt = resolved.first, addedAt = now,
            ).also { restored -> check(restored in dao.caseEvidence(scope, caseId)) }
        }
        val row = CaseEvidenceRow(evidenceId, scope, caseId, sourceKind, sourceId, relation, sourceRevisionId,
            resolved.first, now, null)
        dao.insertCaseEvidence(row)
        check(dao.caseEvidence(scope, caseId).any { it == row }) { "case_source_readback_mismatch" }
        row
    }

    suspend fun removeEvidence(scope: String, caseId: String, sourceKind: String, sourceId: String, confirmedBy: String, now: String = Instant.now().toString()) = db.withTransaction {
        require(confirmedBy == "owner") { "evidence_removal_requires_owner" }
        Instant.parse(now)
        val row = dao.caseEvidence(scope, caseId, sourceKind, sourceId)?.takeIf { it.deletedAt == null } ?: error("case_source_not_found")
        check(dao.unlinkCaseEvidence(scope, row.evidenceId, now) == 1) { "case_source_remove_conflict" }
        check(dao.caseEvidence(scope, caseId).none { it.evidenceId == row.evidenceId }) { "case_source_remove_readback_mismatch" }
    }

    suspend fun cases(scope: String, limit: Int = 30): List<CaseRecordRow> {
        require(scope.isNotBlank() && limit in 1..100)
        return dao.activeCases(scope, limit)
    }

    suspend fun openCaseCount(scope: String): Int {
        require(scope.isNotBlank())
        return dao.openCaseCount(scope)
    }

    suspend fun openCases(scope: String, limit: Int = 4): List<CaseRecordRow> {
        require(scope.isNotBlank() && limit in 1..100)
        return dao.openCases(scope, limit)
    }

    suspend fun caseSnapshot(scope: String, caseId: String): CaseSnapshot? {
        require(scope.isNotBlank() && caseId.isNotBlank())
        val record = dao.caseRecord(scope, caseId)?.takeIf { it.deletedAt == null } ?: return null
        val participants = dao.caseParticipants(scope, caseId).mapNotNull { dao.domainObject(scope, it.domainPersonId) }
            .filter { it.kind == DomainKind.PERSON.name && it.deletedAt == null }
        val evidence = dao.caseEvidence(scope, caseId).map { row -> resolveEvidenceView(row) }
        val timeline = buildList {
            dao.caseRevisions(caseId).forEach { revision ->
                val payload = runCatching { JSONObject(revision.payloadJson) }.getOrNull()
                add(CaseTimelineEntry("case:${revision.caseId}:${revision.version}", revision.changedAt,
                    payload?.optString("title")?.takeIf(String::isNotBlank).orEmpty(),
                    payload?.optString("summary")?.takeIf(String::isNotBlank), "case_revision", revision.caseId, revision.sourceTurnId))
            }
            evidence.filter { it.sourceAvailable }.forEach { item ->
                add(CaseTimelineEntry("evidence:${item.evidence.evidenceId}", item.evidence.occurredAt,
                    item.title, item.detail, item.evidence.sourceKind, item.evidence.sourceId, item.evidence.sourceRevisionId,
                    item.eventKind))
            }
        }.sortedBy { sortTime(it.at) }
        return CaseSnapshot(record, participants, evidence, timeline)
    }

    suspend fun searchCases(scope: String, query: String, limit: Int = 8): List<CaseRecordRow> {
        require(scope.isNotBlank() && query.length <= 200 && limit in 1..30)
        val needle = query.trim().lowercase()
        return dao.activeCases(scope, 100).filter { record ->
            needle.isEmpty() || record.title.lowercase().contains(needle) || record.summary.orEmpty().lowercase().contains(needle) ||
                record.nextAction.orEmpty().lowercase().contains(needle) || dao.caseParticipants(scope, record.caseId).any { participant ->
                    dao.domainObject(scope, participant.domainPersonId)?.title?.lowercase()?.contains(needle) == true
                }
        }.take(limit)
    }

    private suspend fun resolveSource(scope: String, kind: String, sourceId: String, revisionId: String?, now: String): Pair<String, String?>? = when (kind) {
        "money_event" -> dao.scopedMoneyEvent(scope, sourceId)?.let { (it.occurredAt ?: it.recordedAt) to null }
        "domain_object" -> dao.domainObject(scope, sourceId)?.takeIf { it.deletedAt == null }?.let { row ->
            val revisions = dao.domainRevisions(sourceId)
            val revision = revisionId?.let { id -> revisions.firstOrNull { it.sourceRevisionId == id } ?: return null }
            (revision?.changedAt ?: revisions.lastOrNull()?.changedAt ?: now) to revision?.sourceRevisionId
        }
        "calendar_event" -> dao.calendarEvent(scope, sourceId)?.takeIf { it.status == "active" }?.let { row ->
            val at = runCatching { LocalDateTime.parse(row.startLocal).atZone(ZoneId.of(row.zoneId)).toInstant().toString() }
                .getOrElse { runCatching { java.time.LocalDate.parse(row.startLocal).atStartOfDay(ZoneId.of(row.zoneId)).toInstant().toString() }.getOrNull() }
            at?.let { it to null }
        }
        "task" -> dao.taskEvents(sourceId, scope).takeIf { it.isNotEmpty() && !dao.taskDeleted(sourceId) }?.let { now to null }
        "memory_revision" -> {
            val source = dao.sources().firstOrNull { it.scopeId == scope && it.sourceId == sourceId }
            val revision = revisionId?.let { id -> dao.revisions().firstOrNull { it.sourceId == sourceId && it.revisionId == id } }
            if (source?.archived == false && revision != null) revision.recordedAt to revision.revisionId else null
        }
        else -> null
    }

    private suspend fun resolveEvidenceView(row: CaseEvidenceRow): CaseEvidenceView {
        return when (row.sourceKind) {
            "money_event" -> domainRepository.moneyEvent(row.scopeId, row.sourceId)?.let { event ->
                val person = dao.person(row.scopeId, event.personId)
                val amount = java.math.BigDecimal.valueOf(event.minorUnits, 2).stripTrailingZeros().toPlainString()
                CaseEvidenceView(row, person?.displayName.orEmpty(),
                    "$amount ${event.currency}${event.comment?.let { " · $it" }.orEmpty()}", true, event.kind)
            } ?: unavailable(row)
            "domain_object" -> dao.domainObject(row.scopeId, row.sourceId)?.takeIf { it.deletedAt == null }?.let { source ->
                val revision = row.sourceRevisionId?.let { id -> dao.domainRevisions(source.objectId).firstOrNull { it.sourceRevisionId == id } }
                CaseEvidenceView(row, source.title, revision?.payloadJson ?: source.body, true)
            } ?: unavailable(row)
            "calendar_event" -> dao.calendarEvent(row.scopeId, row.sourceId)?.takeIf { it.status == "active" }?.let { source ->
                CaseEvidenceView(row, source.title, source.startLocal + " · " + source.zoneId, true)
            } ?: unavailable(row)
            "task" -> dao.taskEvents(row.sourceId, row.scopeId)
                .takeIf { it.isNotEmpty() && !dao.taskDeleted(row.sourceId) }
                ?.lastOrNull { it.title != null }?.let { task ->
                CaseEvidenceView(row, task.title.orEmpty(), null, true)
            } ?: unavailable(row)
            "memory_revision" -> dao.revisions().firstOrNull { it.sourceId == row.sourceId && it.revisionId == row.sourceRevisionId }
                ?.let { revision -> CaseEvidenceView(row, "", revision.text, true) } ?: unavailable(row)
            else -> unavailable(row)
        }
    }

    private fun unavailable(row: CaseEvidenceRow) = CaseEvidenceView(row, "", null, false)

    private fun validateCase(scope: String, caseId: String, title: String, summary: String?, status: String,
        nextAction: String?, sourceTurnId: String, now: String) {
        require(scope.isNotBlank() && caseId.isNotBlank() && caseId.length <= 120)
        require(title.isNotBlank() && title.length <= 200 && title.none(Char::isISOControl))
        require(summary == null || summary.length <= 4_000)
        require(status in CASE_STATUSES)
        require(nextAction == null || nextAction.length <= 500)
        require(sourceTurnId.isNotBlank() && sourceTurnId.length <= 120)
        Instant.parse(now)
    }

    private fun caseRevision(row: CaseRecordRow, sourceTurnId: String, now: String) = CaseRevisionRow(
        row.caseId, row.version, JSONObject()
            .put("title", row.title)
            .put("summary", row.summary)
            .put("status", row.status)
            .put("next_action", row.nextAction)
            .toString(), sourceTurnId, now,
    )

    private fun String?.normalizedOptional(): String? = this?.trim()?.ifEmpty { null }

    private fun normalizedName(value: String): String = VoiceMoneyRules.key(value)

    private fun sortTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private companion object {
        val CASE_STATUSES = setOf("open", "waiting", "completed", "cancelled")
    }
}

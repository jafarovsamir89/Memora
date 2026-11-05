package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskApply
import ai.memora.app.domain.TaskDecision
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.TaskRules
import ai.memora.app.domain.VoiceMoneySnapshot
import ai.memora.app.domain.VoiceMoneyRules
import ai.memora.app.domain.VoiceDebt
import ai.memora.app.domain.VoiceReceipt
import org.json.JSONArray
import org.json.JSONObject

internal enum class DomainKind { PERSON, PROJECT, NOTE }
internal enum class ColumnType { TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, MONEY, PERSON_REF, PROJECT_REF }
internal data class ColumnSpec(val id: String, val name: String, val type: ColumnType, val required: Boolean = false, val config: JSONObject = JSONObject())
internal data class DomainHit(val objectId: String, val kind: DomainKind, val title: String, val version: Long,
    val sourceRevisionId: String?, val evidenceRevisionId: String, val evidenceStartUtf16: Int,
    val evidenceEndUtf16: Int, val evidenceText: String)
internal data class DeletePreview(val objectId: String, val expectedVersion: Long, val linkedIds: List<String>)
internal data class LinkedDomainObject(val link: DomainLinkRow, val objectRecord: DomainObjectRow)
internal data class ImportPreview(val tableId: String, val schemaVersion: Long, val rows: List<Pair<String, Map<String, Any?>>>)
internal data class TableSnapshot(val table: UserTableRow, val columns: List<TableColumnRow>, val rows: List<TableDataRow>,
    val linkedTotalCount: Int = 0)
internal data class LinkedViewSpec(val sourceKind: DomainKind)
internal data class LinkedViewPage(val rows: List<DomainObjectRow>, val matchingCount: Int, val totalCount: Int,
    val offset: Int, val limit: Int, val nextOffset: Int?, val continuationLimitReached: Boolean)

internal const val LINKED_VIEW_MAX_OFFSET = 100_000

private class LinkedViewCollector(query: String, ascending: Boolean, private val capacity: Int, locale: java.util.Locale) {
    private val needle = query.trim()
    private val collator = java.text.Collator.getInstance(locale)
    private val order = Comparator<DomainObjectRow> { a, b ->
        val titleOrder = collator.compare(a.title, b.title).let { if (ascending) it else -it }
        if (titleOrder != 0) titleOrder else a.objectId.compareTo(b.objectId)
    }
    private val best = java.util.PriorityQueue<DomainObjectRow>(capacity.coerceAtLeast(1), order.reversed())
    var matchingCount: Int = 0
        private set

    fun add(rows: List<DomainObjectRow>) {
        rows.forEach { row ->
            if (needle.isNotBlank() && listOf(row.objectId, row.title, row.body.orEmpty(), row.status)
                    .none { it.contains(needle, ignoreCase = true) }) return@forEach
            matchingCount++
            if (best.size < capacity) best.add(row)
            else if (order.compare(row, best.peek()) < 0) { best.poll(); best.add(row) }
        }
    }

    fun page(totalCount: Int, requestedOffset: Int, limit: Int): LinkedViewPage {
        val offset = requestedOffset.coerceAtMost(matchingCount)
        val rows = best.toList().sortedWith(order).drop(offset).take(limit)
        val candidateNext = offset + rows.size
        val hasMore = candidateNext < matchingCount
        val next = candidateNext.takeIf { hasMore && it <= LINKED_VIEW_MAX_OFFSET }
        return LinkedViewPage(rows, matchingCount, totalCount, offset, limit, next, hasMore && next == null)
    }
}

private val LINKABLE_DOMAIN_KINDS = setOf(DomainKind.PERSON, DomainKind.PROJECT, DomainKind.NOTE)

private fun encodeLinkedViewSpec(spec: LinkedViewSpec): String = JSONObject()
    .put("version", 1).put("sourceKind", spec.sourceKind.name).toString()

private fun decodeLinkedViewSpec(encoded: String): LinkedViewSpec {
    require(encoded.length <= 256) { "linked_view_spec_invalid" }
    val json = JSONObject(encoded)
    require(json.length() == 2 && json.getInt("version") == 1) { "linked_view_spec_invalid" }
    val kind = runCatching { DomainKind.valueOf(json.getString("sourceKind")) }
        .getOrElse { error("linked_view_spec_invalid") }
    require(kind in LINKABLE_DOMAIN_KINDS) { "linked_view_kind_not_supported" }
    return LinkedViewSpec(kind)
}
internal data class CalendarInput(val eventId: String, val scopeId: String, val title: String, val startLocal: String, val endLocal: String?, val zoneId: String, val precision: String)
internal data class FinanceHistory(val person: PersonRow, val events: List<MoneyEventRow>, val remainingDebtMinorByEvent: Map<String, Long>,
    val voidedEventIds: Set<String>)
internal data class FinanceReversedAllocation(val obligationId: String, val minorUnits: Long)
internal data class FinanceReversalResult(val event: MoneyEventRow, val reversedAllocations: List<FinanceReversedAllocation>)
internal data class FinanceCorrectionResult(val event: MoneyEventRow, val adjustedAllocations: List<FinanceReversedAllocation>,
    val remainingDebtMinorByEvent: Map<String, Long>)

/** Shared protected local domain store used by agent tools and visible Android data screens. */
internal class ProtectedDomainRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()
    internal fun caseRepository() = ProtectedCaseRepository(db)

    suspend fun hasVerifiedLegacyFinance(scope: String): Boolean {
        val marker = dao.migration("voice-money-to-ledger-v1") ?: return false
        check(marker.verified && marker.schemaVersion in 1..2 && marker.itemCount >= 0) { "legacy_finance_manifest_invalid" }
        check(dao.people(scope).size + dao.moneyEvents(scope).size >= marker.itemCount) { "legacy_finance_readback_mismatch" }
        return true
    }

    /** Copy a validated legacy AES-GCM snapshot into SQLCipher without modifying the source file. */
    suspend fun migrateLegacyFinance(scope: String, snapshot: VoiceMoneySnapshot, digest: String, sourceSchema: Int): MigrationResult {
        require(scope.isNotBlank() && digest.matches(Regex("[0-9a-f]{64}")) && sourceSchema in 1..2)
        val marker = LegacyMigrationRow("voice-money-to-ledger-v1", digest, sourceSchema,
            snapshot.persons.size + snapshot.receipts.size + snapshot.debts.size, true)
        val names = snapshot.persons.map { VoiceMoneyRules.key(it.name) }
        require(names.none(String::isBlank) && snapshot.persons.all { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 100 })
        require(snapshot.persons.map { it.id }.distinct().size == snapshot.persons.size)
        val sourceEvents = snapshot.receipts.map { it.id to it.commandId } + snapshot.debts.map { it.id to it.commandId }
        require(sourceEvents.map { it.first }.distinct().size == sourceEvents.size && sourceEvents.map { it.second }.distinct().size == sourceEvents.size)
        require((snapshot.receipts.map { it.personId } + snapshot.debts.map { it.personId }).all { id -> snapshot.persons.any { it.id == id } })
        return db.withTransaction {
            val expectedPeople = snapshot.persons.map { PersonRow(it.id, it.name, scope) }
            val expectedEvents = snapshot.receipts.map { it.toLedger(scope) } + snapshot.debts.map { it.toLedger(scope) }
            val prior = dao.migration(marker.storeName)
            if (prior != null) {
                check(prior.verified && prior.schemaVersion in 1..2 && prior.itemCount >= 0) { "legacy_finance_manifest_invalid" }
                if (prior.sourceDigest == digest && prior.schemaVersion == sourceSchema) {
                    val currentPeople = dao.people(scope).associateBy { it.personId }
                    val currentEvents = dao.moneyEvents(scope).associateBy { it.eventId }
                    check(expectedPeople.all { currentPeople[it.personId] == it } && expectedEvents.all { currentEvents[it.eventId] == it }) {
                        "legacy_finance_readback_mismatch"
                    }
                } else {
                    // The legacy file is retired after cutover. Ignore later edits there; the ledger is authoritative.
                    check(dao.people(scope).size + dao.moneyEvents(scope).size >= prior.itemCount) { "legacy_finance_readback_mismatch" }
                }
                return@withTransaction MigrationResult.AlreadyApplied
            }

            val allPeople = dao.people().associateBy { it.personId }
            val allEvents = dao.moneyEvents().associateBy { it.eventId }
            val existingCommands = allEvents.values.associateBy { it.scopeId to it.commandId }
            expectedPeople.forEach { incoming ->
                val existing = allPeople[incoming.personId]
                if (existing == null) dao.insertPerson(incoming)
                else if (existing != incoming) {
                    check(existing.displayName == incoming.displayName && existing.scopeId == "local") { "legacy_finance_person_collision" }
                    check(dao.movePersonScope(incoming.personId, "local", scope) == 1) { "legacy_finance_person_collision" }
                }
            }
            expectedEvents.forEach { incoming ->
                val byId = allEvents[incoming.eventId]
                val byCommand = existingCommands.values.firstOrNull { it.commandId == incoming.commandId }
                if (byCommand != null && byCommand.eventId != incoming.eventId) check(false) { "legacy_finance_command_collision" }
                if (byId == null) dao.insertMoneyEvent(incoming)
                else if (byId != incoming) {
                    val sameSourceRecord = byId.scopeId == "local" && byId.commandId == incoming.commandId &&
                        byId.personId == incoming.personId && byId.kind == incoming.kind && byId.minorUnits == incoming.minorUnits &&
                        byId.currency == incoming.currency && byId.source == incoming.source && byId.recordedAt == incoming.recordedAt
                    check(sameSourceRecord) { "legacy_finance_event_collision" }
                    check(dao.moveLegacyMoneyEvent(incoming.eventId, "local", scope, incoming.occurredAt, incoming.comment) == 1) {
                        "legacy_finance_event_collision"
                    }
                }
            }
            dao.putMigrations(listOf(marker))
            val copiedPeople = dao.people(scope).associateBy { it.personId }
            val copiedEvents = dao.moneyEvents(scope).associateBy { it.eventId }
            check(expectedPeople.all { copiedPeople[it.personId] == it } && expectedEvents.all { copiedEvents[it.eventId] == it }) {
                "legacy_finance_readback_mismatch"
            }
            MigrationResult.Applied(marker.itemCount)
        }
    }

    suspend fun matchingFinancePeople(scope: String, name: String): List<PersonRow> {
        require(name.isNotBlank() && name.length <= 100)
        val key = VoiceMoneyRules.key(name)
        return dao.people(scope).filter { VoiceMoneyRules.key(it.displayName) == key }
    }

    private suspend fun effectiveMoneyEvent(scope: String, event: MoneyEventRow): MoneyEventRow =
        event.copy(minorUnits = adjustedAmount(scope, event))

    private suspend fun adjustedAmount(scope: String, event: MoneyEventRow): Long {
        val adjustments = dao.adjustments(scope, event.eventId).filter {
            it.kind == "amount_correction" && it.reversesEventId == event.eventId
        }
        return Math.addExact(event.minorUnits, adjustments.fold(0L) { total, item -> Math.addExact(total, item.minorUnits) })
    }

    private fun effectiveSettlementAmount(all: List<MoneyAdjustmentRow>, settlement: MoneyAdjustmentRow): Long {
        val corrections = all.asSequence().filter { it.kind == "correction" && it.reversesEventId == settlement.eventId }
            .fold(0L) { total, item -> Math.addExact(total, item.minorUnits) }
        val reversals = all.asSequence().filter { it.kind == "reversal" && it.reversesEventId == settlement.eventId }
            .fold(0L) { total, item -> Math.addExact(total, item.minorUnits) }
        return Math.subtractExact(Math.addExact(settlement.minorUnits, corrections), reversals)
    }

    suspend fun financeHistory(scope: String, name: String): FinanceHistory? {
        val matches = matchingFinancePeople(scope, name)
        check(matches.size <= 1) { "person_name_ambiguous" }
        return matches.singleOrNull()?.let { financeHistoryByPersonId(scope, it.personId) }
    }

    suspend fun financeHistoryByPersonId(scope: String, personId: String): FinanceHistory? {
        require(scope.isNotBlank() && personId.isNotBlank())
        val person = dao.person(scope, personId) ?: return null
        val events = dao.moneyEventsForPerson(scope, person.personId).map { effectiveMoneyEvent(scope, it) }
        val remaining = events.filter { it.kind == "debt" }.associate { debt -> debt.eventId to outstandingDebtMinor(scope, debt.eventId) }
        val voided = events.filter { isFinanceEventVoided(scope, it.eventId) }.map { it.eventId }.toSet()
        return FinanceHistory(person, events, remaining, voided)
    }

    suspend fun moneyEvent(scope: String, eventId: String): MoneyEventRow? = dao.moneyEvent(scope, eventId)?.let { effectiveMoneyEvent(scope, it) }

    suspend fun outstandingDebtMinor(scope: String, obligationId: String): Long {
        val debt = dao.moneyEvent(scope, obligationId)?.takeIf { it.kind == "debt" } ?: error("obligation_not_found")
        val adjustments = dao.adjustments(scope, obligationId)
        if (isFinanceEventVoided(scope, obligationId)) return 0L
        val principal = adjustedAmount(scope, debt)
        check(adjustments.all { it.kind in setOf("settlement", "correction", "reversal", "amount_correction", "event_restore", "event_void", "annotation") }) {
            "invalid_ledger_event"
        }
        val settled = adjustments.filter { it.kind in setOf("settlement", "correction", "reversal") }.fold(0L) { total, adjustment -> when (adjustment.kind) {
            "settlement", "correction" -> Math.addExact(total, adjustment.minorUnits)
            "reversal" -> Math.subtractExact(total, adjustment.minorUnits)
            else -> error("invalid_ledger_event")
        } }
        check(settled in 0..principal) { "invalid_ledger_balance" }
        return Math.subtractExact(principal, settled)
    }

    suspend fun isFinanceEventVoided(scope: String, eventId: String): Boolean =
        dao.adjustments(scope, eventId).filter { it.kind in setOf("event_void", "event_restore") }
            .sortedWith(compareBy<MoneyAdjustmentRow> { it.occurredAt.orEmpty() }.thenBy { it.eventId })
            .fold(false) { state, item -> item.kind == "event_void" }

    suspend fun canReverseFinanceEvent(scope: String, eventId: String): String? {
        val event = dao.moneyEvent(scope, eventId) ?: return "event_not_found"
        val adjustments = dao.adjustments(scope, eventId)
        if (isFinanceEventVoided(scope, eventId)) return "event_already_reversed"
        if (event.kind == "debt" && adjustments.any { it.kind in setOf("settlement", "correction", "reversal") })
            return "event_has_settlements"
        return null
    }

    suspend fun appliedToDebtMinor(scope: String, paymentEventId: String): Long {
        val payment = dao.moneyEvent(scope, paymentEventId)?.takeIf { it.kind == "receipt" } ?: error("payment_event_not_found")
        val all = dao.adjustments(scope)
        val linked = all.filter { it.kind == "settlement" && it.reversesEventId == payment.eventId }
        val linkedIds = linked.map { it.eventId }.toSet()
        val applied = linked.fold(0L) { total, item -> Math.addExact(total, effectiveSettlementAmount(all, item)) }
        check(applied >= 0L) { "invalid_ledger_event" }
        return applied
    }

    suspend fun reverseFinanceEvent(scope: String, eventId: String, commandId: String, reason: String, occurredAt: String): FinanceReversalResult = db.withTransaction {
        val event = dao.moneyEvent(scope, eventId) ?: error("event_not_found")
        require(reason.isNotBlank() && reason.length <= 500)
        java.time.Instant.parse(occurredAt)
        dao.adjustmentForCommand(commandId)?.let { existing ->
            check(existing.scopeId == scope && existing.obligationId == eventId && existing.kind == "event_void" &&
                existing.reversesEventId == eventId && existing.reason == reason) { "idempotency_conflict" }
            val all = dao.adjustments(scope)
            val priorLinked = all.filter { it.kind == "settlement" && it.reversesEventId == eventId }
                .map { it to effectiveSettlementAmount(all, it) }.filter { it.second > 0 }
            return@withTransaction FinanceReversalResult(event, priorLinked.groupBy { it.first.obligationId }.map { (obligationId, rows) ->
                FinanceReversedAllocation(obligationId, rows.fold(0L) { total, item -> Math.addExact(total, item.second) })
            })
        }
        check(!isFinanceEventVoided(scope, eventId)) { "event_already_reversed" }
        if (event.kind == "debt") check(dao.adjustments(scope, eventId).none { it.kind in setOf("settlement", "correction", "reversal") }) {
            "event_has_settlements"
        }
        val allAdjustments = dao.adjustments(scope)
        val allocations = if (event.kind == "receipt") {
            allAdjustments.filter { it.kind == "settlement" && it.reversesEventId == eventId }
                .map { it to effectiveSettlementAmount(allAdjustments, it) }.filter { it.second > 0 }
        } else emptyList()
        allocations.forEachIndexed { index, (allocation, effectiveAmount) ->
            dao.insertAdjustment(MoneyAdjustmentRow(
                UUID.randomUUID().toString(), "$commandId:link:$index", allocation.obligationId,
                "reversal", effectiveAmount, allocation.currency, occurredAt, allocation.eventId,
                "${reason.take(430)} (receipt allocation reversed)", scope,
            ))
        }
        dao.insertAdjustment(MoneyAdjustmentRow(commandId, commandId, eventId, "event_void", event.minorUnits,
            event.currency, occurredAt, eventId, reason, scope))
        val voidReadback = requireNotNull(dao.adjustmentForCommand(commandId))
        check(voidReadback.scopeId == scope && voidReadback.eventId == commandId && voidReadback.obligationId == eventId &&
            voidReadback.kind == "event_void" && voidReadback.reversesEventId == eventId && voidReadback.reason == reason) {
            "finance_reversal_readback_mismatch"
        }
        val readbackAdjustments = dao.adjustments(scope)
        check(isFinanceEventVoided(scope, eventId)) { "finance_reversal_readback_mismatch" }
        check(allocations.mapIndexed { index, (allocation, amount) ->
            readbackAdjustments.any { it.commandId == "$commandId:link:$index" && it.kind == "reversal" &&
                it.reversesEventId == allocation.eventId && it.minorUnits == amount }
        }.all { it }) { "finance_reversal_allocation_readback_mismatch" }
        FinanceReversalResult(event, allocations.groupBy { it.first.obligationId }.map { (obligationId, rows) ->
            FinanceReversedAllocation(obligationId, rows.fold(0L) { total, item -> Math.addExact(total, item.second) })
        })
    }

    suspend fun allFinanceEvents(scope: String): List<MoneyEventRow> = dao.moneyEvents(scope).map { effectiveMoneyEvent(scope, it) }
    suspend fun financePeople(scope: String): List<PersonRow> = dao.people(scope)
    suspend fun financeAdjustments(scope: String): List<MoneyAdjustmentRow> = dao.adjustments(scope)

    /** Exact month comparison over the canonical ledger; debt originations and outstanding debt stay separate. */
    suspend fun financeAnalytics(scope: String, month: YearMonth, zone: ZoneId = FinanceAnalytics.bakuZone): FinanceAnalyticsSnapshot {
        require(scope.isNotBlank())
        return db.withTransaction {
            val events = dao.financeEventsForAnalysis(scope)
            val adjustments = dao.financeAdjustmentsForAnalysis(scope)
            val people = dao.people(scope).associate { it.personId to it.displayName }
            FinanceAnalytics.fromLedger(month, events, adjustments, people, zone)
        }
    }

    /** Appends the user's verbatim follow-up to one existing ledger event without changing its amount. */
    suspend fun annotateFinanceEvent(scope: String, eventId: String, commandId: String, annotation: String,
        occurredAt: String): MoneyAdjustmentRow = db.withTransaction {
        require(scope.isNotBlank() && eventId.isNotBlank() && commandId.isNotBlank())
        require(annotation.isNotBlank() && annotation.length <= 500)
        java.time.Instant.parse(occurredAt)
        val event = dao.scopedMoneyEvent(scope, eventId) ?: error("event_not_found")
        check(!isFinanceEventVoided(scope, eventId)) { "event_already_reversed" }
        val byCommand = dao.adjustmentForCommand(commandId)
        if (byCommand != null) {
            check(byCommand.scopeId == scope && byCommand.obligationId == eventId && byCommand.kind == "annotation" &&
                byCommand.reversesEventId == eventId && byCommand.reason == annotation && byCommand.minorUnits == 0L) {
                "idempotency_conflict"
            }
            return@withTransaction byCommand
        }
        val duplicate = dao.adjustments(scope, eventId).firstOrNull {
            it.kind == "annotation" && it.reversesEventId == eventId && it.reason == annotation
        }
        if (duplicate != null) return@withTransaction duplicate
        val row = MoneyAdjustmentRow(commandId, commandId, eventId, "annotation", 0L, event.currency,
            occurredAt, eventId, annotation, scope)
        dao.insertAdjustment(row)
        val readback = requireNotNull(dao.adjustmentForCommand(commandId))
        check(readback == row && dao.scopedMoneyEvent(scope, eventId) == event) { "finance_annotation_readback_mismatch" }
        readback
    }

    suspend fun recordFinanceEvent(scope: String, personName: String, kind: String, minorUnits: Long, currency: String,
        source: String, comment: String?, personId: String, eventId: String, commandId: String,
        recordedAt: String, occurredAt: String): MoneyEventRow = db.withTransaction {
        require(scope.isNotBlank() && personName.length <= 100 && (kind == "expense" || personName.isNotBlank()))
        require(kind in setOf("receipt", "debt", "expense") && minorUnits > 0 && currency == "AZN")
        require(source.isNotBlank() && source.length <= 20_000 && (comment?.length ?: 0) <= 1000)
        require(eventId.isNotBlank() && eventId.length <= 100 && commandId.isNotBlank() && commandId.length <= 100 && personId.length <= 100)
        java.time.Instant.parse(recordedAt); java.time.Instant.parse(occurredAt)

        val existingCommand = dao.moneyEventForCommand(scope, commandId)
        if (existingCommand != null) {
            val oldPerson = dao.person(scope, existingCommand.personId)
            val personMatches = if (personName.isBlank()) existingCommand.personId.isBlank()
                else oldPerson != null && VoiceMoneyRules.key(oldPerson.displayName) == VoiceMoneyRules.key(personName)
            check(personMatches &&
                existingCommand.kind == kind && existingCommand.minorUnits == minorUnits && existingCommand.currency == currency &&
                existingCommand.source == source && existingCommand.comment == comment?.trim()?.ifEmpty { null } &&
                existingCommand.occurredAt == occurredAt) { "idempotency_conflict" }
            return@withTransaction existingCommand
        }
        val matches = if (personName.isBlank()) emptyList() else dao.people(scope)
            .filter { VoiceMoneyRules.key(it.displayName) == VoiceMoneyRules.key(personName) }
        check(matches.size <= 1) { "person_name_ambiguous" }
        val existingPerson = matches.singleOrNull()
        val person = existingPerson ?: personName.takeIf { it.isNotBlank() }?.let { PersonRow(personId, it.trim(), scope) }
        val resolvedPersonId = person?.personId.orEmpty()
        val incoming = MoneyEventRow(eventId, commandId, resolvedPersonId, kind, minorUnits, currency, source,
            recordedAt, occurredAt, comment?.trim()?.ifEmpty { null }, scope)
        val idCollision = dao.moneyEvents().firstOrNull { it.eventId == eventId }
        check(idCollision == null) { "money_event_id_conflict" }
        if (person != null) {
            val existingId = dao.people().firstOrNull { it.personId == person.personId }
            check(existingId == null || existingId == person) { "money_person_id_conflict" }
            if (existingPerson == null) dao.insertPerson(person)
        }
        dao.insertMoneyEvent(incoming)
        val readback = requireNotNull(dao.scopedMoneyEvent(scope, eventId))
        check(readback == incoming && (person == null || dao.person(scope, person.personId) == person)) { "finance_write_readback_mismatch" }
        readback
    }

    suspend fun tables(scope: String): List<TableSnapshot> = dao.activeUserTables(scope).map { table ->
        val linkedTotalCount = if (table.mode == "linked_view") {
            val spec = decodeLinkedViewSpec(table.viewSpecJson ?: error("linked_view_spec_missing"))
            dao.countActiveDomainObjects(scope, spec.sourceKind.name)
        } else 0
        TableSnapshot(table, dao.columns(table.tableId), dao.rows(table.tableId, scope), linkedTotalCount = linkedTotalCount)
    }

    suspend fun linkedViewPage(scope: String, tableId: String, query: String, ascending: Boolean, offset: Int, limit: Int,
        locale: java.util.Locale = java.util.Locale.getDefault()): LinkedViewPage {
        require(scope.isNotBlank() && tableId.isNotBlank() && query.length <= 200 && offset in 0..LINKED_VIEW_MAX_OFFSET && limit in 1..50)
        return db.withTransaction {
            val table = dao.userTable(scope, tableId)?.takeIf { it.deletedAt == null && it.mode == "linked_view" }
                ?: error("linked_view_not_found")
            val spec = decodeLinkedViewSpec(table.viewSpecJson ?: error("linked_view_spec_missing"))
            val totalCount = dao.countActiveDomainObjects(scope, spec.sourceKind.name)
            val collector = LinkedViewCollector(query, ascending, offset + limit, locale)
            var readOffset = 0
            while (true) {
                val chunk = dao.activeDomainObjectsPage(scope, spec.sourceKind.name, 200, readOffset)
                if (chunk.isEmpty()) break
                collector.add(chunk)
                readOffset += chunk.size
                if (chunk.size < 200) break
            }
            collector.page(totalCount, offset, limit)
        }
    }

    suspend fun deletedRows(scope: String, tableId: String): List<TableDataRow> = dao.deletedRows(tableId, scope)

    suspend fun table(scope: String, id: String): UserTableRow? = dao.userTable(scope, id)
    suspend fun tableRow(scope: String, tableId: String, rowId: String): TableDataRow? =
        dao.rowById(rowId)?.takeIf { it.scopeId == scope && it.tableId == tableId }
    suspend fun domainObject(scope: String, id: String): DomainObjectRow? = dao.domainObject(scope, id)
    suspend fun domainObjects(scope: String, kind: DomainKind, limit: Int = 200): List<DomainObjectRow> {
        require(scope.isNotBlank() && limit in 1..500)
        return dao.activeDomainObjects(scope, kind.name, limit)
    }

    /** Returns only persisted, active, same-scope links. It never infers identity from a matching name. */
    suspend fun linkedObjects(scope: String, objectId: String, limit: Int = 50): List<LinkedDomainObject> {
        require(scope.isNotBlank() && objectId.isNotBlank() && limit in 1..100)
        val source = dao.domainObject(scope, objectId)?.takeIf { it.deletedAt == null } ?: return emptyList()
        return dao.activeLinksForObject(scope, source.objectId, limit).mapNotNull { link ->
            val linkedId = if (link.fromId == source.objectId) link.toId else link.fromId
            dao.domainObject(scope, linkedId)?.takeIf { it.deletedAt == null }
                ?.let { LinkedDomainObject(link, it) }
        }
    }

    suspend fun createObject(scope: String, id: String, kind: DomainKind, title: String, body: String?,
        sourceRevisionId: String?, now: String): DomainObjectRow = db.withTransaction {
        require(scope.isNotBlank() && id.isNotBlank()); requireTitle(title)
        require(body == null || body.length <= 100_000)
        val row = DomainObjectRow(id, scope, kind.name, title, body, "active", 1, null)
        dao.insertDomainObject(row)
        val revision = DomainRevisionRow(id, 1, payload(title, body, "active", null), sourceRevisionId, now)
        dao.insertDomainRevision(revision)
        check(dao.domainObject(scope, id) == row && dao.domainRevisions(id).lastOrNull() == revision) { "domain_create_readback_mismatch" }
        row
    }

    suspend fun updateObject(scope: String, id: String, expectedVersion: Long, title: String, body: String?,
        status: String, sourceRevisionId: String?, now: String): DomainObjectRow = db.withTransaction {
        val current = dao.domainObject(scope, id) ?: error("not_found")
        check(current.version == expectedVersion && current.deletedAt == null) { "version_conflict" }
        requireTitle(title); require(body == null || body.length <= 100_000)
        require(status in setOf("active", "archived")); check(expectedVersion < Long.MAX_VALUE)
        val next = expectedVersion + 1
        check(dao.updateDomainObject(scope, id, expectedVersion, next, title, body, status) == 1) { "version_conflict" }
        val revision = DomainRevisionRow(id, next, payload(title, body, status, null), sourceRevisionId, now)
        dao.insertDomainRevision(revision)
        val readback = requireNotNull(dao.domainObject(scope, id))
        check(readback.version == next && readback.title == title && readback.body == body && readback.status == status &&
            dao.domainRevisions(id).lastOrNull() == revision) { "domain_update_readback_mismatch" }
        readback
    }

    suspend fun link(scope: String, linkId: String, fromId: String, toId: String, relation: String) = db.withTransaction {
        require(fromId != toId && relation.matches(Regex("[a-z][a-z0-9_]{0,39}")))
        val from = dao.domainObject(scope, fromId); val to = dao.domainObject(scope, toId)
        check(from?.deletedAt == null && to?.deletedAt == null) { "not_found" }
        check(dao.activeLinks(scope).none { it.fromId == fromId && it.toId == toId && it.relation == relation }) { "duplicate_link" }
        val row = DomainLinkRow(linkId, scope, fromId, toId, relation, 1, null)
        dao.insertDomainLink(row)
        check(dao.activeLinks(scope).any { it == row }) { "domain_link_readback_mismatch" }
    }

    suspend fun previewDelete(scope: String, id: String, expectedVersion: Long): DeletePreview {
        val row = dao.domainObject(scope, id) ?: error("not_found")
        check(row.deletedAt == null && row.version == expectedVersion) { "version_conflict" }
        return DeletePreview(id, expectedVersion, dao.activeLinks(scope).filter { it.fromId == id || it.toId == id }
            .flatMap { listOf(it.fromId, it.toId) }.filter { it != id }.distinct().sorted())
    }

    suspend fun delete(scope: String, preview: DeletePreview, now: String) = db.withTransaction {
        check(preview == previewDelete(scope, preview.objectId, preview.expectedVersion)) { "preview_changed" }
        check(preview.expectedVersion < Long.MAX_VALUE)
        check(dao.tombstoneDomainObject(scope, preview.objectId, preview.expectedVersion, preview.expectedVersion + 1, now) == 1)
        val row = requireNotNull(dao.domainObject(scope, preview.objectId))
        val sourceRevisionId = dao.domainRevisions(row.objectId).lastOrNull()?.sourceRevisionId
        val revision = DomainRevisionRow(row.objectId, row.version, payload(row.title, row.body, row.status, row.deletedAt), sourceRevisionId, now)
        dao.insertDomainRevision(revision)
        check(dao.domainRevisions(row.objectId).lastOrNull() == revision) { "domain_delete_readback_mismatch" }
    }

    suspend fun restore(scope: String, id: String, expectedVersion: Long, now: String): DomainObjectRow = db.withTransaction {
        val current = dao.domainObject(scope, id) ?: error("not_found")
        check(current.deletedAt != null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.restoreDomainObject(scope, id, expectedVersion, expectedVersion + 1) == 1)
        val restored = requireNotNull(dao.domainObject(scope, id))
        val sourceRevisionId = dao.domainRevisions(id).lastOrNull()?.sourceRevisionId
        val revision = DomainRevisionRow(id, restored.version, payload(restored.title, restored.body, restored.status, null), sourceRevisionId, now)
        dao.insertDomainRevision(revision)
        check(dao.domainRevisions(id).lastOrNull() == revision) { "domain_restore_readback_mismatch" }
        restored
    }

    suspend fun createTable(scope: String, id: String, title: String, mode: String, schema: List<ColumnSpec>,
        now: String = java.time.Instant.now().toString(), viewSpec: LinkedViewSpec? = null) = db.withTransaction {
        requireTitle(title); require(mode in setOf("editable", "linked_view")); validateSchema(schema)
        require((mode == "linked_view") == (viewSpec != null)) { "linked_view_spec_required" }
        viewSpec?.let { require(it.sourceKind in LINKABLE_DOMAIN_KINDS) { "linked_view_kind_not_supported" } }
        val viewSpecJson = viewSpec?.let(::encodeLinkedViewSpec)
        dao.insertUserTable(UserTableRow(id, scope, title, mode, 1, 1, null, viewSpecJson))
        val columns = schema.mapIndexed { index, col -> TableColumnRow(id, col.id, index, col.name, col.type.name, col.required, col.config.toString()) }
        dao.insertColumns(columns)
        val revision = TableSchemaRevisionRow(id, 1, columnsJson(columns), now)
        dao.insertSchemaRevision(revision)
        check(dao.userTable(scope, id) == UserTableRow(id, scope, title, mode, 1, 1, null, viewSpecJson) &&
            dao.columns(id) == columns && dao.schemaRevisions(id).lastOrNull() == revision) { "table_create_readback_mismatch" }
    }

    /** Safe schema edits preserve every existing column ID/type and only append optional columns. */
    suspend fun changeTableSchema(scope: String, tableId: String, expectedSchemaVersion: Long,
        schema: List<ColumnSpec>, now: String): Long = db.withTransaction {
        validateSchema(schema)
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.schemaVersion == expectedSchemaVersion) { "schema_conflict" }
        check(table.mode == "editable") { "linked_view_read_only" }
        check(expectedSchemaVersion < Long.MAX_VALUE)
        val old = dao.columns(tableId).associateBy { it.columnId }
        val requested = schema.associateBy { it.id }
        require(old.keys.all { it in requested }) { "destructive_schema_change_requires_preview" }
        old.forEach { (id, previous) ->
            val next = requested.getValue(id)
            require(previous.valueType == next.type.name && previous.required == next.required && previous.configJson == next.config.toString()) {
                "type_change_requires_preview"
            }
        }
        schema.filter { it.id !in old }.forEach { require(!it.required) { "new_required_column_requires_default" } }
        check(dao.updateTableSchemaVersion(scope, tableId, expectedSchemaVersion, expectedSchemaVersion + 1) == 1) { "schema_conflict" }
        val updated = schema.mapIndexed { index, col -> TableColumnRow(tableId, col.id, index, col.name, col.type.name, col.required, col.config.toString()) }
        dao.updateColumns(updated.filter { it.columnId in old })
        dao.insertColumns(updated.filter { it.columnId !in old })
        val revision = TableSchemaRevisionRow(tableId, expectedSchemaVersion + 1, columnsJson(updated), now)
        dao.insertSchemaRevision(revision)
        check(dao.userTable(scope, tableId)?.schemaVersion == expectedSchemaVersion + 1 &&
            dao.columns(tableId) == updated && dao.schemaRevisions(tableId).lastOrNull() == revision) { "table_schema_readback_mismatch" }
        expectedSchemaVersion + 1
    }

    suspend fun renameTable(scope: String, tableId: String, expectedVersion: Long, title: String): UserTableRow = db.withTransaction {
        requireTitle(title)
        val current = dao.userTable(scope, tableId) ?: error("not_found")
        check(current.deletedAt == null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.renameUserTable(scope, tableId, expectedVersion, expectedVersion + 1, title.trim()) == 1) { "version_conflict" }
        val readback = requireNotNull(dao.userTable(scope, tableId))
        check(readback == current.copy(title = title.trim(), version = expectedVersion + 1)) { "table_rename_readback_mismatch" }
        readback
    }

    suspend fun deleteTable(scope: String, tableId: String, expectedVersion: Long, now: String) = db.withTransaction {
        val current = dao.userTable(scope, tableId) ?: error("not_found")
        check(current.deletedAt == null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.tombstoneUserTable(scope, tableId, expectedVersion, expectedVersion + 1, now) == 1) { "version_conflict" }
        check(dao.userTable(scope, tableId) == current.copy(version = expectedVersion + 1, deletedAt = now)) { "table_delete_readback_mismatch" }
    }

    suspend fun restoreTable(scope: String, tableId: String, expectedVersion: Long) = db.withTransaction {
        val current = dao.userTable(scope, tableId) ?: error("not_found")
        check(current.deletedAt != null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.restoreUserTable(scope, tableId, expectedVersion, expectedVersion + 1) == 1) { "version_conflict" }
        check(dao.userTable(scope, tableId) == current.copy(version = expectedVersion + 1, deletedAt = null)) { "table_restore_readback_mismatch" }
    }

    suspend fun addRows(scope: String, tableId: String, expectedSchemaVersion: Long, rowValues: List<Pair<String, Map<String, Any?>>>) = db.withTransaction {
        require(rowValues.size in 1..100)
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.schemaVersion == expectedSchemaVersion) { "schema_conflict" }
        check(table.mode == "editable") { "linked_view_read_only" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        val ids = hashSetOf<String>()
        val existingRows = dao.rows(tableId, scope).associateBy { it.rowId }
        val prepared = rowValues.map { (id, values) ->
            require(id.isNotBlank() && ids.add(id)); validateRow(scope, columns, values)
            val encoded = encodeValues(values)
            existingRows[id]?.let { existing -> check(existing.valuesJson == encoded) { "idempotency_conflict" } }
            existingRows[id] ?: TableDataRow(id, tableId, scope, 1, encoded, null)
        }
        val missing = prepared.filter { it.rowId !in existingRows }
        missing.forEach { row -> check(dao.rowById(row.rowId) == null) { "row_id_exists_in_another_table" } }
        missing.forEach { dao.insertTableRows(listOf(it)) }
        val readback = dao.rows(tableId, scope).associateBy { it.rowId }
        check(prepared.all { readback[it.rowId] == it }) { "table_rows_add_readback_mismatch" }
        prepared.map { readback.getValue(it.rowId) }
    }

    suspend fun updateRow(scope: String, tableId: String, rowId: String, expectedVersion: Long,
        expectedSchemaVersion: Long, values: Map<String, Any?>): TableDataRow = db.withTransaction {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.schemaVersion == expectedSchemaVersion && table.deletedAt == null) { "schema_conflict" }
        check(table.mode == "editable") { "linked_view_read_only" }
        val existing = dao.rows(tableId, scope).firstOrNull { it.rowId == rowId } ?: error("not_found")
        check(existing.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        validateRow(scope, columns, values)
        val encoded = encodeValues(values)
        check(dao.updateTableRow(rowId, tableId, scope, expectedVersion, expectedVersion + 1, encoded) == 1)
        val expected = existing.copy(version = expectedVersion + 1, valuesJson = encoded)
        check(dao.rowById(rowId) == expected) { "table_row_update_readback_mismatch" }
        expected
    }

    suspend fun deleteRow(scope: String, tableId: String, rowId: String, expectedVersion: Long, now: String) = db.withTransaction {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.mode == "editable") { "table_not_editable" }
        val row = dao.rows(tableId, scope).firstOrNull { it.rowId == rowId } ?: error("not_found")
        check(row.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.tombstoneTableRow(rowId, tableId, scope, expectedVersion, expectedVersion + 1, now) == 1) { "version_conflict" }
        check(dao.rowById(rowId) == row.copy(version = expectedVersion + 1, deletedAt = now)) { "table_row_delete_readback_mismatch" }
    }

    suspend fun restoreRow(scope: String, tableId: String, rowId: String, expectedVersion: Long) = db.withTransaction {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.mode == "editable") { "table_not_editable" }
        val row = dao.rowById(rowId)?.takeIf { it.tableId == tableId && it.scopeId == scope } ?: error("not_found")
        check(row.deletedAt != null && row.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.restoreTableRow(rowId, tableId, scope, expectedVersion, expectedVersion + 1) == 1) { "version_conflict" }
        check(dao.rowById(rowId) == row.copy(version = expectedVersion + 1, deletedAt = null)) { "table_row_restore_readback_mismatch" }
    }

    /** Exact money aggregate; results stay partitioned by currency and never use floating point. */
    suspend fun aggregateMoney(scope: String, tableId: String, columnId: String): Map<String, Long> {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null)
        check(dao.columns(tableId).any { it.columnId == columnId && it.valueType == ColumnType.MONEY.name }) { "not_money_column" }
        val totals = sortedMapOf<String, Long>()
        for (row in dao.rows(tableId, scope)) {
            val values = JSONObject(row.valuesJson)
            if (!values.has(columnId) || values.isNull(columnId)) continue
            val money = values.getJSONObject(columnId)
            val amount = money.getLong("minorUnits"); val currency = money.getString("currency")
            totals[currency] = Math.addExact(totals[currency] ?: 0L, amount)
        }
        return totals
    }

    /** Append-only task command path; duplicate app command IDs are rejected atomically. */
    suspend fun appendTaskEvent(event: TaskEvent): Task = appendTaskEvents(listOf(event))

    suspend fun appendTaskEvents(events: List<TaskEvent>): Task = db.withTransaction {
        require(events.isNotEmpty() && events.size <= 10)
        val first = events.first()
        require(first.taskId.isNotBlank() && first.scopeId.isNotBlank() && first.actorId.isNotBlank())
        require(events.all { it.taskId == first.taskId && it.scopeId == first.scopeId && it.actorId == first.actorId && it.commandId.isNotBlank() })
        require(events.map { it.commandId }.distinct().size == events.size)
        check(events.none { dao.taskEventsForCommand(it.commandId).isNotEmpty() }) { "idempotency_conflict" }
        val current = dao.taskEvents(first.taskId, first.scopeId).asSequence()
            .fold<TaskEventRow, Task?>(null) { task, row ->
                val applied = TaskRules.apply(task, fromTaskRow(row)) as? TaskApply.Applied ?: error("task_history_invalid")
                applied.task
            }
        val result = events.fold<TaskEvent, Task?>(current) { task, event ->
            (TaskRules.apply(task, event) as? TaskApply.Applied ?: error("task_conflict_or_invalid")).task
        }
        val expectedRows = events.map(::toTaskRow)
        dao.putTaskEvents(expectedRows)
        val readbackRows = events.map { event -> dao.taskEventsForCommand(event.commandId).singleOrNull() }
        check(readbackRows.size == expectedRows.size && expectedRows.indices.all { index ->
            readbackRows[index]?.let { it.copy(sequence = expectedRows[index].sequence) == expectedRows[index] } == true
        }) { "task_event_write_readback_mismatch" }
        val readback = dao.taskEvents(first.taskId, first.scopeId).fold<TaskEventRow, Task?>(null) { task, row ->
            (TaskRules.apply(task, fromTaskRow(row)) as? TaskApply.Applied ?: error("task_history_readback_mismatch")).task
        }
        check(readback == result) { "task_projection_readback_mismatch" }
        requireNotNull(readback)
    }

    suspend fun exportTable(scope: String, tableId: String): String {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null)
        check(table.mode == "editable") { "linked_view_export_not_supported" }
        val columns = dao.columns(tableId)
        val rows = dao.rows(tableId, scope)
        return JSONObject().put("format", "memora-table-v1").put("scope", scope).put("tableId", tableId)
            .put("schemaVersion", table.schemaVersion).put("columns", JSONArray(columns.map { JSONObject()
                .put("id", it.columnId).put("name", it.name).put("type", it.valueType).put("required", it.required).put("config", JSONObject(it.configJson)) }))
            .put("rows", JSONArray(rows.map { JSONObject().put("id", it.rowId).put("version", it.version).put("values", JSONObject(it.valuesJson)) })).toString()
    }

    suspend fun previewImport(scope: String, tableId: String, json: String): ImportPreview {
        require(json.length <= 1_000_000)
        val root = JSONObject(json); require(root.getString("format") == "memora-table-v1")
        require(root.getString("scope") == scope && root.getString("tableId") == tableId) { "scope_mismatch" }
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.mode == "editable") { "linked_view_import_not_supported" }
        check(root.getLong("schemaVersion") == table.schemaVersion) { "schema_conflict" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        val rawRows = root.getJSONArray("rows"); require(rawRows.length() <= 1000)
        val ids = hashSetOf<String>()
        val parsed = (0 until rawRows.length()).map { index ->
            val row = rawRows.getJSONObject(index); require(ids.add(row.getString("id")))
            val values = row.getJSONObject("values").let { obj -> obj.keys().asSequence().associateWith { key -> jsonValue(obj.get(key)) } }
            validateRow(scope, columns, values)
            deterministicImportId(tableId, row.getString("id")) to values
        }
        return ImportPreview(tableId, table.schemaVersion, parsed)
    }

    suspend fun importRows(scope: String, preview: ImportPreview): List<TableDataRow> =
        addRows(scope, preview.tableId, preview.schemaVersion, preview.rows)

    suspend fun search(scope: String, query: String, kinds: Set<DomainKind> = DomainKind.entries.toSet(), limit: Int = 20): List<DomainHit> {
        require(limit in 1..100 && query.length <= 500)
        val needle = fold(query.trim()); if (needle.isEmpty()) return emptyList()
        val terms = TOKEN_REGEX.findAll(needle).map { it.value }.filter { it.length > 1 && it !in STOP_WORDS }.distinct().take(20).toList()
        if (terms.isEmpty()) return emptyList()
        val allowedKinds = kinds.map(DomainKind::name).toSet()
        val hits = mutableListOf<Pair<Int, DomainHit>>()
        for (row in dao.activeObjects(scope).filter { it.kind in allowedKinds }) {
            val rev = dao.domainRevisions(row.objectId).lastOrNull() ?: continue
            val p = JSONObject(rev.payloadJson); val title = p.optString("title", row.title); val body = p.optString("body").takeIf { it.isNotEmpty() }
            val text = listOfNotNull(title, body).joinToString(" ")
            val tokenMatches = TOKEN_REGEX.findAll(fold(text)).filter { token ->
                terms.any { term -> searchTokenMatches(term, token.value) }
            }.toList()
            if (tokenMatches.isEmpty()) continue
            val score = tokenMatches.map { it.value }.distinct().size * 100 / terms.size +
                if (fold(title).contains(needle)) 100 else 0
            val originalToken = TOKEN_REGEX.findAll(text).firstOrNull { token ->
                terms.any { term -> searchTokenMatches(term, fold(token.value)) }
            } ?: continue
            val start = originalToken.range.first
            val snippetStart = (start - 300).coerceAtLeast(0)
            val snippetEnd = (snippetStart + 1000).coerceAtMost(text.length)
            val snippet = text.substring(snippetStart, snippetEnd)
            hits += score to DomainHit(row.objectId, DomainKind.valueOf(row.kind), title, row.version,
                rev.sourceRevisionId, "${row.objectId}:${rev.version}", start - snippetStart,
                originalToken.range.last + 1 - snippetStart, snippet)
        }
        return hits.sortedWith(compareByDescending<Pair<Int, DomainHit>> { it.first }.thenBy { it.second.title.lowercase() })
            .take(limit).map { it.second }
    }

    suspend fun context(scope: String, query: String, maxChars: Int = 6000): List<DomainHit> {
        require(maxChars in 100..20_000)
        var used = 0
        return search(scope, query, limit = 100).takeWhile { hit -> (used + hit.evidenceText.length).let { fits -> if (fits <= maxChars) used = fits; fits <= maxChars } }
    }

    suspend fun correctFinanceEventAmount(scope: String, eventId: String, newMinorUnits: Long, commandId: String,
        reason: String, recordedAt: String): FinanceCorrectionResult = db.withTransaction {
        require(scope.isNotBlank() && eventId.isNotBlank() && commandId.isNotBlank())
        require(newMinorUnits > 0 && reason.isNotBlank() && reason.length <= 500)
        java.time.Instant.parse(recordedAt)
        val event = dao.moneyEvent(scope, eventId) ?: error("event_not_found")
        check(event.kind in setOf("receipt", "debt", "expense")) { "event_kind_unsupported" }
        check(!isFinanceEventVoided(scope, eventId)) { "event_already_reversed" }

        val prior = dao.adjustmentForCommand(commandId)
        if (prior != null) {
            check(prior.scopeId == scope && prior.obligationId == eventId && prior.kind == "amount_correction" &&
                prior.reversesEventId == eventId && prior.reason == reason) { "idempotency_conflict" }
            val earlierCorrections = dao.adjustments(scope, eventId).filter {
                it.kind == "amount_correction" && it.reversesEventId == eventId && it.commandId != commandId
            }.fold(0L) { total, item -> Math.addExact(total, item.minorUnits) }
            check(Math.addExact(Math.addExact(event.minorUnits, earlierCorrections), prior.minorUnits) == newMinorUnits) {
                "idempotency_conflict"
            }
            return@withTransaction correctionReadback(scope, event, prior, emptyList())
        }

        val previousAmount = adjustedAmount(scope, event)
        require(newMinorUnits != previousAmount) { "amount_unchanged" }
        val amountDelta = Math.subtractExact(newMinorUnits, previousAmount)
        val allAdjustments = dao.adjustments(scope)
        val allocationAdjustments = mutableListOf<MoneyAdjustmentRow>()
        val changedAllocations = mutableListOf<FinanceReversedAllocation>()

        if (event.kind == "debt") {
            val remaining = outstandingDebtMinor(scope, eventId)
            val alreadySettled = Math.subtractExact(previousAmount, remaining)
            require(newMinorUnits >= alreadySettled) { "correction_below_settled_amount" }
        } else if (event.kind == "receipt") {
            val allocations = allAdjustments.filter { it.kind == "settlement" && it.reversesEventId == eventId }
                .map { it to effectiveSettlementAmount(allAdjustments, it) }.filter { it.second > 0 }
            val totalApplied = allocations.fold(0L) { total, item -> Math.addExact(total, item.second) }
            if (totalApplied > newMinorUnits && allocations.size > 1) error("correction_requires_allocation_review")
            if (allocations.size == 1) {
                val (allocation, currentlyApplied) = allocations.single()
                val desiredApplied = when {
                    totalApplied > newMinorUnits -> newMinorUnits
                    totalApplied == previousAmount -> newMinorUnits
                    else -> currentlyApplied
                }
                val allocationDelta = Math.subtractExact(desiredApplied, currentlyApplied)
                if (allocationDelta != 0L) {
                    val debt = dao.moneyEvent(scope, allocation.obligationId)?.takeIf { it.kind == "debt" }
                        ?: error("obligation_not_found")
                    check(!isFinanceEventVoided(scope, debt.eventId)) { "obligation_voided" }
                    require(debt.currency == event.currency) { "currency_mismatch" }
                    if (allocationDelta > 0) {
                        require(allocationDelta <= outstandingDebtMinor(scope, debt.eventId)) { "settlement_exceeds_balance" }
                    }
                    val index = allocationAdjustments.size
                    val adjustmentId = "$commandId:allocation:$index"
                    allocationAdjustments += MoneyAdjustmentRow(adjustmentId, adjustmentId, allocation.obligationId,
                        "correction", allocationDelta, allocation.currency, allocation.occurredAt, allocation.eventId,
                        "${reason.take(430)} (linked repayment amount corrected)", scope)
                    changedAllocations += FinanceReversedAllocation(debt.eventId, desiredApplied)
                }
            }
        }

        val correction = MoneyAdjustmentRow(commandId, commandId, eventId, "amount_correction", amountDelta,
            event.currency, recordedAt, eventId, reason, scope)
        dao.insertAdjustment(correction)
        for (adjustment in allocationAdjustments) dao.insertAdjustment(adjustment)
        val result = correctionReadback(scope, event, correction, changedAllocations)
        check(result.event.minorUnits == newMinorUnits) { "finance_correction_readback_mismatch" }
        if (event.kind == "receipt") {
            val applied = appliedToDebtMinor(scope, eventId)
            check(applied <= newMinorUnits) { "finance_allocation_readback_mismatch" }
            if (changedAllocations.isNotEmpty()) check(applied == changedAllocations.single().minorUnits) {
                "finance_allocation_readback_mismatch"
            }
        }
        result
    }

    private suspend fun correctionReadback(scope: String, event: MoneyEventRow, correction: MoneyAdjustmentRow,
        changedAllocations: List<FinanceReversedAllocation>): FinanceCorrectionResult {
        val readback = requireNotNull(dao.adjustmentForCommand(correction.commandId))
        check(readback == correction) { "finance_correction_readback_mismatch" }
        val updatedEvent = requireNotNull(dao.scopedMoneyEvent(scope, event.eventId)).let { effectiveMoneyEvent(scope, it) }
        val affectedDebtIds = if (event.kind == "receipt") dao.adjustments(scope)
            .filter { it.kind == "settlement" && it.reversesEventId == event.eventId }.map { it.obligationId }.distinct()
        else if (event.kind == "debt") listOf(event.eventId) else emptyList()
        val remaining = affectedDebtIds.associateWith { outstandingDebtMinor(scope, it) }
        return FinanceCorrectionResult(updatedEvent, changedAllocations, remaining)
    }

    suspend fun recordSettlement(scope: String, eventId: String, commandId: String, obligationId: String, minorUnits: Long,
        currency: String, occurredAt: String?, reason: String, paymentEventId: String? = null): Long = db.withTransaction {
        require(scope.isNotBlank() && minorUnits > 0 && reason.isNotBlank() && reason.length <= 500)
        val existing = dao.adjustmentForCommand(commandId)
        if (existing != null) {
            check(existing.scopeId == scope && existing.eventId == eventId && existing.obligationId == obligationId && existing.kind == "settlement" &&
                existing.minorUnits == minorUnits && existing.currency == currency && existing.occurredAt == occurredAt &&
                existing.reason == reason && existing.reversesEventId == paymentEventId) { "idempotency_conflict" }
            val obligation = dao.moneyEvent(scope, obligationId)?.takeIf { it.kind == "debt" } ?: error("obligation_not_found")
            val adjustments = dao.adjustments(scope, obligationId)
            val settled = adjustments.filter { it.kind in setOf("settlement", "correction", "reversal") }.fold(0L) { total, item -> when (item.kind) {
                "settlement", "correction" -> Math.addExact(total, item.minorUnits)
                "reversal" -> Math.subtractExact(total, item.minorUnits)
                else -> error("invalid_ledger_event")
            } }
            return@withTransaction Math.subtractExact(adjustedAmount(scope, obligation), settled)
        }
        val obligation = dao.moneyEvent(scope, obligationId)?.takeIf { it.kind == "debt" } ?: error("obligation_not_found")
        check(!isFinanceEventVoided(scope, obligationId)) { "obligation_voided" }
        require(currency == obligation.currency && isCurrency(currency)) { "currency_mismatch" }
        val prior = dao.adjustments(scope, obligationId)
        val net = prior.filter { it.kind in setOf("settlement", "correction", "reversal") }.fold(0L) { total, item -> when (item.kind) {
            "settlement" -> Math.addExact(total, item.minorUnits)
            "correction" -> Math.addExact(total, item.minorUnits)
            "reversal" -> Math.subtractExact(total, item.minorUnits)
            else -> error("invalid_ledger_event")
        } }
        val principal = adjustedAmount(scope, obligation)
        val remaining = Math.subtractExact(principal, net)
        require(minorUnits <= remaining) { "settlement_exceeds_balance" }
        if (paymentEventId != null) {
            val payment = dao.moneyEvent(scope, paymentEventId)?.takeIf { it.kind == "receipt" } ?: error("payment_event_not_found")
            check(payment.personId == obligation.personId) { "payment_obligation_mismatch" }
            check(!isFinanceEventVoided(scope, paymentEventId)) { "payment_event_voided" }
            require(payment.currency == currency) { "currency_mismatch" }
            require(minorUnits <= adjustedAmount(scope, payment) - appliedToDebtMinor(scope, paymentEventId)) { "payment_amount_already_applied" }
        }
        val inserted = MoneyAdjustmentRow(eventId, commandId, obligationId, "settlement", minorUnits, currency, occurredAt,
            paymentEventId, reason, scope)
        dao.insertAdjustment(inserted)
        val readback = requireNotNull(dao.adjustmentForCommand(commandId))
        check(readback == inserted) { "settlement_write_readback_mismatch" }
        val readbackRemaining = outstandingDebtMinor(scope, obligationId)
        check(readbackRemaining == remaining - minorUnits) { "settlement_balance_readback_mismatch" }
        readbackRemaining
    }

    suspend fun createCalendarEvent(input: CalendarInput): LocalCalendarEventRow = db.withTransaction {
        val row = validatedCalendarRow(input, 1)
        dao.insertCalendarEvent(row)
        check(dao.calendarEvent(input.scopeId, input.eventId) == row) { "calendar_create_readback_mismatch" }
        row
    }

    suspend fun updateCalendarEvent(scope: String, input: CalendarInput, expectedVersion: Long): LocalCalendarEventRow = db.withTransaction {
        val current = dao.calendarEvent(scope, input.eventId) ?: error("not_found")
        check(current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(current.status == "active") { "calendar_event_cancelled" }
        check(input.scopeId == scope)
        val next = validatedCalendarRow(input, expectedVersion + 1)
        check(dao.updateCalendarEvent(scope, input.eventId, expectedVersion, next.version, next.title, next.startLocal,
            next.endLocal, next.zoneId, next.timePrecision, current.status) == 1) { "version_conflict" }
        val expected = next.copy(status = current.status)
        val readback = requireNotNull(dao.calendarEvent(scope, input.eventId))
        check(readback == expected) { "calendar_update_readback_mismatch" }
        readback
    }

    suspend fun cancelCalendarEvent(scope: String, eventId: String, expectedVersion: Long): LocalCalendarEventRow = db.withTransaction {
        val current = dao.calendarEvent(scope, eventId) ?: error("not_found")
        check(current.status == "active" && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.cancelCalendarEvent(scope, eventId, expectedVersion, expectedVersion + 1) == 1) { "version_conflict" }
        val expected = current.copy(status = "cancelled", version = expectedVersion + 1)
        val readback = requireNotNull(dao.calendarEvent(scope, eventId))
        check(readback == expected) { "calendar_cancel_readback_mismatch" }
        readback
    }

    suspend fun calendarEvent(scope: String, eventId: String): LocalCalendarEventRow? = dao.calendarEvent(scope, eventId)
    suspend fun allCalendarEvents(scope: String): List<LocalCalendarEventRow> = dao.calendarEvents(scope)

    suspend fun calendarBetween(scope: String, fromLocal: String, untilLocal: String): List<LocalCalendarEventRow> {
        require(fromLocal < untilLocal)
        return (dao.calendarBetween(scope, fromLocal, untilLocal) + dao.allDayCalendarBetween(scope, fromLocal, untilLocal))
            .distinctBy { it.eventId }.sortedWith(compareBy<LocalCalendarEventRow> { it.startLocal }.thenBy { it.eventId })
    }

    private fun validatedCalendarRow(input: CalendarInput, version: Long): LocalCalendarEventRow {
        requireTitle(input.title); require(input.precision in setOf("date", "minute"))
        val zone = ZoneId.of(input.zoneId)
        if (input.precision == "date") {
            java.time.LocalDate.parse(input.startLocal)
            require(input.endLocal == null)
        } else {
            val start = LocalDateTime.parse(input.startLocal)
            check(zone.rules.getValidOffsets(start).size == 1) { "ambiguous_or_invalid_local_time" }
            input.endLocal?.let { end ->
                val endTime = LocalDateTime.parse(end); require(endTime.isAfter(start))
                check(zone.rules.getValidOffsets(endTime).size == 1) { "ambiguous_or_invalid_local_time" }
            }
        }
        return LocalCalendarEventRow(input.eventId, input.scopeId, input.title.trim(), input.startLocal, input.endLocal, input.zoneId, input.precision, version, "active")
    }

    private fun validateSchema(schema: List<ColumnSpec>) {
        require(schema.size in 1..50 && schema.map { it.id }.toSet().size == schema.size)
        schema.forEach { col -> require(col.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))); requireTitle(col.name); require(col.config.toString().length <= 1000) }
    }

    private suspend fun validateRow(scope: String, columns: Map<String, ColumnSpec>, values: Map<String, Any?>) {
        require(values.keys.all { it in columns })
        columns.forEach { (id, col) ->
            val value = values[id]
            if (value == null) { require(!col.required) { "required_value_missing" }; return@forEach }
            when (col.type) {
                ColumnType.TEXT -> require(value is String && value.length <= 10_000)
                ColumnType.PERSON_REF, ColumnType.PROJECT_REF -> {
                    require(value is String && value.isNotBlank() && value.length <= 100) { "invalid_reference" }
                    val target = dao.domainObject(scope, value) ?: error("invalid_reference")
                    val expectedKind = if (col.type == ColumnType.PERSON_REF) DomainKind.PERSON else DomainKind.PROJECT
                    require(target.kind == expectedKind.name && target.status == "active" && target.deletedAt == null) {
                        "invalid_reference"
                    }
                }
                ColumnType.INTEGER -> require(value is Int || value is Long)
                ColumnType.DECIMAL -> require(value is String && runCatching { BigDecimal(value); true }.getOrDefault(false))
                ColumnType.BOOLEAN -> require(value is Boolean)
                ColumnType.DATE -> require(value is String && runCatching { java.time.LocalDate.parse(value); true }.getOrDefault(false))
                ColumnType.DATETIME -> require(value is String && runCatching { java.time.OffsetDateTime.parse(value); true }.getOrDefault(false))
                ColumnType.MONEY -> {
                    require(value is Map<*, *> && value.keys == setOf("minorUnits", "currency"))
                    require(value["minorUnits"] is Number && (value["minorUnits"] as Number).toLong() >= 0)
                    require(value["currency"] is String && isCurrency(value["currency"] as String))
                }
            }
        }
    }

    private fun encodeValues(values: Map<String, Any?>): String {
        val objectValue = JSONObject()
        values.toSortedMap().forEach { (key, value) -> objectValue.put(key, when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject(value.entries.associate { it.key.toString() to it.value }.toSortedMap())
            else -> value
        }) }
        return objectValue.toString()
    }

    private fun columnsJson(columns: List<TableColumnRow>) = JSONArray(columns.sortedBy { it.position }.map { JSONObject()
        .put("id", it.columnId).put("position", it.position).put("name", it.name).put("type", it.valueType)
        .put("required", it.required).put("config", JSONObject(it.configJson)) }).toString()

    /** Stable target-table scoped IDs make an identical import retry idempotent. */
    private fun deterministicImportId(tableId: String, sourceRowId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$tableId\u0000$sourceRowId".toByteArray())
        val hex = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-a${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    private fun payload(title: String, body: String?, status: String, deletedAt: String?) = JSONObject()
        .put("title", title).put("body", body ?: JSONObject.NULL).put("status", status)
        .put("deletedAt", deletedAt ?: JSONObject.NULL).toString()
    private fun fold(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(SEARCH_MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
        .replace("ch", "c").replace("sh", "s")
    private fun searchTokenMatches(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        if (minOf(query.length, candidate.length) < 4) return false
        return query.zip(candidate).takeWhile { (left, right) -> left == right }.size >= 4
    }
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    private val SEARCH_MARKS = Regex("\\p{M}+")
    private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "about", "this", "that", "что", "это", "мне", "меня", "мои", "мой", "моя", "у", "и", "в", "на", "для", "ne", "nə", "mene", "mənə", "mənim", "və", "bir", "bu", "da", "də")
    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonValue(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
    private fun requireTitle(value: String) { require(value.isNotBlank() && value == value.trim() && value.codePointCount(0, value.length) <= 200) }
    private fun isCurrency(value: String) = runCatching { Currency.getInstance(value).currencyCode == value }.getOrDefault(false)

    private fun toTaskRow(event: TaskEvent): TaskEventRow = when (event) {
        is TaskEvent.Created -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "created", previousVersion = null, title = event.title, completed = null, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.Renamed -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "renamed", previousVersion = event.previousVersion, title = event.title, completed = null, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.CompletionChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "completed", previousVersion = event.previousVersion, title = null, completed = event.completed, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.DetailsChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "details", previousVersion = event.previousVersion, title = null, completed = null, dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind, personLabel = event.personLabel, projectLabel = event.projectLabel)
        is TaskEvent.Updated -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "updated", previousVersion = event.previousVersion, title = event.title, completed = null, dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind, personLabel = event.personLabel, projectLabel = event.projectLabel)
    }

    private fun fromTaskRow(row: TaskEventRow): TaskEvent = when (row.type) {
        "created" -> TaskEvent.Created(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.title))
        "renamed" -> TaskEvent.Renamed(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.title))
        "completed" -> TaskEvent.CompletionChanged(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.completed))
        "details" -> TaskEvent.DetailsChanged(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), row.dueLocal, row.dueZone, requireNotNull(row.remind), row.personLabel, row.projectLabel)
        "updated" -> TaskEvent.Updated(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.title), row.dueLocal, row.dueZone, requireNotNull(row.remind), row.personLabel, row.projectLabel)
        else -> error("Unknown task event type")
    }
}

private fun VoiceReceipt.toLedger(scope: String) = MoneyEventRow(eventId = id, commandId = commandId, personId = personId,
    kind = "receipt", minorUnits = minorUnits, currency = currency, source = source, recordedAt = recordedAt.toString(),
    occurredAt = occurredAt?.toString(), comment = comment, scopeId = scope)

private fun VoiceDebt.toLedger(scope: String) = MoneyEventRow(eventId = id, commandId = commandId, personId = personId,
    kind = "debt", minorUnits = minorUnits, currency = currency, source = source, recordedAt = recordedAt.toString(),
    occurredAt = occurredAt?.toString(), comment = comment, scopeId = scope)

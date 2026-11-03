package ai.memora.app.data.protectedstore

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class FinanceCurrencyPeriod(
    val currency: String,
    val receivedMinorUnits: Long,
    val spentMinorUnits: Long,
    val debtOriginatedMinorUnits: Long,
    val recordedMovementMinorUnits: Long,
    val receiptCount: Int,
    val expenseCount: Int,
    val debtCount: Int,
)

internal data class FinanceCounterpartyActivity(
    val personId: String?,
    val displayName: String?,
    val currency: String,
    val receivedMinorUnits: Long,
    val spentMinorUnits: Long,
    val debtOriginatedMinorUnits: Long,
    val eventCount: Int,
)

internal data class FinancePeriodTotals(
    val month: YearMonth,
    val currencies: List<FinanceCurrencyPeriod>,
    val includedEventCount: Int,
    val voidedEventCount: Int,
    val recordedDateFallbackCount: Int,
    val undatedEventCount: Int,
    val topCounterpartyActivity: List<FinanceCounterpartyActivity>,
    val counterpartyActivityTruncated: Boolean,
)

internal data class FinanceOutstandingDebt(
    val currency: String,
    val amountMinorUnits: Long,
    val obligationCount: Int,
)

internal data class FinanceMovementComparison(
    val currency: String,
    val receivedDeltaMinorUnits: Long,
    val spentDeltaMinorUnits: Long,
    val recordedMovementDeltaMinorUnits: Long,
)

internal data class FinanceAnalyticsSnapshot(
    val current: FinancePeriodTotals,
    val previous: FinancePeriodTotals,
    val outstandingDebt: List<FinanceOutstandingDebt>,
    val zoneId: String,
) {
    val comparisonWithPreviousPeriod: List<FinanceMovementComparison>
        get() {
            val previousByCurrency = previous.currencies.associateBy { it.currency }
            return current.currencies.mapNotNull { now ->
                val then = previousByCurrency[now.currency] ?: return@mapNotNull null
                FinanceMovementComparison(
                    now.currency,
                    Math.subtractExact(now.receivedMinorUnits, then.receivedMinorUnits),
                    Math.subtractExact(now.spentMinorUnits, then.spentMinorUnits),
                    Math.subtractExact(now.recordedMovementMinorUnits, then.recordedMovementMinorUnits),
                )
            }
        }

    fun toToolData(): Map<String, Any?> = mapOf(
        "source" to "protected_finance_ledger",
        "period" to periodData(current),
        "previous_period" to periodData(previous),
        "comparison_with_previous_period" to comparisonWithPreviousPeriod.map { delta -> mapOf(
            "currency" to delta.currency,
            "received_delta_minor_units" to delta.receivedDeltaMinorUnits,
            "spent_delta_minor_units" to delta.spentDeltaMinorUnits,
            "recorded_movement_delta_minor_units" to delta.recordedMovementDeltaMinorUnits,
        ) },
        "outstanding_debt_as_of_query" to outstandingDebt.map { debt -> mapOf(
            "currency" to debt.currency,
            "minor_units" to debt.amountMinorUnits,
            "open_obligation_count" to debt.obligationCount,
        ) },
        "counterparty_activity" to current.topCounterpartyActivity.map { activity -> mapOf(
            "person_id" to activity.personId,
            "person_name" to activity.displayName,
            "currency" to activity.currency,
            "received_minor_units" to activity.receivedMinorUnits,
            "spent_minor_units" to activity.spentMinorUnits,
            "debt_originated_minor_units_not_income" to activity.debtOriginatedMinorUnits,
            "event_count" to activity.eventCount,
        ) },
        "project_analysis" to mapOf(
            "available" to false,
            "reason" to "financial_events_have_no_verified_project_links",
        ),
        "coverage" to mapOf(
            "opening_balance_known" to false,
            "all_accounts_covered" to false,
            "expense_categories_available" to false,
            "profit_calculable" to false,
            "currency_conversion_applied" to false,
            "counterparty_activity_truncated" to current.counterpartyActivityTruncated,
            "undated_events_excluded_all_time" to current.undatedEventCount,
            "period_dates_fallback_to_recorded_at" to current.recordedDateFallbackCount,
            "status" to "ledger_only_completeness_unverified",
        ),
        "interpretation" to mapOf(
            "received_and_spent_are_recorded_cash_events" to true,
            "debt_originations_are_not_cash_income" to true,
            "recorded_movement_is_profit" to false,
        ),
    )

    private fun periodData(period: FinancePeriodTotals): Map<String, Any?> = mapOf(
        "month" to period.month.toString(),
        "start_date_inclusive" to period.month.atDay(1).toString(),
        "end_date_inclusive" to period.month.atEndOfMonth().toString(),
        "timezone" to zoneId,
        "included_event_count" to period.includedEventCount,
        "voided_event_count" to period.voidedEventCount,
        "recorded_date_fallback_count" to period.recordedDateFallbackCount,
        "undated_event_count_in_ledger" to period.undatedEventCount,
        "currencies" to period.currencies.map { flow -> mapOf(
            "currency" to flow.currency,
            "received_minor_units" to flow.receivedMinorUnits,
            "spent_minor_units" to flow.spentMinorUnits,
            "debt_originated_minor_units_not_income" to flow.debtOriginatedMinorUnits,
            "recorded_cash_movement_minor_units_not_profit" to flow.recordedMovementMinorUnits,
            "receipt_count" to flow.receiptCount,
            "expense_count" to flow.expenseCount,
            "debt_count" to flow.debtCount,
        ) },
        "counterparty_activity" to period.topCounterpartyActivity.map { activity -> mapOf(
            "person_id" to activity.personId,
            "person_name" to activity.displayName,
            "currency" to activity.currency,
            "received_minor_units" to activity.receivedMinorUnits,
            "spent_minor_units" to activity.spentMinorUnits,
            "debt_originated_minor_units_not_income" to activity.debtOriginatedMinorUnits,
            "event_count" to activity.eventCount,
        ) },
    )
}

/** Exact read-only aggregates. It never claims accounting profit or complete ledger coverage. */
internal object FinanceAnalytics {
    val bakuZone: ZoneId = ZoneId.of("Asia/Baku")

    fun requestedMonth(value: String?, now: Instant = Instant.now()): YearMonth? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return YearMonth.from(now.atZone(bakuZone))
        if (!raw.matches(Regex("\\d{4}-(0[1-9]|1[0-2])"))) return null
        return runCatching { YearMonth.parse(raw, DateTimeFormatter.ofPattern("uuuu-MM")) }
            .getOrNull()?.takeIf { it.year in MIN_YEAR..MAX_YEAR }
    }

    /** Reuses the ledger's append-only correction and void semantics from one bounded database snapshot. */
    fun fromLedger(
        month: YearMonth,
        events: List<MoneyEventRow>,
        adjustments: List<MoneyAdjustmentRow>,
        personNameById: Map<String, String>,
        zone: ZoneId = bakuZone,
    ): FinanceAnalyticsSnapshot {
        require(events.size <= MAX_EVENTS && adjustments.size <= MAX_ADJUSTMENTS)
        require(events.all { it.kind in FINANCE_EVENT_KINDS && it.minorUnits > 0 }) { "invalid_ledger_event" }
        val byObligation = adjustments.groupBy { it.obligationId }
        val amountCorrections = adjustments.asSequence()
            .filter { it.kind == "amount_correction" && !it.reversesEventId.isNullOrBlank() }
            .groupBy { it.reversesEventId!! }

        fun isVoided(eventId: String): Boolean = byObligation[eventId].orEmpty()
            .asSequence().filter { it.kind == "event_void" || it.kind == "event_restore" }
            .sortedWith(compareBy<MoneyAdjustmentRow> { it.occurredAt.orEmpty() }.thenBy { it.eventId })
            .fold(false) { _, adjustment -> adjustment.kind == "event_void" }

        fun correctedAmount(event: MoneyEventRow): Long = Math.addExact(
            event.minorUnits,
            amountCorrections[event.eventId].orEmpty().asSequence()
                .filter { it.obligationId == event.eventId }
                .fold(0L) { total, adjustment -> Math.addExact(total, adjustment.minorUnits) },
        )

        val voided = events.asSequence().filter { isVoided(it.eventId) }.map { it.eventId }.toSet()
        val effectiveEvents = events.map { it.copy(minorUnits = correctedAmount(it)) }
        val remainingDebt = events.asSequence().filter { it.kind == "debt" }.associate { debt ->
            if (debt.eventId in voided) debt.eventId to 0L
            else {
                val related = byObligation[debt.eventId].orEmpty()
                require(related.all { it.kind in SUPPORTED_DEBT_ADJUSTMENTS }) { "invalid_ledger_event" }
                val principal = correctedAmount(debt)
                val settled = related.asSequence().filter { it.kind in SETTLEMENT_ADJUSTMENTS }
                    .fold(0L) { total, adjustment -> when (adjustment.kind) {
                        "settlement", "correction" -> Math.addExact(total, adjustment.minorUnits)
                        "reversal" -> Math.subtractExact(total, adjustment.minorUnits)
                        else -> error("invalid_ledger_event")
                    } }
                require(settled in 0..principal) { "invalid_ledger_balance" }
                debt.eventId to Math.subtractExact(principal, settled)
            }
        }
        return calculate(month, effectiveEvents, voided, remainingDebt, zone, personNameById)
    }

    fun calculate(
        month: YearMonth,
        events: List<MoneyEventRow>,
        voidedEventIds: Set<String>,
        remainingDebtMinorByEventId: Map<String, Long>,
        zone: ZoneId = bakuZone,
        personNameById: Map<String, String> = emptyMap(),
    ): FinanceAnalyticsSnapshot {
        require(events.size <= MAX_EVENTS)
        require(events.all { it.kind in FINANCE_EVENT_KINDS && it.minorUnits > 0 }) { "invalid_ledger_event" }
        require(voidedEventIds.all(String::isNotBlank))
        require(remainingDebtMinorByEventId.values.all { it >= 0 })
        val current = period(month, events, voidedEventIds, zone, personNameById)
        val previous = period(month.minusMonths(1), events, voidedEventIds, zone, personNameById)
        val openDebt = events.asSequence().filter { it.kind == "debt" && it.eventId !in voidedEventIds }
            .mapNotNull { event ->
                val remaining = remainingDebtMinorByEventId[event.eventId] ?: return@mapNotNull null
                if (remaining <= 0) return@mapNotNull null
                currency(event.currency) to remaining
            }
            .groupBy({ it.first }, { it.second })
            .map { (currency, amounts) ->
                FinanceOutstandingDebt(currency, amounts.fold(0L) { total, amount -> Math.addExact(total, amount) }, amounts.size)
            }
            .sortedBy { it.currency }
        return FinanceAnalyticsSnapshot(current, previous, openDebt, zone.id)
    }

    private fun period(
        month: YearMonth,
        events: List<MoneyEventRow>,
        voided: Set<String>,
        zone: ZoneId,
        personNameById: Map<String, String>,
    ): FinancePeriodTotals {
        val range = month.atDay(1)..month.atEndOfMonth()
        val byCurrency = linkedMapOf<String, MutableTotals>()
        val byCounterparty = linkedMapOf<Pair<String?, String>, MutableCounterpartyTotals>()
        var included = 0
        var voidedCount = 0
        var fallbackCount = 0
        var undatedCount = 0
        events.forEach { event ->
            val (date, usedRecordTime) = dateForPeriod(event, zone)
            if (date == null) {
                undatedCount++
                return@forEach
            }
            if (date !in range) return@forEach
            if (usedRecordTime) fallbackCount++
            if (event.eventId in voided) {
                voidedCount++
                return@forEach
            }
            val moneyCurrency = currency(event.currency)
            val totals = byCurrency.getOrPut(moneyCurrency) { MutableTotals() }
            val key = event.personId.takeIf(String::isNotBlank) to moneyCurrency
            val counterparty = byCounterparty.getOrPut(key) { MutableCounterpartyTotals() }
            included++
            when (event.kind) {
                "receipt" -> {
                    totals.received = Math.addExact(totals.received, event.minorUnits)
                    totals.receiptCount++
                    counterparty.received = Math.addExact(counterparty.received, event.minorUnits)
                }
                "expense" -> {
                    totals.spent = Math.addExact(totals.spent, event.minorUnits)
                    totals.expenseCount++
                    counterparty.spent = Math.addExact(counterparty.spent, event.minorUnits)
                }
                "debt" -> {
                    totals.debt = Math.addExact(totals.debt, event.minorUnits)
                    totals.debtCount++
                    counterparty.debt = Math.addExact(counterparty.debt, event.minorUnits)
                }
            }
            counterparty.eventCount++
        }
        val currencies = byCurrency.map { (moneyCurrency, total) -> FinanceCurrencyPeriod(
            currency = moneyCurrency,
            receivedMinorUnits = total.received,
            spentMinorUnits = total.spent,
            debtOriginatedMinorUnits = total.debt,
            recordedMovementMinorUnits = Math.subtractExact(total.received, total.spent),
            receiptCount = total.receiptCount,
            expenseCount = total.expenseCount,
            debtCount = total.debtCount,
        ) }.sortedBy { it.currency }
        val counterparties = byCounterparty.map { (key, total) -> FinanceCounterpartyActivity(
            key.first, key.first?.let(personNameById::get)?.takeIf(String::isNotBlank), key.second,
            total.received, total.spent, total.debt, total.eventCount,
        ) }.sortedWith(compareByDescending<FinanceCounterpartyActivity> { it.sortAmount() }
            .thenBy { it.currency }.thenBy { it.displayName.orEmpty() }.thenBy { it.personId.orEmpty() })
        return FinancePeriodTotals(month, currencies, included, voidedCount, fallbackCount, undatedCount,
            counterparties.take(MAX_COUNTERPARTIES), counterparties.size > MAX_COUNTERPARTIES)
    }

    private fun FinanceCounterpartyActivity.sortAmount(): BigInteger =
        BigInteger.valueOf(receivedMinorUnits).add(BigInteger.valueOf(spentMinorUnits)).add(BigInteger.valueOf(debtOriginatedMinorUnits))

    /** Event time is preferred; malformed or missing values fall back to record time and are counted. */
    private fun dateForPeriod(event: MoneyEventRow, zone: ZoneId): Pair<LocalDate?, Boolean> {
        parseDate(event.occurredAt, zone)?.let { return it to false }
        parseDate(event.recordedAt, zone)?.let { return it to true }
        return null to false
    }

    private fun parseDate(value: String?, zone: ZoneId): LocalDate? = try {
        value?.let { Instant.parse(it).atZone(zone).toLocalDate() }
    } catch (_: Exception) { null }

    private fun currency(value: String): String = value.uppercase()
        .takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "UNKNOWN"

    private class MutableTotals {
        var received = 0L
        var spent = 0L
        var debt = 0L
        var receiptCount = 0
        var expenseCount = 0
        var debtCount = 0
    }

    private class MutableCounterpartyTotals {
        var received = 0L
        var spent = 0L
        var debt = 0L
        var eventCount = 0
    }

    private const val MAX_EVENTS = 100_000
    private const val MAX_ADJUSTMENTS = 200_000
    private const val MAX_COUNTERPARTIES = 20
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 9999
    private val FINANCE_EVENT_KINDS = setOf("receipt", "expense", "debt")
    private val SUPPORTED_DEBT_ADJUSTMENTS = setOf(
        "settlement", "correction", "reversal", "amount_correction", "event_restore", "event_void", "annotation",
    )
    private val SETTLEMENT_ADJUSTMENTS = setOf("settlement", "correction", "reversal")
}

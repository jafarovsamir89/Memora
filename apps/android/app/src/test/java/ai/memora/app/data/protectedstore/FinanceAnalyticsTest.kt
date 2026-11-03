package ai.memora.app.data.protectedstore

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceAnalyticsTest {
    @Test fun aggregatesExactCashMovementDebtAndSameCurrencyComparisonWithoutCallingItProfit() {
        val currentReceipt = event("receipt-now", "person-1", "receipt", 10_025, "2026-11-03T08:00:00Z")
        val expense = event("expense-now", "person-2", "expense", 15_000, "2026-11-02T08:00:00Z")
        val debt = event("debt-now", "person-1", "debt", 35_000, "2026-11-01T08:00:00Z")
        val previousReceipt = event("receipt-previous", "person-1", "receipt", 8_000, "2026-10-03T08:00:00Z")
        val voidedReceipt = event("receipt-void", "person-1", "receipt", 99_000, "2026-11-02T08:00:00Z")
        val settlement = adjustment("settlement-now", "debt-now", "settlement", 10_000, "receipt-now")
        val void = adjustment("void-now", "receipt-void", "event_void", 99_000, "receipt-void")

        val report = FinanceAnalytics.fromLedger(
            YearMonth.of(2026, 11),
            listOf(currentReceipt, expense, debt, previousReceipt, voidedReceipt),
            listOf(settlement, void),
            mapOf("person-1" to "Synthetic Counterparty", "person-2" to "Synthetic Vendor"),
        )

        val azn = report.current.currencies.single()
        assertEquals("AZN", azn.currency)
        assertEquals(10_025L, azn.receivedMinorUnits)
        assertEquals(15_000L, azn.spentMinorUnits)
        assertEquals(35_000L, azn.debtOriginatedMinorUnits)
        assertEquals(-4_975L, azn.recordedMovementMinorUnits)
        assertEquals(1, azn.receiptCount)
        assertEquals(1, azn.expenseCount)
        assertEquals(1, azn.debtCount)
        assertEquals(3, report.current.includedEventCount)
        assertEquals(1, report.current.voidedEventCount)
        assertEquals(25_000L, report.outstandingDebt.single().amountMinorUnits)
        assertEquals(2_025L, (report.toToolData()["comparison_with_previous_period"] as List<*>).single()
            .let { it as Map<*, *> }["received_delta_minor_units"])
        assertEquals("Synthetic Counterparty", report.current.topCounterpartyActivity.first().displayName)
        assertEquals(35_000L, report.current.topCounterpartyActivity.first().debtOriginatedMinorUnits)

        val data = report.toToolData()
        val interpretation = data["interpretation"] as Map<*, *>
        val coverage = data["coverage"] as Map<*, *>
        assertEquals(false, interpretation["recorded_movement_is_profit"])
        assertEquals(true, interpretation["debt_originations_are_not_cash_income"])
        assertEquals(false, coverage["profit_calculable"])
        assertEquals(false, coverage["all_accounts_covered"])
        assertEquals(false, (data["project_analysis"] as Map<*, *>)["available"])
    }

    @Test fun fallsBackToRecordTimeAndKeepsVoidAndUndatedCoverageExplicit() {
        val fallback = event("fallback", "person-1", "receipt", 1_250, "not-a-date", recordedAt = "2026-11-03T08:00:00Z")
        val undated = event("undated", "person-1", "expense", 999, "bad", recordedAt = "also-bad")
        val voided = event("voided", "person-1", "receipt", 20_000, "2026-11-04T08:00:00Z")
        val report = FinanceAnalytics.fromLedger(
            YearMonth.of(2026, 11), listOf(fallback, undated, voided),
            listOf(adjustment("void-adjustment", "voided", "event_void", 20_000, "voided")),
            mapOf("person-1" to "Synthetic Person"),
        )

        assertEquals(1_250L, report.current.currencies.single().receivedMinorUnits)
        assertEquals(1, report.current.recordedDateFallbackCount)
        assertEquals(1, report.current.undatedEventCount)
        assertEquals(1, report.current.voidedEventCount)
        assertFalse(report.toToolData()["coverage"].let { it as Map<*, *> }["profit_calculable"] as Boolean)
    }

    @Test fun amountCorrectionFlowsIntoTheSameMonthlyAggregateWithoutAnotherEvent() {
        val receipt = event("corrected-receipt", "person-1", "receipt", 10_000, "2026-11-03T08:00:00Z")
        val correction = MoneyAdjustmentRow("amount-correction", "command-correction", receipt.eventId,
            "amount_correction", -2_000, "AZN", "2026-11-04T08:00:00Z", receipt.eventId,
            "synthetic correction", "synthetic-scope")

        val report = FinanceAnalytics.fromLedger(YearMonth.of(2026, 11), listOf(receipt), listOf(correction),
            mapOf("person-1" to "Synthetic Person"))

        assertEquals(1, report.current.includedEventCount)
        assertEquals(8_000L, report.current.currencies.single().receivedMinorUnits)
        assertEquals(8_000L, report.current.topCounterpartyActivity.single().receivedMinorUnits)
    }

    @Test fun sameDisplayNameDoesNotMergeSeparatePersonLedgerIdentities() {
        val first = event("same-name-first", "person-id-a", "receipt", 8_000, "2026-11-03T08:00:00Z")
        val second = event("same-name-second", "person-id-b", "receipt", 15_000, "2026-11-04T08:00:00Z")
        val report = FinanceAnalytics.fromLedger(YearMonth.of(2026, 11), listOf(first, second), emptyList(),
            mapOf("person-id-a" to "Synthetic Samir", "person-id-b" to "Synthetic Samir"))

        val activity = report.current.topCounterpartyActivity
        assertEquals(2, activity.size)
        assertEquals(setOf("person-id-a", "person-id-b"), activity.mapNotNull { it.personId }.toSet())
        assertEquals(setOf(8_000L, 15_000L), activity.map { it.receivedMinorUnits }.toSet())
        assertEquals(2, (report.toToolData()["counterparty_activity"] as List<*>).size)
    }

    @Test fun monthInputIsStrictAndUsesBakuForTheDefault() {
        assertEquals(YearMonth.of(2026, 11), FinanceAnalytics.requestedMonth(null,
            java.time.Instant.parse("2026-10-31T20:30:00Z")))
        assertEquals(YearMonth.of(2026, 2), FinanceAnalytics.requestedMonth("2026-02"))
        assertNull(FinanceAnalytics.requestedMonth("2026-2"))
        assertNull(FinanceAnalytics.requestedMonth("2026-13"))
        assertNull(FinanceAnalytics.requestedMonth("0001-01"))
    }

    private fun event(
        id: String,
        personId: String,
        kind: String,
        minorUnits: Long,
        occurredAt: String,
        recordedAt: String = "2026-11-03T08:00:00Z",
    ) = MoneyEventRow(id, "command-$id", personId, kind, minorUnits, "AZN", "synthetic", recordedAt,
        occurredAt, null, "synthetic-scope")

    private fun adjustment(
        id: String,
        obligationId: String,
        kind: String,
        minorUnits: Long,
        reversesEventId: String,
    ) = MoneyAdjustmentRow(id, "command-$id", obligationId, kind, minorUnits, "AZN",
        "2026-11-03T08:00:00Z", reversesEventId, "synthetic", "synthetic-scope")
}

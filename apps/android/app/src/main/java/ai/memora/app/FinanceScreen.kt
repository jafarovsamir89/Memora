package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.MoneyAdjustmentRow
import ai.memora.app.data.protectedstore.FinanceAnalytics
import ai.memora.app.data.protectedstore.FinanceAnalyticsSnapshot
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Finance view over the same SQLCipher ledger used by Gemini tools. */
@Composable
fun FinanceScreen() {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    var events by remember { mutableStateOf<List<MoneyEventRow>?>(null) }
    var settlements by remember { mutableStateOf<List<Pair<MoneyAdjustmentRow, String>>>(emptyList()) }
    var voidedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var people by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var remainingDebtByEventId by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var appliedToDebtByReceiptId by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var unavailable by remember { mutableStateOf(false) }

    LaunchedEffect(scopeId) {
        try {
            check(processor.migrateLegacyFinance())
            val loadedEvents = repository.allFinanceEvents(scopeId)
            events = loadedEvents.asReversed()
            voidedEventIds = loadedEvents.filter { repository.isFinanceEventVoided(scopeId, it.eventId) }.map { it.eventId }.toSet()
            remainingDebtByEventId = loadedEvents.filter { it.kind == "debt" }
                .associate { it.eventId to repository.outstandingDebtMinor(scopeId, it.eventId) }
            appliedToDebtByReceiptId = loadedEvents.filter { it.kind == "receipt" }
                .associate { it.eventId to repository.appliedToDebtMinor(scopeId, it.eventId) }
            val loadedPeople = repository.financePeople(scopeId).associate { it.personId to it.displayName }
            people = loadedPeople
            settlements = repository.financeAdjustments(scopeId).filter { it.kind == "settlement" && it.reversesEventId == null }.mapNotNull { adjustment ->
                repository.moneyEvent(scopeId, adjustment.obligationId)?.let { event -> adjustment to (loadedPeople[event.personId] ?: "") }
            }.asReversed()
            unavailable = false
        } catch (_: Exception) { unavailable = true }
    }

    ScreenColumn(Modifier.testTag("finance_screen")) {
        ScreenHeading(s(R.string.finance), s(R.string.finance_hint))
        if (unavailable) {
            MemoraCard { androidx.compose.material3.Text(s(R.string.capture_storage_error)) }
        } else if (events == null || events!!.isEmpty()) {
            MemoraCard { androidx.compose.material3.Text(s(R.string.working_no_data)) }
        } else events!!.forEach { event ->
            MemoraCard {
                people[event.personId]?.let { androidx.compose.material3.Text(it, style = androidx.compose.material3.MaterialTheme.typography.titleMedium) }
                val label = when (event.kind) {
                    "debt" -> R.string.voice_debt_label
                    "expense" -> R.string.finance_expense_label
                    else -> R.string.finance_receipt_label
                }
                androidx.compose.material3.Text(s(label))
                if (event.eventId in voidedEventIds) androidx.compose.material3.Text(s(R.string.finance_reversed_label), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                val amount = "${event.minorUnits / 100},${(event.minorUnits % 100).toString().padStart(2, '0')} ${event.currency}"
                androidx.compose.material3.Text(amount)
                if (event.kind == "debt") {
                    val remaining = remainingDebtByEventId[event.eventId]
                    if (remaining != null) androidx.compose.material3.Text("${s(R.string.finance_debt_remaining)}: ${remaining / 100},${(remaining % 100).toString().padStart(2, '0')} ${event.currency}")
                }
                if (event.kind == "receipt") {
                    val applied = appliedToDebtByReceiptId[event.eventId] ?: 0L
                    if (applied > 0L) androidx.compose.material3.Text("${s(R.string.finance_receipt_applied)}: ${applied / 100},${(applied % 100).toString().padStart(2, '0')} ${event.currency}")
                }
                event.occurredAt?.let { raw ->
                    val date = runCatching { Instant.parse(raw).atZone(ZoneId.of("Asia/Baku")).toLocalDate().toString() }.getOrNull()
                    if (date != null) androidx.compose.material3.Text(date)
                }
                event.comment?.let { androidx.compose.material3.Text(it) }
                androidx.compose.material3.Text(event.source)
            }
        }
        settlements.forEach { (settlement, personName) ->
            MemoraCard {
                if (personName.isNotEmpty()) androidx.compose.material3.Text(personName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Text(s(R.string.finance_settlement_label))
                androidx.compose.material3.Text("${settlement.minorUnits / 100},${(settlement.minorUnits % 100).toString().padStart(2, '0')} ${settlement.currency}")
                settlement.occurredAt?.let { raw ->
                    val date = runCatching { Instant.parse(raw).atZone(ZoneId.of("Asia/Baku")).toLocalDate().toString() }.getOrNull()
                    if (date != null) androidx.compose.material3.Text(date)
                }
                androidx.compose.material3.Text(settlement.reason)
            }
        }
    }
}

/** Read-only summary over the exact same protected ledger as Gemini's finance tool. */
@Composable
internal fun FinanceReportsSection(language: String) {
    val context = LocalContext.current
    val database = rememberUiAgentDatabase()
    val processor = remember(context, database) { AgentVoiceTurnProcessor(context, database) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(database) { ProtectedDomainRepository(database) }
    var selectedMonthText by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(YearMonth.now(FinanceAnalytics.bakuZone).toString())
    }
    var snapshot by remember { mutableStateOf<FinanceAnalyticsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    val month = remember(selectedMonthText) {
        FinanceAnalytics.requestedMonth(selectedMonthText) ?: YearMonth.now(FinanceAnalytics.bakuZone)
    }
    val resources = ai.memora.app.ui.LocalStrings.current

    LaunchedEffect(scopeId, selectedMonthText, retryToken) {
        loading = true
        failed = false
        try {
            check(processor.migrateLegacyFinance())
            snapshot = repository.financeAnalytics(scopeId, month)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = null
            failed = true
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("finance_reports_section")) {
        MemoraCard(Modifier.testTag("finance_report_period_card")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                androidx.compose.material3.TextButton(
                    onClick = { selectedMonthText = month.minusMonths(1).toString() },
                    enabled = !loading && month.year > 1900,
                    modifier = Modifier.testTag("finance_report_previous").semantics {
                        contentDescription = resources.getString(ai.memora.app.R.string.report_previous_month)
                    },
                ) { androidx.compose.material3.Text("‹", fontSize = 26.sp) }
                androidx.compose.material3.Text(month.toString(), style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("finance_report_month"))
                val currentMonth = YearMonth.now(FinanceAnalytics.bakuZone)
                androidx.compose.material3.TextButton(
                    onClick = { selectedMonthText = month.plusMonths(1).toString() },
                    enabled = !loading && month < currentMonth,
                    modifier = Modifier.testTag("finance_report_next").semantics {
                        contentDescription = resources.getString(ai.memora.app.R.string.report_next_month)
                    },
                ) { androidx.compose.material3.Text("›", fontSize = 26.sp) }
            }
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.report_source_ledger),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.muted,
                modifier = Modifier.testTag("finance_report_source"))
        }

        when {
            loading -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().testTag("finance_report_loading"))
            failed -> MemoraCard {
                androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_error),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                androidx.compose.material3.TextButton(onClick = { retryToken++ }, modifier = Modifier.testTag("finance_report_retry")) {
                    androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.capture_retry))
                }
            }
            snapshot != null -> FinanceReportContent(snapshot!!, language)
        }
    }
}

@Composable
private fun FinanceReportContent(snapshot: FinanceAnalyticsSnapshot, language: String) {
    val resources = ai.memora.app.ui.LocalStrings.current
    val currentMonth = snapshot.current
    if (currentMonth.currencies.isEmpty()) {
        MemoraCard(Modifier.testTag("finance_report_empty")) {
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_no_events),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    } else currentMonth.currencies.forEach { totals ->
        val previous = snapshot.previous.currencies.firstOrNull { it.currency == totals.currency }
        MemoraCard(Modifier.testTag("finance_report_currency_${totals.currency}")) {
            androidx.compose.material3.Text(totals.currency, style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.Text(reportLine(resources.getString(ai.memora.app.R.string.report_received), totals.receivedMinorUnits, totals.currency, language),
                modifier = Modifier.testTag("finance_report_received_${totals.currency}"))
            androidx.compose.material3.Text(reportLine(resources.getString(ai.memora.app.R.string.report_spent), totals.spentMinorUnits, totals.currency, language),
                modifier = Modifier.testTag("finance_report_spent_${totals.currency}"))
            androidx.compose.material3.Text(reportLine(resources.getString(ai.memora.app.R.string.report_recorded_movement), totals.recordedMovementMinorUnits, totals.currency, language),
                modifier = Modifier.testTag("finance_report_movement_${totals.currency}"))
            androidx.compose.material3.Text(reportLine(resources.getString(ai.memora.app.R.string.report_debt_originated), totals.debtOriginatedMinorUnits, totals.currency, language),
                modifier = Modifier.testTag("finance_report_debt_originated_${totals.currency}"))
            if (previous != null) {
                val comparison = reportLine(resources.getString(ai.memora.app.R.string.report_previous_received), previous.receivedMinorUnits, totals.currency, language) +
                    " · " + reportLine(resources.getString(ai.memora.app.R.string.report_previous_spent), previous.spentMinorUnits, totals.currency, language)
                androidx.compose.material3.Text(comparison, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.muted, modifier = Modifier.testTag("finance_report_previous_totals_${totals.currency}"))
                snapshot.comparisonWithPreviousPeriod.firstOrNull { it.currency == totals.currency }?.let { delta ->
                    val change = reportLine(resources.getString(ai.memora.app.R.string.report_received_change), delta.receivedDeltaMinorUnits, totals.currency, language) +
                        " · " + reportLine(resources.getString(ai.memora.app.R.string.report_spent_change), delta.spentDeltaMinorUnits, totals.currency, language)
                    androidx.compose.material3.Text(change, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.muted, modifier = Modifier.testTag("finance_report_change_${totals.currency}"))
                }
            } else {
                androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_no_previous_events),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_not_profit),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted,
                modifier = Modifier.testTag("finance_report_not_profit"))
        }
    }

    snapshot.outstandingDebt.forEach { debt ->
        MemoraCard(Modifier.testTag("finance_report_open_debt_${debt.currency}")) {
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_open_debts),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Text(reportLine(resources.getString(ai.memora.app.R.string.finance_report_recorded_obligations),
                debt.amountMinorUnits, debt.currency, language))
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_open_count, debt.obligationCount),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }

    val topPeople = currentMonth.topCounterpartyActivity.take(5)
    val duplicatePersonNames = topPeople.mapNotNull { it.displayName }.groupingBy { it }.eachCount()
    if (topPeople.isNotEmpty()) {
        MemoraCard(Modifier.testTag("finance_report_counterparties")) {
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_counterparties),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            topPeople.forEach { activity ->
                val baseName = activity.displayName ?: resources.getString(ai.memora.app.R.string.finance_report_unlinked_person)
                val name = if (activity.displayName != null && duplicatePersonNames[activity.displayName]!! > 1 && activity.personId != null)
                    resources.getString(ai.memora.app.R.string.finance_report_duplicate_person, baseName, activity.personId.takeLast(4))
                else baseName
                androidx.compose.material3.Text(name, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Text(
                    reportLine(resources.getString(ai.memora.app.R.string.report_received), activity.receivedMinorUnits, activity.currency, language) +
                        " · " + reportLine(resources.getString(ai.memora.app.R.string.report_spent), activity.spentMinorUnits, activity.currency, language) +
                        " · " + reportLine(resources.getString(ai.memora.app.R.string.report_debt_originated), activity.debtOriginatedMinorUnits, activity.currency, language),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
    }

    MemoraCard(tint = colors.softAccent, modifier = Modifier.testTag("finance_report_coverage")) {
        androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_limits_title),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        listOf(
            ai.memora.app.R.string.finance_report_no_opening_balance,
            ai.memora.app.R.string.finance_report_account_coverage_unknown,
            ai.memora.app.R.string.finance_report_categories_unavailable,
            ai.memora.app.R.string.finance_report_projects_unlinked,
        ).forEach { id -> androidx.compose.material3.Text("• ${resources.getString(id)}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted) }
        if (snapshot.current.recordedDateFallbackCount > 0) {
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_date_fallback,
                snapshot.current.recordedDateFallbackCount), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (snapshot.current.undatedEventCount > 0) {
            androidx.compose.material3.Text(resources.getString(ai.memora.app.R.string.finance_report_undated,
                snapshot.current.undatedEventCount), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }
}

private fun reportLine(label: String, minorUnits: Long, currency: String, language: String): String =
    "$label: ${formatReportMoney(minorUnits, language)} $currency"

private fun formatReportMoney(minorUnits: Long, language: String): String {
    val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return formatter.format(BigDecimal.valueOf(minorUnits, 2))
}

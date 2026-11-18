package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
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
import ai.memora.app.ui.EmptyState
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.IconBubble
import ai.memora.app.ui.Pill
import ai.memora.app.ui.SectionHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Finance view over the same SQLCipher ledger used by Gemini tools. */
@Composable
internal fun FinanceScreen(onOpenTarget: (AppRoute) -> Unit = {}, language: String = "az") {
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
    var loading by remember { mutableStateOf(true) }
    var retryToken by remember { mutableIntStateOf(0) }
    var selectedFilterName by rememberSaveable { mutableStateOf(FinanceScreenFilter.ALL.name) }
    val selectedFilter = FinanceScreenFilter.entries.firstOrNull { it.name == selectedFilterName } ?: FinanceScreenFilter.ALL
    val listState = rememberLazyListState()
    var displayZone by remember { mutableStateOf(ZoneId.of("Asia/Baku")) }

    LaunchedEffect(scopeId, retryToken) {
        loading = true
        events = null
        try {
            check(processor.migrateLegacyFinance())
            displayZone = runCatching {
                ZoneId.of(database.protectedStoreDao().localProfile()?.timeZoneId ?: "Asia/Baku")
            }.getOrDefault(ZoneId.of("Asia/Baku"))
            val snapshot = repository.financePresentationSnapshot(scopeId)
            events = snapshot.events.asReversed()
            voidedEventIds = snapshot.voidedEventIds
            remainingDebtByEventId = snapshot.remainingDebtByEventId
            appliedToDebtByReceiptId = snapshot.appliedToDebtByReceiptId
            people = snapshot.people
            settlements = snapshot.settlements.asReversed()
            unavailable = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
        } finally {
            loading = false
        }
    }

    val visibleEvents = events.orEmpty().filter { selectedFilter.matches(it.kind) }
    val filterOptions = listOf(
        FinanceScreenFilter.ALL.name to s(R.string.all),
        FinanceScreenFilter.RECEIVED.name to s(R.string.finance_filter_received),
        FinanceScreenFilter.EXPENSES.name to s(R.string.finance_filter_expenses),
        FinanceScreenFilter.DEBTS.name to s(R.string.finance_filter_debts),
    )
    val locale = Locale.forLanguageTag(language)

    Column(Modifier.fillMaxSize().testTag("finance_screen")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Text(
                s(R.string.finance_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.testTag("finance_subtitle"),
            )
            Filters(filterOptions, selectedFilterName, { selectedFilterName = it })
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).testTag("finance_event_list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading -> item(key = "finance_loading") {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().testTag("finance_loading"))
                }
                unavailable -> item(key = "finance_unavailable") {
                    MemoraCard {
                        androidx.compose.material3.Text(s(R.string.capture_storage_error), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                        androidx.compose.material3.TextButton(onClick = { retryToken++ }, modifier = Modifier.testTag("finance_retry")) {
                            androidx.compose.material3.Text(s(R.string.capture_retry))
                        }
                    }
                }
                events.orEmpty().isEmpty() -> item(key = "finance_empty") {
                    EmptyState(s(R.string.finance_empty_title), s(R.string.finance_empty_body), Glyph.Wallet, "finance_empty")
                }
                visibleEvents.isEmpty() -> item(key = "finance_filter_empty") {
                    EmptyState(s(R.string.finance_filter_empty), s(R.string.finance_empty_body), Glyph.Search, "finance_filter_empty")
                }
            }
            items(visibleEvents, key = { it.eventId }) { event ->
                val targetRoute = AppRoute.target(AppTargetKind.FINANCE, event.eventId)
                FinanceEventCard(
                    event = event,
                    personName = people[event.personId],
                    voided = event.eventId in voidedEventIds,
                    remainingDebtMinor = remainingDebtByEventId[event.eventId],
                    appliedToDebtMinor = appliedToDebtByReceiptId[event.eventId],
                    displayZone = displayZone,
                    locale = locale,
                    onClick = targetRoute?.let { { onOpenTarget(it) } },
                    testTag = "finance_event_${event.eventId}",
                )
            }
            if (selectedFilter == FinanceScreenFilter.ALL || selectedFilter == FinanceScreenFilter.DEBTS) {
                if (settlements.isNotEmpty()) item(key = "finance_settlement_heading") {
                    SectionHeading(s(R.string.finance_settlement_label))
                }
                items(settlements, key = { "settlement:${it.first.eventId}" }) { (settlement, personName) ->
                    MemoraCard(modifier = Modifier.testTag("finance_settlement_${settlement.eventId}")) {
                        if (personName.isNotEmpty()) androidx.compose.material3.Text(personName,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.ink)
                        Pill(s(R.string.finance_settlement_label), tone = 1)
                        androidx.compose.material3.Text(formatFinanceMoney(settlement.minorUnits, settlement.currency, locale),
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.ink)
                        settlement.occurredAt?.let { raw -> financeDisplayDate(raw, displayZone, locale)?.let { date ->
                            androidx.compose.material3.Text(date, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
                        } }
                        androidx.compose.material3.Text(settlement.reason, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = colors.muted)
                    }
                }
            }
        }
    }
}

private enum class FinanceScreenFilter {
    ALL, RECEIVED, EXPENSES, DEBTS;

    fun matches(kind: String): Boolean = when (this) {
        ALL -> true
        RECEIVED -> kind == "receipt"
        EXPENSES -> kind == "expense"
        DEBTS -> kind == "debt"
    }
}

@Composable
private fun FinanceEventCard(
    event: MoneyEventRow,
    personName: String?,
    voided: Boolean,
    remainingDebtMinor: Long?,
    appliedToDebtMinor: Long?,
    displayZone: ZoneId,
    locale: Locale,
    onClick: (() -> Unit)?,
    testTag: String,
) {
    val (label, tone) = when (event.kind) {
        "debt" -> R.string.finance_direction_debt to 2
        "expense" -> R.string.finance_direction_spent to 0
        else -> R.string.finance_direction_received to 1
    }
    MemoraCard(modifier = Modifier.testTag(testTag), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconBubble(Glyph.Wallet, tone, size = 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(s(label), tone)
                personName?.let { androidx.compose.material3.Text(it,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2) }
            }
            if (onClick != null) androidx.compose.material3.Text("›",
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = colors.muted)
        }
        androidx.compose.material3.Text(formatFinanceMoney(event.minorUnits, event.currency, locale),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = if (event.kind == "expense") colors.amber else colors.ink,
            modifier = Modifier.testTag("finance_event_amount_${event.eventId}"))
        if (voided) androidx.compose.material3.Text(s(R.string.finance_reversed_label), color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("finance_event_voided_${event.eventId}"))
        if (event.kind == "debt" && remainingDebtMinor != null) androidx.compose.material3.Text(
            "${s(R.string.finance_debt_remaining)}: ${formatFinanceMoney(remainingDebtMinor, event.currency, locale)}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        if (event.kind == "receipt" && (appliedToDebtMinor ?: 0L) > 0L) androidx.compose.material3.Text(
            "${s(R.string.finance_receipt_applied)}: ${formatFinanceMoney(appliedToDebtMinor ?: 0L, event.currency, locale)}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
        event.occurredAt?.let { raw -> financeDisplayDate(raw, displayZone, locale)?.let { date ->
            androidx.compose.material3.Text(date, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted,
                modifier = Modifier.testTag("finance_event_date_${event.eventId}"))
        } }
        event.comment?.takeIf(String::isNotBlank)?.let { androidx.compose.material3.Text(it,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.ink) }
        androidx.compose.material3.Text("${s(R.string.action_related_source)}: ${event.source}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

private fun formatFinanceMoney(minorUnits: Long, currency: String, locale: Locale): String =
    "${NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(minorUnits, 2))} $currency"

private fun financeDisplayDate(raw: String, zone: ZoneId, locale: Locale): String? = runCatching {
    val dateTime = runCatching { Instant.parse(raw).atZone(zone).toLocalDateTime() }
        .getOrElse { LocalDateTime.parse(raw) }
    dateTime.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
}.getOrElse {
    runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)) }.getOrNull()
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
    val monthLabel = remember(month, language) {
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag(language)))
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
                androidx.compose.material3.Text(monthLabel, style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
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

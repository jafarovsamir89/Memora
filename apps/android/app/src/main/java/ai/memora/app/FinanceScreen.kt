package ai.memora.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.MoneyAdjustmentRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.time.Instant
import java.time.ZoneId

/** Finance view over the same SQLCipher ledger used by Gemini tools. */
@Composable
fun FinanceScreen() {
    val context = LocalContext.current
    val processor = remember(context) { AgentVoiceTurnProcessor(context) }
    val scopeId = remember(processor) { processor.conversationScopeId() }
    val repository = remember(context) { ProtectedDomainRepository(AgentDatabase.open(context)) }
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

    ScreenColumn {
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

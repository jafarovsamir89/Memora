package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.voice.AgentVoiceTurnProcessor
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FinancePresentationSnapshotTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun batchedPresentationReadMatchesExistingLedgerReadSemantics() = runBlocking {
        val database = databaseRule.database
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context, database).conversationScopeId()
        val repository = ProtectedDomainRepository(database)
        val at = "2026-11-16T11:00:00Z"
        val debtId = UUID.randomUUID().toString()
        val receiptId = UUID.randomUUID().toString()
        val expenseId = UUID.randomUUID().toString()
        val manualSettlementId = UUID.randomUUID().toString()

        repository.recordFinanceEvent(scope, "Synthetic finance contact", "debt", 30_000, "AZN",
            "synthetic-source", null, UUID.randomUUID().toString(), debtId, UUID.randomUUID().toString(), at, at)
        repository.recordFinanceEvent(scope, "Synthetic finance contact", "receipt", 12_000, "AZN",
            "synthetic-source", null, UUID.randomUUID().toString(), receiptId, UUID.randomUUID().toString(), at, at)
        repository.recordFinanceEvent(scope, "Synthetic vendor", "expense", 5_000, "AZN",
            "synthetic-source", null, UUID.randomUUID().toString(), expenseId, UUID.randomUUID().toString(), at, at)
        repository.recordSettlement(scope, UUID.randomUUID().toString(), UUID.randomUUID().toString(), debtId,
            10_000, "AZN", at, "Synthetic partial repayment", receiptId)
        repository.recordSettlement(scope, manualSettlementId, UUID.randomUUID().toString(), debtId,
            2_000, "AZN", at, "Synthetic unlinked settlement")
        repository.correctFinanceEventAmount(scope, receiptId, 13_000, UUID.randomUUID().toString(),
            "Synthetic receipt correction", "2026-11-16T11:05:00Z")
        repository.reverseFinanceEvent(scope, expenseId, UUID.randomUUID().toString(),
            "Synthetic expense correction", "2026-11-16T11:10:00Z")

        val snapshot = repository.financePresentationSnapshot(scope)
        val canonicalEvents = repository.allFinanceEvents(scope)
        assertEquals(canonicalEvents, snapshot.events)
        assertEquals(canonicalEvents.filter { repository.isFinanceEventVoided(scope, it.eventId) }
            .map { it.eventId }.toSet(), snapshot.voidedEventIds)
        canonicalEvents.filter { it.kind == "debt" }.forEach { event ->
            assertEquals(repository.outstandingDebtMinor(scope, event.eventId), snapshot.remainingDebtByEventId[event.eventId])
        }
        canonicalEvents.filter { it.kind == "receipt" }.forEach { event ->
            assertEquals(repository.appliedToDebtMinor(scope, event.eventId), snapshot.appliedToDebtByReceiptId[event.eventId])
        }
        assertEquals(18_000L, snapshot.remainingDebtByEventId[debtId])
        assertEquals(10_000L, snapshot.appliedToDebtByReceiptId[receiptId])
        assertTrue(expenseId in snapshot.voidedEventIds)
        assertEquals("Synthetic finance contact", snapshot.people[canonicalEvents.first { it.eventId == debtId }.personId])
        assertEquals(1, snapshot.settlements.size)
        assertEquals(manualSettlementId, snapshot.settlements.single().first.eventId)
        assertEquals(debtId, snapshot.settlements.single().first.obligationId)
    }
}

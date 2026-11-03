package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.TaskEventRow
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies current task/case evidence comes from the scoped protected stores and does not mutate them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderWorkStatusTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun fakeProviderReadsExactScopedTaskAndCaseStatusWithoutWriting() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000023"
        val otherScope = "00000000-0000-4000-8000-000000000024"
        val dao = db.protectedStoreDao()
        dao.putTaskEvents(listOf(
            TaskEventRow(taskId = "synthetic-open-task", scopeId = scope, actorId = "synthetic-actor",
                commandId = "synthetic-task-created", type = "created", previousVersion = null,
                title = "Synthetic delivery follow-up", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = "Synthetic Client", projectLabel = "Synthetic Project"),
            TaskEventRow(taskId = "synthetic-completed-task", scopeId = scope, actorId = "synthetic-actor",
                commandId = "synthetic-task-completed-created", type = "created", previousVersion = null,
                title = "Synthetic completed call", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "synthetic-completed-task", scopeId = scope, actorId = "synthetic-actor",
                commandId = "synthetic-task-completed", type = "completed", previousVersion = 1,
                title = null, completed = true, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "other-scope-task", scopeId = otherScope, actorId = "synthetic-other-actor",
                commandId = "other-task-created", type = "created", previousVersion = null,
                title = "Must not leak", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
        ))
        val domain = ProtectedDomainRepository(db)
        val cases = ProtectedCaseRepository(db)
        val financeTime = "2026-11-03T08:00:00Z"
        domain.recordFinanceEvent(scope, "Synthetic Client", "receipt", 10_025, "AZN", "Synthetic business fixture", null,
            "synthetic-finance-person", "synthetic-business-receipt", "synthetic-business-receipt-command", financeTime, financeTime)
        domain.recordFinanceEvent(scope, "Synthetic Client", "expense", 4_000, "AZN", "Synthetic business fixture", null,
            "synthetic-finance-person", "synthetic-business-expense", "synthetic-business-expense-command", financeTime, financeTime)
        cases.createCase(scope, "synthetic-open-case", "Synthetic delivery", "Fictional test case",
            nextAction = "Confirm fictional arrival", sourceTurnId = "synthetic-source-turn",
            now = "2026-11-03T09:00:00Z")
        cases.createCase(scope, "synthetic-completed-case", "Synthetic finished setup", null,
            status = "completed", sourceTurnId = "synthetic-completed-source",
            now = "2026-11-03T08:00:00Z")
        cases.createCase(otherScope, "foreign-case", "Must not leak", null,
            sourceTurnId = "foreign-source", now = "2026-11-03T07:00:00Z")

        val eventCountBefore = dao.taskEvents().size
        val financeCountBefore = domain.allFinanceEvents(scope).size
        val currentCaseStatusCountsBefore = cases.statusCounts(scope)
        val provider = FakeWorkStatusProvider()
        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db)).handlers()
            .filter { it.descriptor.name in setOf("analyze_finance_period", "summarize_work_status") }
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handler), AllowPolicy(), MemoryJournal())
            .run(AgentTurnRequest("00000000-0000-4000-8000-000000000021", scope,
                "00000000-0000-4000-8000-000000000022", null, "az-AZ", "Synthetic: summarize current work"))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals(setOf("analyze_finance_period", "summarize_work_status"), result.toolResults.map { it.name }.toSet())
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val observations = result.toolResults.associateBy { it.name }
        val finance = requireNotNull(observations["analyze_finance_period"])
        assertEquals("finance_report_ready", finance.code)
        val monthly = finance.modelData["period"] as Map<*, *>
        val currency = (monthly["currencies"] as List<*>).single() as Map<*, *>
        assertEquals(10_025L, currency["received_minor_units"])
        assertEquals(4_000L, currency["spent_minor_units"])

        val observation = requireNotNull(observations["summarize_work_status"])
        assertEquals("work_status_ready", observation.code)
        val tasks = observation.modelData["tasks"] as Map<*, *>
        assertEquals(2, tasks["total_current_tasks"])
        assertEquals(1, tasks["open_current_tasks"])
        assertEquals(1, tasks["completed_current_tasks"])
        val taskSample = tasks["open_tasks"] as List<*>
        assertEquals("synthetic-open-task", (taskSample.single() as Map<*, *>) ["task_id"])
        assertFalse(tasks["completion_period_analysis_available"] as Boolean)

        val caseData = observation.modelData["cases"] as Map<*, *>
        assertEquals(2, caseData["current_case_count"])
        assertEquals(1, caseData["open_case_count"])
        assertEquals(mapOf("completed" to 1, "open" to 1), caseData["status_counts"])
        val casesSample = caseData["recent_case_sample"] as List<*>
        assertEquals(2, casesSample.size)
        assertTrue(casesSample.none { (it as Map<*, *>) ["title"] == "Must not leak" })
        val limitations = observation.modelData["limitations"] as List<*>
        assertTrue(limitations.any { it == "task_completion_timestamps_not_stored; no period completion rate" })
        assertEquals(eventCountBefore, dao.taskEvents().size)
        assertEquals(financeCountBefore, domain.allFinanceEvents(scope).size)
        assertEquals(currentCaseStatusCountsBefore, cases.statusCounts(scope))
        assertEquals(2, provider.turnCount)
    }

    private class FakeWorkStatusProvider : AgentModelGateway {
        private val replies = ArrayDeque(listOf(
            AgentModelReply.Calls(listOf(
                AgentModelToolCall("fake-business-finance", "analyze_finance_period", 1, mapOf("month" to "2026-11")),
                AgentModelToolCall("fake-business-work", "summarize_work_status", 1, emptyMap()),
            )),
            AgentModelReply.Answer("Synthetic current work summary."),
        ))
        var turnCount = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            turnCount++
            return replies.removeFirst()
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class MemoryJournal : AgentRunJournal {
        private val commands = mutableMapOf<String, AgentCommandJournalEntry>()
        override suspend fun beginTurn(turnId: String) = Unit
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun recordCommand(command: AgentCommandJournalEntry) { commands[command.commandId] = command }
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) {
            commands[commandId]?.let { commands[commandId] = it.copy(status = status) }
        }
        override suspend fun interruptedTurns() = emptyList<AgentTurnJournalEntry>()
        override suspend fun command(commandId: String) = commands[commandId]
        override suspend fun unresolvedCommands() = emptyList<AgentCommandJournalEntry>()
    }
}

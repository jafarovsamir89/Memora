package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.TaskEventRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One consented local query should find related evidence without copying or changing source records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentFakeProviderUnifiedMemoryTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun naturalCrossDomainSearchReturnsScopedCanonicalSourcesAndLeavesStoresUnchanged() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000144"
        val otherScope = "00000000-0000-4000-8000-000000000145"
        val domain = ProtectedDomainRepository(db)
        val memory = ProtectedMemoryRepository(db)
        val conversations = ProtectedConversationRepository(db)
        val profiles = ProtectedProfileMemoryRepository(db)
        val cases = ProtectedCaseRepository(db)

        domain.createObject(scope, "synthetic-elchin-person", DomainKind.PERSON, "Synthetic Elchin",
            "Fictional BMW repair customer", null, "2026-11-03T08:00:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "expense", 5_000, "AZN",
            "Synthetic BMW engine repair deposit", null, "synthetic-finance-elchin", "synthetic-bmw-expense",
            "synthetic-bmw-expense-command", "2026-11-03T09:00:00Z", "2026-11-03T09:00:00Z")
        cases.createCase(scope, "synthetic-bmw-case", "Synthetic BMW repair", "Fictional work with Synthetic Elchin",
            nextAction = "Confirm test drive", sourceTurnId = "synthetic-case-source", now = "2026-11-03T09:10:00Z")
        cases.addParticipant(scope, "synthetic-bmw-case", "synthetic-elchin-person", "customer",
            "synthetic-bmw-participant", now = "2026-11-03T09:11:00Z")
        cases.addEvidence(scope, "synthetic-bmw-case", "money_event", "synthetic-bmw-expense", "repair_deposit",
            now = "2026-11-03T09:12:00Z")

        db.protectedStoreDao().putTaskEvents(listOf(
            TaskEventRow(taskId = "synthetic-bmw-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-bmw-task-created", type = "created", previousVersion = null,
                title = "Collect repaired BMW", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "synthetic-bmw-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-bmw-task-details", type = "details", previousVersion = 1,
                title = null, completed = null, dueLocal = "2026-11-04T12:00", dueZone = "Asia/Baku",
                remind = false, personLabel = "Synthetic Elchin", projectLabel = "Synthetic BMW"),
        ))
        domain.createCalendarEvent(CalendarInput("synthetic-bmw-calendar", scope, "BMW workshop pickup",
            "2026-11-04T13:00", null, "Asia/Baku", "minute"))
        domain.createTable(scope, "synthetic-bmw-table", "Synthetic repairs", "editable",
            listOf(ColumnSpec("vehicle", "Vehicle", ColumnType.TEXT, required = true),
                ColumnSpec("contact", "Contact", ColumnType.TEXT)))
        domain.addRows(scope, "synthetic-bmw-table", 1, listOf("synthetic-bmw-row" to mapOf(
            "vehicle" to "BMW engine", "contact" to "Synthetic Elchin")))
        conversations.appendTurn(scope, "synthetic-bmw-conversation",
            "Synthetic Elchin asked about the BMW engine repair appointment.", "Synthetic reply recorded.", 1_793_691_000_000)
        profiles.saveUserStated(scope, "context", "Synthetic BMW repair is an active fictional case",
            "synthetic-bmw-conversation", "Synthetic Elchin asked about the BMW engine repair appointment.", 1_793_691_000_000)

        domain.createObject(otherScope, "foreign-bmw-note", DomainKind.NOTE, "Synthetic BMW",
            "Foreign scope marker", null, "2026-11-03T08:00:00Z")
        domain.recordFinanceEvent(otherScope, "Synthetic Elchin", "expense", 99_000, "AZN",
            "Foreign scope marker BMW repair", null, "foreign-finance-person", "foreign-bmw-expense",
            "foreign-bmw-expense-command", "2026-11-03T09:00:00Z", "2026-11-03T09:00:00Z")
        cases.createCase(otherScope, "foreign-bmw-case", "Foreign BMW repair", null,
            sourceTurnId = "foreign-case-source", now = "2026-11-03T09:10:00Z")
        db.protectedStoreDao().putTaskEvents(listOf(TaskEventRow(
            taskId = "foreign-bmw-task", scopeId = otherScope, actorId = "foreign-owner",
            commandId = "foreign-bmw-task-created", type = "created", previousVersion = null,
            title = "Foreign BMW task", completed = null, dueLocal = null, dueZone = null, remind = null,
            personLabel = null, projectLabel = null,
        )))
        domain.createCalendarEvent(CalendarInput("foreign-bmw-calendar", otherScope, "Foreign BMW appointment",
            "2026-11-04T13:00", null, "Asia/Baku", "minute"))
        domain.createTable(otherScope, "foreign-bmw-table", "Foreign BMW table", "editable",
            listOf(ColumnSpec("value", "Value", ColumnType.TEXT)))
        domain.addRows(otherScope, "foreign-bmw-table", 1, listOf("foreign-bmw-row" to mapOf("value" to "Foreign marker")))
        conversations.appendTurn(otherScope, "foreign-bmw-conversation", "Foreign BMW marker", "Foreign reply.", 1_793_691_000_000)

        val taskCount = db.protectedStoreDao().taskEvents().size
        val financeCount = domain.allFinanceEvents(scope).size
        val caseCount = cases.cases(scope).size
        val calendarCount = domain.allCalendarEvents(scope).size
        val tableRows = domain.tables(scope).sumOf { it.rows.size }
        val provider = UnifiedMemoryProvider()
        val handlers = LocalMemoryTools(domain, memory, conversations = conversations, profileMemory = profiles).handlers()
            .filter { it.descriptor.name == "search_memory" }
        val searchHandler = handlers.single()
        val turnRequest = AgentTurnRequest("00000000-0000-4000-8000-000000000146", scope,
            "00000000-0000-4000-8000-000000000147", null, "az-AZ",
            "Synthetic: what is happening with Elçinin BMW repair, payment, task, appointment and table?")
        val result = AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), AllowPolicy(), MemoryJournal())
            .run(turnRequest)

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Synthetic cross-domain answer used the scoped evidence.", result.answer)
        val observation = result.toolResults.single()
        assertEquals("search_memory", observation.name)
        assertEquals(ToolObservationStatus.APPLIED, observation.status)
        assertEquals("ranked_lexical", observation.modelData["match_mode"])
        val activity = observation.modelData["activity"] as List<*>
        val activityKinds = activity.map { (it as Map<*, *>)["kind"] as String }.toSet()
        assertTrue(activityKinds.containsAll(setOf("money_event", "case", "task", "calendar_event", "table_row")))
        assertTrue(activity.none { (it as Map<*, *>).values.toString().contains("foreign-") })
        assertFalse(observation.modelData["activity_truncated"] as Boolean)
        val caseHit = activity.map { it as Map<*, *> }.single { it["kind"] == "case" }
        assertEquals("synthetic-bmw-case:1", caseHit["source_revision_id"])
        val caseSources = caseHit["sources"] as List<*>
        assertEquals("synthetic-bmw-expense", ((caseSources.single() as Map<*, *>)["source_id"]))
        val moneyHit = activity.map { it as Map<*, *> }.single { it["kind"] == "money_event" }
        assertEquals("synthetic-bmw-expense", moneyHit["source_id"])
        assertEquals(5_000L, moneyHit["minor_units"])
        assertEquals(true, moneyHit["exact_financial_totals_require_search_money"])
        val conversationTurns = observation.modelData["conversation_turns"] as List<*>
        assertEquals("synthetic-bmw-conversation", ((conversationTurns.single() as Map<*, *>)["turn_id"]))
        val profileFacts = observation.modelData["profile_facts"] as List<*>
        assertEquals("synthetic-bmw-conversation", (((profileFacts.single() as Map<*, *>)["source_turn_ids"] as List<*>).single()))
        assertNotNull(provider.answerReceivedEvidence)
        assertEquals(taskCount, db.protectedStoreDao().taskEvents().size)
        assertEquals(financeCount, domain.allFinanceEvents(scope).size)
        assertEquals(caseCount, cases.cases(scope).size)
        assertEquals(calendarCount, domain.allCalendarEvents(scope).size)
        assertEquals(tableRows, domain.tables(scope).sumOf { it.rows.size })

        val inflectedName = searchHandler.read(AgentModelToolCall("synthetic-az-name-search", "search_memory", 1,
            mapOf("query" to "Elçinin")), turnRequest)
        assertEquals(ToolObservationStatus.APPLIED, inflectedName.status)
        val azActivity = inflectedName.modelData["activity"] as List<*>
        val azKinds = azActivity.map { (it as Map<*, *>)["kind"] as String }.toSet()
        assertTrue("unexpected AZ name results: $azKinds", azKinds.containsAll(setOf("money_event", "case", "task", "table_row")))
        assertTrue((inflectedName.modelData["evidence"] as List<*>).isNotEmpty())
        assertTrue((inflectedName.modelData["conversation_turns"] as List<*>).isNotEmpty())
    }

    @Test fun exactPhraseAndTitleMatchesRankAheadOfScatteredFieldMatches() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000154"
        db.protectedStoreDao().putTaskEvents(listOf(
            TaskEventRow(taskId = "scattered-match", scopeId = scope, actorId = "owner",
                commandId = "scattered-created", type = "created", previousVersion = null,
                title = "BMW engine repair", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "phrase-match", scopeId = scope, actorId = "owner",
                commandId = "phrase-created", type = "created", previousVersion = null,
                title = "BMW repair pickup", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
        ))
        val tools = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db)).handlers()
        val handler = tools.single { it.descriptor.name == "search_memory" }
        val result = handler.read(AgentModelToolCall("phrase-ranking", "search_memory", 1,
            mapOf("query" to "BMW repair")), AgentTurnRequest("turn-ranking", scope, "session-ranking",
            null, "az-AZ", "synthetic query"))

        assertEquals(ToolObservationStatus.APPLIED, result.status)
        assertEquals("ranked_lexical", result.modelData["match_mode"])
        val hits = result.modelData["activity"] as List<*>
        val taskIds = hits.mapNotNull { (it as Map<*, *>).takeIf { row -> row["kind"] == "task" }?.get("task_id") }
        assertEquals(listOf("phrase-match", "scattered-match"), taskIds)
    }

    private class UnifiedMemoryProvider : AgentModelGateway {
        var answerReceivedEvidence: Map<*, *>? = null
        private var callIndex = 0

        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            return if (callIndex++ == 0) AgentModelReply.Calls(listOf(AgentModelToolCall(
                "synthetic-unified-search", "search_memory", 1,
                mapOf("query" to "Elçinin BMW repair payment task appointment table"),
            ))) else {
                val observation = request.previousToolResults.single()
                val activity = observation.modelData["activity"] as List<*>
                val kinds = activity.map { (it as Map<*, *>)["kind"] }.toSet()
                check(kinds.containsAll(setOf("money_event", "case", "task", "calendar_event", "table_row")))
                answerReceivedEvidence = observation.modelData
                AgentModelReply.Answer("Synthetic cross-domain answer used the scoped evidence.")
            }
        }
    }

    private class AllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) = PolicyDecision(PolicyAction.ALLOW, "allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class MemoryJournal : AgentRunJournal {
        override suspend fun beginTurn(turnId: String) = Unit
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun recordCommand(command: AgentCommandJournalEntry) = Unit
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) = Unit
        override suspend fun interruptedTurns() = emptyList<AgentTurnJournalEntry>()
        override suspend fun command(commandId: String) = null
        override suspend fun unresolvedCommands() = emptyList<AgentCommandJournalEntry>()
    }
}

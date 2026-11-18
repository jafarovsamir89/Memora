package ai.memora.app.acceptance

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.agent.AgentModelGateway
import ai.memora.app.agent.AgentModelReply
import ai.memora.app.agent.AgentModelRequest
import ai.memora.app.agent.AgentModelToolCall
import ai.memora.app.agent.AgentOrchestrator
import ai.memora.app.agent.AgentRunJournal
import ai.memora.app.agent.AgentRunResultStatus
import ai.memora.app.agent.AgentRuntimePolicy
import ai.memora.app.agent.AgentToolCatalog
import ai.memora.app.agent.AgentToolDescriptor
import ai.memora.app.agent.AgentTurnRequest
import ai.memora.app.agent.AgentTurnStatus
import ai.memora.app.agent.DefaultAgentToolRegistry
import ai.memora.app.agent.LocalMemoryTools
import ai.memora.app.agent.PolicyAction
import ai.memora.app.agent.PolicyDecision
import ai.memora.app.agent.PreparedAgentCommand
import ai.memora.app.agent.ToolObservationStatus
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.PersonRow
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake-provider journeys through the production agent runtime and existing synthetic stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyntheticReliabilityJourneyTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()
    private val fixtureDirectories = mutableListOf<File>()

    @After fun removeSyntheticFiles() {
        fixtureDirectories.forEach { directory ->
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    @Test fun fictionalBmwDebtRepaymentAndCorrectionRunThroughFakeProviderAndReadback() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000026252"
        val personId = "00000000-0000-4000-8000-000000026253"
        val financePersonId = "00000000-0000-4000-8000-000000026254"
        val caseId = "00000000-0000-4000-8000-000000026255"
        val debtId = "00000000-0000-4000-8000-000000026256"
        val fixedNow = "2026-11-18T12:00:00Z"
        val domain = ProtectedDomainRepository(db)
        val cases = ProtectedCaseRepository(db)
        val aliases = PersonAliasRepository(db)
        domain.createObject(scope, personId, DomainKind.PERSON, "Elçin Məmmədov", "Fictional BMW mechanic", null, fixedNow)
        domain.recordFinanceEvent(scope, "Elçin Məmmədov", "debt", 30_000, "AZN", "synthetic scenario source",
            "Fictional BMW repair obligation", financePersonId, debtId,
            "00000000-0000-4000-8000-000000026257", fixedNow, fixedNow)
        aliases.add(scope, personId, "Elçin BMW ustası", nowMillis = Instant.parse(fixedNow).toEpochMilli())
        cases.confirmFinancePersonIdentity(scope, financePersonId, personId,
            linkId = "00000000-0000-4000-8000-000000026258", confirmedBy = "owner", now = fixedNow)
        cases.createCase(scope, caseId, "BMW təmiri", "Fictional repair case",
            sourceTurnId = "00000000-0000-4000-8000-000000026259", now = fixedNow)
        cases.addParticipant(scope, caseId, personId, "mechanic", "00000000-0000-4000-8000-000000026260", fixedNow)
        cases.addEvidence(scope, caseId, "money_event", debtId, "repair_obligation",
            "00000000-0000-4000-8000-000000026261", fixedNow)

        val initialEvents = domain.allFinanceEvents(scope).size
        val tools = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = aliases).handlers()
        val provider = ScriptedProvider { request ->
            when (request.previousToolResults.size) {
                0 -> AgentModelReply.Calls(listOf(AgentModelToolCall("journey-record-receipt", "record_money_event", 1,
                    mapOf("kind" to "receipt", "person_name" to "Elçin BMW ustası", "amount" to "100",
                        "currency" to "AZN", "comment" to "Fictional partial repayment"))))
                1 -> {
                    val receiptId = request.previousToolResults.single { it.name == "record_money_event" }
                        .modelData["id"] as String
                    AgentModelReply.Calls(listOf(AgentModelToolCall("journey-link-receipt", "link_case_source", 1,
                        mapOf("case_id" to caseId, "source_kind" to "money_event", "source_id" to receiptId,
                            "relation" to "partial_repayment"))))
                }
                2 -> {
                    val receiptId = request.previousToolResults.single { it.name == "record_money_event" }
                        .modelData["id"] as String
                    AgentModelReply.Calls(listOf(AgentModelToolCall("journey-settle-debt", "settle_debt", 1,
                        mapOf("obligation_id" to debtId, "amount" to "100", "reason" to "Fictional repayment",
                            "payment_event_id" to receiptId))))
                }
                3 -> {
                    val receiptId = request.previousToolResults.single { it.name == "record_money_event" }
                        .modelData["id"] as String
                    AgentModelReply.Calls(listOf(AgentModelToolCall("journey-correct-receipt", "correct_money_event", 1,
                        mapOf("event_id" to receiptId, "new_amount" to "120",
                            "reason" to "Fictional user correction"))))
                }
                4 -> AgentModelReply.Calls(listOf(AgentModelToolCall("journey-search-money", "search_money", 1,
                    mapOf("person_name" to "Elçin BMW ustası", "query" to "", "kind" to ""))))
                5 -> AgentModelReply.Calls(listOf(AgentModelToolCall("journey-search-case", "search_cases", 1,
                    mapOf("query" to "BMW təmiri"))))
                else -> AgentModelReply.Answer("Elçin 120 manat verdi, BMW borcunun qalığı 180 manatdır.")
            }
        }

        val result = orchestrator(provider, tools).run(AgentTurnRequest(
            turnId = "00000000-0000-4000-8000-000000026262", scopeId = scope,
            actorId = "00000000-0000-4000-8000-000000026263", sourceRevisionId = null,
            locale = "az-AZ", text = "Sintetik ssenari: Elçin BMW ustası 100 manat verdi, sonra məbləği 120-yə düzəlt.",
        ))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertEquals("Elçin 120 manat verdi, BMW borcunun qalığı 180 manatdır.", result.answer)
        assertEquals(listOf("receipt_recorded", "case_source_linked", "debt_settlement_recorded",
            "money_event_corrected", "financial_evidence_loaded", "cases_loaded"), result.toolResults.map { it.code })
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val receiptId = result.toolResults.single { it.name == "record_money_event" }.modelData["id"] as String
        assertTrue(receiptId.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(receiptId, result.toolResults.single { it.name == "settle_debt" }.modelData["payment_event_id"])
        assertEquals(initialEvents + 1, domain.allFinanceEvents(scope).size)
        assertEquals(18_000L, domain.outstandingDebtMinor(scope, debtId))
        val readback = result.toolResults.single { it.name == "search_money" }.modelData
        val summary = readback["financial_summary"] as Map<*, *>
        assertEquals(18_000L, summary["outstanding_debt_minor_units"])
        val events = readback["events"] as List<*>
        val correctedReceipt = events.map { it as Map<*, *> }.single { it["event_id"] == receiptId }
        assertEquals(12_000L, correctedReceipt["minor_units"])
        assertEquals(12_000L, correctedReceipt["applied_to_debt_minor_units"])
        val caseResult = result.toolResults.single { it.name == "search_cases" }.modelData
        val case = (caseResult["cases"] as List<*>).map { it as Map<*, *> }.single { it["case_id"] == caseId }
        assertEquals(personId, ((case["participants"] as List<*>).single() as Map<*, *>) ["person_object_id"])
        val linkedSourceIds = (case["sources"] as List<*>).map { (it as Map<*, *>) ["id"] }
        assertTrue("case source IDs did not include the exact ledger rows: $linkedSourceIds",
            linkedSourceIds.containsAll(listOf(debtId, receiptId)))
        assertTrue(db.protectedStoreDao().adjustments(scope).any {
            it.kind == "amount_correction" && it.reversesEventId == receiptId
        })
        assertTrue(db.protectedStoreDao().adjustments(scope).any {
            it.kind == "correction" && it.obligationId == debtId && it.minorUnits == 2_000L
        })
    }

    @Test fun sameNameRuslanWriteStopsForClarificationWithoutLedgerMutation() = runBlocking {
        val profile = createFixture()
        profile.seed()
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val scope = SyntheticReliabilityIds.scope
        db.protectedStoreDao().insertPerson(PersonRow("00000000-0000-4000-8000-000000026264",
            "Ruslan Əliyev", scope))
        val initialEvents = domain.allFinanceEvents(scope).size
        val initialAdjustments = db.protectedStoreDao().adjustments(scope).size
        val provider = ScriptedProvider { request ->
            if (request.previousToolResults.isEmpty()) AgentModelReply.Calls(listOf(AgentModelToolCall(
                "ambiguous-ruslan-write", "record_money_event", 1,
                mapOf("kind" to "receipt", "person_name" to "Ruslan Əliyev", "amount" to "150", "currency" to "AZN"),
            ))) else AgentModelReply.Answer("I should ask which Ruslan this is.")
        }
        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = PersonAliasRepository(db))
            .handlers().filter { it.descriptor.name == "record_money_event" }
        val result = orchestrator(provider, handler).run(AgentTurnRequest(
            "00000000-0000-4000-8000-000000026267", scope,
            "00000000-0000-4000-8000-000000026268", null, "az-AZ",
            "Ruslan Əliyev mənə 150 manat qaytardı.",
        ))

        assertEquals(AgentRunResultStatus.AWAITING_CLARIFICATION, result.status)
        assertEquals("person_name_ambiguous", result.code)
        assertTrue(result.answer.orEmpty().contains("Hansını nəzərdə tutursunuz?"))
        assertEquals("person_name_ambiguous", result.toolResults.single().code)
        assertEquals(ToolObservationStatus.CONFLICT, result.toolResults.single().status)
        assertEquals(initialEvents, domain.allFinanceEvents(scope).size)
        assertEquals(initialAdjustments, db.protectedStoreDao().adjustments(scope).size)
    }

    @Test fun fakeProviderReadsExactCaseMoneyTaskMemoryAndRussianSourceInOneScopedJourney() = runBlocking {
        createFixture().seed()
        val db = databaseRule.database
        val ids = SyntheticReliabilityIds
        val domain = ProtectedDomainRepository(db)
        val beforeFinance = domain.allFinanceEvents(ids.scope).size
        val beforeCases = ProtectedCaseRepository(db).cases(ids.scope).size
        val tools = LocalMemoryTools(domain, ProtectedMemoryRepository(db),
            conversations = ProtectedConversationRepository(db),
            profileMemory = ProtectedProfileMemoryRepository(db), personAliases = PersonAliasRepository(db),
            userTimeZoneId = "Asia/Baku",
            searchClock = Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneOffset.UTC),
            authoritativeLocalDate = java.time.LocalDate.parse("2026-11-18"),
        ).handlers().filter { it.descriptor.name in setOf(
            "create_task", "search_cases", "search_money", "search_memory", "list_tasks",
            "search_conversation_history", "read_conversation_turn",
        ) }
        val provider = ScriptedProvider { request ->
            when (request.previousToolResults.size) {
                0 -> AgentModelReply.Calls(listOf(
                    AgentModelToolCall("journey-create-task", "create_task", 3, mapOf(
                        "title" to "Ruslana sintetik zəng", "due_local" to "2025-11-19T10:00",
                        "zone_id" to "Asia/Baku", "remind" to false, "person_label" to "Ruslan Əliyev",
                    )),
                    AgentModelToolCall("journey-load-case", "search_cases", 1, mapOf("query" to "BMW təmiri")),
                    AgentModelToolCall("journey-load-money", "search_money", 1, mapOf(
                        "person_name" to "Elçin BMW ustası", "query" to "", "kind" to "",
                    )),
                    AgentModelToolCall("journey-load-memory", "search_memory", 1,
                        mapOf("query" to "Elçin Məmmədov BMW təmiri")),
                    AgentModelToolCall("journey-load-conversation", "search_conversation_history", 1, mapOf(
                        "query" to "BMW", "max_results" to 8,
                    )),
                    AgentModelToolCall("journey-load-tasks", "list_tasks", 1, emptyMap()),
                ))
                6 -> {
                    val results = request.previousToolResults.associateBy { it.name }
                    val task = results.getValue("create_task")
                    assertEquals(ToolObservationStatus.APPLIED, task.status)
                    assertEquals("2026-11-19T10:00", task.modelData["due_local"])
                    val listed = results.getValue("list_tasks").modelData["tasks"] as List<*>
                    val taskRow = listed.map { it as Map<*, *> }.single { it["id"] == task.modelData["id"] }
                    assertEquals("2026-11-19T10:00", taskRow["due_local"])

                    val caseRows = results.getValue("search_cases").modelData["cases"] as List<*>
                    val case = caseRows.map { it as Map<*, *> }.single { it["case_id"] == ids.caseBmw }
                    assertEquals(ids.personElchin, ((case["participants"] as List<*>).single() as Map<*, *>)["person_object_id"])
                    assertTrue((case["sources"] as List<*>).any { (it as Map<*, *>)["id"] == ids.financeEvent })

                    val money = results.getValue("search_money").modelData
                    val moneyRows = money["events"] as List<*>
                    assertTrue(moneyRows.map { (it as Map<*, *>)["event_id"] }.containsAll(
                        listOf(ids.financeEvent, ids.financeReceiptElchin)))
                    assertEquals(18_000L, (money["financial_summary"] as Map<*, *>)["outstanding_debt_minor_units"])

                    val memoryActivity = results.getValue("search_memory").modelData["activity"] as List<*>
                    val activityKinds = memoryActivity.map { (it as Map<*, *>)["kind"] }.toSet()
                    assertTrue("unexpected scoped activity kinds: $activityKinds",
                        activityKinds.containsAll(setOf("case", "money_event", "task", "calendar_event", "table_row")))
                    val linkedTask = memoryActivity.map { it as Map<*, *> }.single { it["task_id"] == ids.roomTask }
                    assertEquals("explicit_person_task_link", linkedTask["retrieval_reason"])
                    assertEquals(listOf(ids.personElchin), linkedTask["matched_person_ids"])
                    assertFalse(memoryActivity.any { it.toString().contains(ids.foreignScope) || it.toString().contains(ids.personForeign) })

                    val conversationEvidence = results.getValue("search_conversation_history").modelData["evidence"] as List<*>
                    val russianTurn = conversationEvidence.map { it as Map<*, *> }.single { it["turn_id"] == ids.conversationTurnRu }
                    assertTrue((russianTurn["snippet"] as String).contains("за ремонт BMW"))
                    AgentModelReply.Calls(listOf(AgentModelToolCall("journey-read-ru-turn", "read_conversation_turn", 1,
                        mapOf("turn_id" to ids.conversationTurnRu))))
                }
                else -> {
                    val source = request.previousToolResults.single { it.name == "read_conversation_turn" }
                    assertEquals(ids.conversationTurnRu, source.modelData["turn_id"])
                    assertEquals("Он сегодня дал 100 манат за ремонт BMW.", source.modelData["user_message"])
                    AgentModelReply.Answer("Источник найден, задача создана на 19 ноября по времени Баку.")
                }
            }
        }
        val initialCount = db.protectedStoreDao().taskEvents().size
        val result = orchestrator(provider, tools).run(AgentTurnRequest(
            "00000000-0000-4000-8000-000000026270", ids.scope,
            "00000000-0000-4000-8000-000000026271", null, "az-AZ",
            "Sabah saat 10:00-da Ruslana zəng etməyi xatırlat və BMW təmiri barədə yaddaşı yoxla.",
            authoritativeLocalDate = "2026-11-18",
        ))

        assertEquals(AgentRunResultStatus.COMPLETED, result.status)
        assertTrue(result.toolResults.all { it.status == ToolObservationStatus.APPLIED })
        val created = result.toolResults.single { it.name == "create_task" }
        assertEquals("2026-11-19T10:00", created.modelData["due_local"])
        assertEquals("Asia/Baku", created.modelData["due_zone"])
        assertTrue(db.protectedStoreDao().taskEvents().size > initialCount)
        assertEquals(beforeFinance, domain.allFinanceEvents(ids.scope).size)
        assertEquals(beforeCases, ProtectedCaseRepository(db).cases(ids.scope).size)
    }

    private fun createFixture(): SyntheticReliabilityProfile {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "memora-acceptance-synthetic-").toFile()
        fixtureDirectories += directory
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SyntheticReliabilityProfile(databaseRule.database, directory, key)
    }

    private fun orchestrator(provider: AgentModelGateway, handlers: List<ai.memora.app.agent.AgentToolHandler>) =
        AgentOrchestrator(provider, DefaultAgentToolRegistry(handlers), TestAllowPolicy(), TestMemoryJournal())

    private class ScriptedProvider(private val respond: (AgentModelRequest) -> AgentModelReply) : AgentModelGateway {
        var calls = 0
        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
            request.tools.forEach(AgentToolCatalog::require)
            calls++
            return respond(request)
        }
    }

    private class TestAllowPolicy : AgentRuntimePolicy {
        override suspend fun beforeModelSend(turn: AgentTurnRequest, includesStoredMemory: Boolean) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun authorizeTool(turn: AgentTurnRequest, descriptor: AgentToolDescriptor) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun authorizeCommit(turn: AgentTurnRequest, command: PreparedAgentCommand) =
            PolicyDecision(PolicyAction.ALLOW, "synthetic_test_allowed")
        override suspend fun confirmationGranted(turn: AgentTurnRequest, command: PreparedAgentCommand) = true
    }

    private class TestMemoryJournal : AgentRunJournal {
        override suspend fun beginTurn(turnId: String) = Unit
        override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = Unit
        override suspend fun recordCommand(command: ai.memora.app.agent.AgentCommandJournalEntry) = Unit
        override suspend fun setCommandStatus(commandId: String, expected: String, status: String) = Unit
        override suspend fun interruptedTurns() = emptyList<ai.memora.app.agent.AgentTurnJournalEntry>()
        override suspend fun command(commandId: String) = null
        override suspend fun unresolvedCommands() = emptyList<ai.memora.app.agent.AgentCommandJournalEntry>()
    }
}

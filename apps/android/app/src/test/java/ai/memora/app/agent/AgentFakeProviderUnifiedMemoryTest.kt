package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.CaseParticipantRow
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.LinkedViewSpec
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.TaskEventRow
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

    @Test fun searchMemoryFindsOnlyActiveSameScopeAliasPhrasesInsideNaturalQueries() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000193"
        val otherScope = "00000000-0000-4000-8000-000000000194"
        val domain = ProtectedDomainRepository(db)
        val aliases = PersonAliasRepository(db)
        domain.createObject(scope, "synthetic-person-alias-active", DomainKind.PERSON, "Synthetic Canonical Person",
            null, null, "2026-11-04T08:00:00Z")
        domain.createObject(scope, "synthetic-person-alias-retired", DomainKind.PERSON, "Synthetic Retired Person",
            null, null, "2026-11-04T08:00:00Z")
        domain.createObject(otherScope, "synthetic-person-alias-foreign", DomainKind.PERSON, "Synthetic Foreign Person",
            null, null, "2026-11-04T08:00:00Z")
        aliases.add(scope, "synthetic-person-alias-active", "Samir Ustad", sourceTurnId = "synthetic-alias-source")
        val retired = aliases.add(scope, "synthetic-person-alias-retired", "Old Samir")
        aliases.retire(scope, retired.aliasId, retired.version)
        aliases.add(otherScope, "synthetic-person-alias-foreign", "Samir Ustad")
        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = aliases).handlers()
            .single { it.descriptor.name == "search_memory" }

        val result = handler.read(AgentModelToolCall("synthetic-alias-query", "search_memory", 1,
            mapOf("query" to "What did Samir, Ustad promise?")), AgentTurnRequest(
            "synthetic-alias-turn", scope, "synthetic-alias-session", null, "en-US", "Synthetic query"))
        val aliasHits = result.modelData["person_aliases"] as List<*>
        assertEquals(1, aliasHits.size)
        val hit = aliasHits.single() as Map<*, *>
        assertEquals("synthetic-person-alias-active", hit["person_id"])
        assertEquals("Synthetic Canonical Person", hit["canonical_name"])
        assertEquals("exact_alias_phrase", hit["match_mode"])
        assertEquals("synthetic-alias-source", hit["source_turn_id"])
        assertEquals(1, aliases.list(scope, "synthetic-person-alias-active").size)

        domain.createObject(scope, "synthetic-canonical-alias-collision", DomainKind.PERSON, "Samir Ustad",
            null, null, "2026-11-04T08:01:00Z")
        val ambiguousResult = handler.read(AgentModelToolCall("synthetic-alias-ambiguous-query", "search_memory", 1,
            mapOf("query" to "What did Samir Ustad promise?")), AgentTurnRequest(
            "synthetic-alias-ambiguous-turn", scope, "synthetic-alias-session", null, "en-US", "Synthetic ambiguous query"))
        assertTrue((ambiguousResult.modelData["person_aliases"] as List<*>).isEmpty())
    }

    @Test fun searchMemoryFollowsMatchedPersonToOnlyExplicitlyLinkedCases() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000195"
        val foreignScope = "00000000-0000-4000-8000-000000000196"
        val domain = ProtectedDomainRepository(db)
        val aliases = PersonAliasRepository(db)
        val cases = ProtectedCaseRepository(db)
        domain.createObject(scope, "synthetic-linked-person", DomainKind.PERSON, "Synthetic Canonical Person",
            null, null, "2026-11-04T09:00:00Z")
        domain.createObject(foreignScope, "synthetic-foreign-person", DomainKind.PERSON, "Synthetic Foreign Person",
            null, null, "2026-11-04T09:00:00Z")
        aliases.add(scope, "synthetic-linked-person", "Samir Ustad", sourceTurnId = "synthetic-linked-alias-source")
        cases.createCase(scope, "synthetic-linked-case", "Synthetic turbine repair", "Fictional engine repair context",
            nextAction = "Confirm delivery", sourceTurnId = "synthetic-linked-case-source",
            now = "2026-11-04T09:10:00Z")
        cases.addParticipant(scope, "synthetic-linked-case", "synthetic-linked-person", "customer",
            "synthetic-linked-participant", now = "2026-11-04T09:11:00Z")
        cases.createCase(scope, "synthetic-unlinked-case", "Synthetic unrelated delivery", "No person participant",
            sourceTurnId = "synthetic-unlinked-case-source", now = "2026-11-04T09:12:00Z")
        cases.createCase(foreignScope, "synthetic-foreign-linked-case", "Synthetic foreign matter", "Foreign scope",
            sourceTurnId = "synthetic-foreign-case-source", now = "2026-11-04T09:13:00Z")
        cases.addParticipant(foreignScope, "synthetic-foreign-linked-case", "synthetic-foreign-person", "customer",
            "synthetic-foreign-participant", now = "2026-11-04T09:14:00Z")

        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db), personAliases = aliases).handlers()
            .single { it.descriptor.name == "search_memory" }
        val result = handler.read(AgentModelToolCall("synthetic-linked-person-query", "search_memory", 1,
            mapOf("query" to "What did Samir Ustad promise?")), AgentTurnRequest(
            "synthetic-linked-person-turn", scope, "synthetic-linked-person-session", null, "en-US", "Synthetic query"))
        val activity = result.modelData["activity"] as List<*>
        val caseHits = activity.mapNotNull { it as? Map<*, *> }.filter { it["kind"] == "case" }
        assertEquals(listOf("synthetic-linked-case"), caseHits.mapNotNull { it["case_id"] as? String })
        assertEquals("explicit_person_case_link", caseHits.single()["retrieval_reason"])
        assertEquals(listOf("synthetic-linked-person"), caseHits.single()["matched_person_ids"])
        assertEquals(1, cases.casesForPerson(scope, "synthetic-linked-person").size)
    }

    @Test fun caseSearchPagesBeyondTheNewestHundredRecords() = runBlocking {
        val scope = "00000000-0000-4000-8000-000000000190"
        val dao = databaseRule.database.protectedStoreDao()
        repeat(105) { index ->
            val updatedAt = Instant.parse("2026-11-03T00:00:00Z").plusSeconds(index.toLong()).toString()
            dao.insertCase(CaseRecordRow(
                caseId = "synthetic-case-page-$index", scopeId = scope,
                title = if (index == 0) "Synthetic gearbox delivery" else "Synthetic unrelated case $index",
                summary = null, status = "open", nextAction = null, version = 1,
                sourceTurnId = "synthetic-case-page-source-$index", createdAt = updatedAt,
                updatedAt = updatedAt, deletedAt = null,
            ))
        }

        val matches = ProtectedCaseRepository(databaseRule.database).searchCases(scope, "gearbox delivery", limit = 30)
        assertEquals(listOf("synthetic-case-page-0"), matches.map { it.caseId })
    }

    @Test fun personLinkedCasesAreNotHiddenByUnrelatedRecentCases() = runBlocking {
        val scope = "00000000-0000-4000-8000-000000000191"
        val otherScope = "00000000-0000-4000-8000-000000000192"
        val dao = databaseRule.database.protectedStoreDao()
        val base = Instant.parse("2026-11-03T00:00:00Z")
        repeat(105) { index ->
            val updatedAt = base.plusSeconds(index.toLong()).toString()
            dao.insertCase(CaseRecordRow("synthetic-unrelated-$index", scope, "Synthetic unrelated $index",
                null, "open", null, 1, "synthetic-unrelated-source-$index", updatedAt, updatedAt, null))
        }
        val olderCaseTime = base.minusSeconds(1).toString()
        dao.insertCase(CaseRecordRow("synthetic-person-older-case", scope, "Synthetic older linked case",
            null, "open", null, 1, "synthetic-person-case-source", olderCaseTime, olderCaseTime, null))
        dao.insertCaseParticipant(CaseParticipantRow("synthetic-person-old-link", scope,
            "synthetic-person-older-case", "synthetic-person-191", "contact", olderCaseTime, null))
        dao.insertCase(CaseRecordRow("synthetic-foreign-person-case", otherScope, "Synthetic foreign linked case",
            null, "open", null, 1, "synthetic-foreign-source", olderCaseTime, olderCaseTime, null))
        dao.insertCaseParticipant(CaseParticipantRow("synthetic-foreign-person-link", otherScope,
            "synthetic-foreign-person-case", "synthetic-person-191", "contact", olderCaseTime, null))

        val matches = ProtectedCaseRepository(databaseRule.database).casesForPerson(scope, "synthetic-person-191")
        assertEquals(listOf("synthetic-person-older-case"), matches.map { it.caseId })
    }

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
            nextAction = "Confirm test drive", sourceTurnId = "synthetic-case-source", now = "2026-11-03T09:10:00Z",
            sourceText = "Synthetic owner update: replacement parts for the BMW arrive Friday.",
            sourceQuote = "replacement parts for the BMW arrive Friday")
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
        val caseQuotes = caseHit["source_quotes"] as List<*>
        assertEquals(1, caseQuotes.size)
        val caseQuote = caseQuotes.single() as Map<*, *>
        assertEquals("case:synthetic-bmw-case:1", caseQuote["revision_id"])
        assertEquals("synthetic-case-source", caseQuote["source_turn_id"])
        assertEquals("replacement parts for the BMW arrive Friday", caseQuote["excerpt"])
        assertEquals(true, caseQuote["exact_match_verified"])
        assertEquals(false, caseHit["source_quotes_truncated"])
        val caseSources = caseHit["sources"] as List<*>
        assertEquals("synthetic-bmw-expense", ((caseSources.single() as Map<*, *>)["source_id"]))
        val moneyHit = activity.map { it as Map<*, *> }.single { it["kind"] == "money_event" }
        assertEquals("synthetic-bmw-expense", moneyHit["source_id"])
        assertEquals(5_000L, moneyHit["minor_units"])
        assertEquals(true, moneyHit["exact_financial_totals_require_search_money"])
        val conversationTurns = observation.modelData["conversation_turns"] as List<*>
        assertTrue(conversationTurns.map { (it as Map<*, *>)["turn_id"] }.contains("synthetic-bmw-conversation"))
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

        val sourcePhraseSearch = searchHandler.read(AgentModelToolCall("synthetic-case-source-search", "search_memory", 1,
            mapOf("query" to "replacement parts arrive Friday")), turnRequest)
        assertEquals(ToolObservationStatus.APPLIED, sourcePhraseSearch.status)
        val sourcePhraseHits = sourcePhraseSearch.modelData["activity"] as List<*>
        val sourcePhraseCase = sourcePhraseHits.map { it as Map<*, *> }
            .single { it["kind"] == "case" && it["source_id"] == "synthetic-bmw-case" }
        assertEquals("replacement parts for the BMW arrive Friday",
            ((sourcePhraseCase["source_quotes"] as List<*>).single() as Map<*, *>)["excerpt"])
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

    @Test fun explicitIsoDateFindsOnlyDatedFinanceTaskAndCalendarSources() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000158"
        val domain = ProtectedDomainRepository(db)
        domain.recordFinanceEvent(scope, "Synthetic customer", "receipt", 1_250, "AZN",
            "synthetic dated receipt", null, "synthetic-date-person", "synthetic-date-receipt",
            "synthetic-date-receipt-command", "2026-11-03T21:30:00Z", "2026-11-03T21:30:00Z")
        domain.recordFinanceEvent(scope, "Synthetic customer", "receipt", 2_500, "AZN",
            "synthetic next-day receipt", null, "synthetic-date-person", "synthetic-next-day-receipt",
            "synthetic-next-day-receipt-command", "2026-11-04T21:30:00Z", "2026-11-04T21:30:00Z")
        domain.createCalendarEvent(CalendarInput("synthetic-date-calendar", scope, "Synthetic appointment",
            "2026-11-04T12:00", null, "Asia/Baku", "minute"))
        domain.createCalendarEvent(CalendarInput("synthetic-next-day-calendar", scope, "Synthetic appointment",
            "2026-11-05T12:00", null, "Asia/Baku", "minute"))
        db.protectedStoreDao().putTaskEvents(listOf(
            TaskEventRow(taskId = "synthetic-date-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-date-task-created", type = "created", previousVersion = null,
                title = "Synthetic follow-up", completed = null, dueLocal = null,
                dueZone = null, remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "synthetic-date-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-date-task-due", type = "details", previousVersion = 1,
                title = null, completed = null, dueLocal = "2026-11-04T15:00",
                dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "synthetic-next-day-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-next-day-task-created", type = "created", previousVersion = null,
                title = "Synthetic follow-up", completed = null, dueLocal = null,
                dueZone = null, remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "synthetic-next-day-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "synthetic-next-day-task-due", type = "details", previousVersion = 1,
                title = null, completed = null, dueLocal = "2026-11-05T15:00",
                dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null),
        ))
        val handler = LocalMemoryTools(domain, ProtectedMemoryRepository(db), financeEnabled = true,
            userTimeZoneId = "Asia/Baku",
            searchClock = Clock.fixed(Instant.parse("2026-11-03T10:30:00Z"), ZoneOffset.UTC))
            .handlers().single { it.descriptor.name == "search_memory" }
        val turn = AgentTurnRequest("synthetic-date-search-turn", scope, "synthetic-date-search-session",
            null, "az-AZ", "Find synthetic records on 2026-11-04")
        val result = handler.read(AgentModelToolCall("synthetic-date-query", "search_memory", 1,
            mapOf("query" to "2026-11-04")), turn)

        assertEquals(ToolObservationStatus.APPLIED, result.status)
        val hits = result.modelData["activity"] as List<*>
        val keys = hits.map { row ->
            val item = row as Map<*, *>
            "${item["kind"]}:${item["source_id"]}"
        }.toSet()
        assertTrue("date should retrieve its scoped finance source: $keys", "money_event:synthetic-date-receipt" in keys)
        assertTrue("date should retrieve its scoped calendar source: $keys", "calendar_event:synthetic-date-calendar" in keys)
        assertTrue("date should retrieve its scoped task source: $keys", "task:synthetic-date-task" in keys)
        assertFalse("next-day finance source must not match", "money_event:synthetic-next-day-receipt" in keys)
        assertFalse("next-day calendar source must not match", "calendar_event:synthetic-next-day-calendar" in keys)
        assertFalse("next-day task source must not match", "task:synthetic-next-day-task" in keys)

        listOf("sabah", "tomorrow", "завтра").forEach { relativeDate ->
            val relativeResult = handler.read(AgentModelToolCall("synthetic-relative-$relativeDate", "search_memory", 1,
                mapOf("query" to relativeDate)), turn)
            val relativeHits = relativeResult.modelData["activity"] as List<*>
            val relativeKeys = relativeHits.map { row ->
                val item = row as Map<*, *>
                "${item["kind"]}:${item["source_id"]}"
            }.toSet()
            assertTrue("$relativeDate should use profile-local tomorrow: $relativeKeys",
                "money_event:synthetic-date-receipt" in relativeKeys &&
                    "calendar_event:synthetic-date-calendar" in relativeKeys &&
                    "task:synthetic-date-task" in relativeKeys)
            assertFalse("$relativeDate must exclude next local day", "task:synthetic-next-day-task" in relativeKeys)
        }
    }

    @Test fun typedTableReferencesRequireActiveSameScopeObjectsOfExpectedKind() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val scope = "00000000-0000-4000-8000-000000000159"
        val otherScope = "00000000-0000-4000-8000-000000000160"
        domain.createObject(scope, "synthetic-ref-person", DomainKind.PERSON, "Synthetic person", null, null, "2026-11-03T10:00:00Z")
        domain.createObject(scope, "synthetic-ref-project", DomainKind.PROJECT, "Synthetic project", null, null, "2026-11-03T10:00:00Z")
        domain.createObject(scope, "synthetic-ref-note", DomainKind.NOTE, "Synthetic note", null, null, "2026-11-03T10:00:00Z")
        domain.createObject(otherScope, "synthetic-ref-foreign-person", DomainKind.PERSON, "Foreign synthetic person", null, null, "2026-11-03T10:00:00Z")
        domain.createTable(scope, "synthetic-linked-table", "Synthetic linked records", "editable", listOf(
            ColumnSpec("person", "Person", ColumnType.PERSON_REF),
            ColumnSpec("project", "Project", ColumnType.PROJECT_REF)))

        val saved = domain.addRows(scope, "synthetic-linked-table", 1, listOf("synthetic-linked-row" to mapOf(
            "person" to "synthetic-ref-person", "project" to "synthetic-ref-project")))
        assertEquals(1, saved.size)
        assertTrue(saved.single().valuesJson.contains("synthetic-ref-person"))
        assertTrue(saved.single().valuesJson.contains("synthetic-ref-project"))

        val invalidLinks = listOf(
            "synthetic-ref-wrong-kind" to mapOf("person" to "synthetic-ref-project", "project" to "synthetic-ref-project"),
            "synthetic-ref-foreign" to mapOf("person" to "synthetic-ref-foreign-person", "project" to "synthetic-ref-project"),
            "synthetic-ref-missing" to mapOf("person" to "synthetic-does-not-exist", "project" to "synthetic-ref-project"),
        )
        invalidLinks.forEach { (id, values) ->
            assertTrue("invalid reference row $id must be rejected", runCatching {
                domain.addRows(scope, "synthetic-linked-table", 1, listOf(id to values))
            }.isFailure)
        }

        val note = domain.domainObject(scope, "synthetic-ref-note")!!
        domain.delete(scope, domain.previewDelete(scope, note.objectId, note.version), "2026-11-03T11:00:00Z")
        assertTrue("soft-deleted reference must be rejected", runCatching {
            domain.addRows(scope, "synthetic-linked-table", 1, listOf("synthetic-ref-deleted" to mapOf(
                "person" to "synthetic-ref-person", "project" to "synthetic-ref-note")))
        }.isFailure)
        assertEquals(listOf("synthetic-linked-row"), domain.tables(scope).single().rows.map { it.rowId })
    }

    @Test fun linkedDomainViewReadsLiveScopedSourcesWithoutCopyingOrEditingThem() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-linked-view-scope"
        val foreignScope = "synthetic-linked-view-foreign"
        domain.createObject(scope, "synthetic-view-person", DomainKind.PERSON, "Before rename", "Synthetic body", null,
            "2026-11-04T10:00:00Z")
        val archived = domain.createObject(scope, "synthetic-view-archived", DomainKind.PERSON, "Archived person", null, null,
            "2026-11-04T10:00:00Z")
        domain.updateObject(scope, archived.objectId, archived.version, archived.title, archived.body, "archived", null,
            "2026-11-04T10:30:00Z")
        domain.createObject(foreignScope, "synthetic-view-foreign-person", DomainKind.PERSON, "Foreign person", null, null,
            "2026-11-04T10:00:00Z")
        domain.createObject(scope, "synthetic-view-project", DomainKind.PROJECT, "Unrelated project", null, null,
            "2026-11-04T10:00:00Z")
        domain.createTable(scope, "synthetic-person-view", "Synthetic linked people", "linked_view",
            listOf(ColumnSpec("source", "Source", ColumnType.TEXT)),
            viewSpec = LinkedViewSpec(DomainKind.PERSON))

        val first = domain.tables(scope).single()
        assertEquals("PERSON", first.table.viewSpecJson?.let { org.json.JSONObject(it).getString("sourceKind") })
        assertEquals(1, first.linkedTotalCount)
        assertTrue(first.rows.isEmpty())
        assertEquals(listOf("synthetic-view-person"), domain.linkedViewPage(scope, first.table.tableId, "", true, 0, 25).rows.map { it.objectId })
        assertTrue(runCatching {
            domain.addRows(scope, "synthetic-person-view", 1, listOf("synthetic-forbidden-row" to mapOf("source" to "copy")))
        }.isFailure)
        val viewExport = org.json.JSONObject(domain.exportTable(scope, "synthetic-person-view"))
        assertEquals("memora-table-v2", viewExport.getString("format"))
        assertEquals("linked_view", viewExport.getString("mode"))
        assertEquals(0, viewExport.getJSONArray("rows").length())
        assertEquals("PERSON", viewExport.getJSONObject("viewSpec").getString("sourceKind"))
        val preview = domain.previewImport(scope, "synthetic-person-view", viewExport.toString())
        assertTrue(domain.importRows(scope, preview).isEmpty())
        assertTrue(databaseRule.database.protectedStoreDao().rows("synthetic-person-view", scope).isEmpty())
        assertTrue("linked view schemas must remain source-spec controlled", runCatching {
            domain.changeTableSchema(scope, "synthetic-person-view", 1,
                listOf(ColumnSpec("source", "Source", ColumnType.TEXT), ColumnSpec("extra", "Extra", ColumnType.TEXT)),
                "2026-11-04T10:30:00Z")
        }.isFailure)

        val original = domain.domainObject(scope, "synthetic-view-person")!!
        domain.updateObject(scope, original.objectId, original.version, "Renamed source", original.body, original.status,
            "synthetic-linked-view-update", "2026-11-04T11:00:00Z")
        assertEquals("Renamed source", domain.linkedViewPage(scope, first.table.tableId, "", true, 0, 25).rows.single().title)
    }

    @Test fun linkedViewPagesAndSearchCoverMoreThanFiveHundredActiveSources() = runBlocking {
        val db = databaseRule.database
        val domain = ProtectedDomainRepository(db)
        val scope = "synthetic-large-linked-view-scope"
        val sources = (0..600).map { index ->
            val id = "synthetic-large-view-%03d".format(index)
            DomainObjectRow(id, scope, "PERSON", "Person %03d".format(index),
                if (index == 600) "Əli QƏRAR" else "Group A", "active", 1, null)
        }
        db.protectedStoreDao().insertDomainObjects(sources +
            DomainObjectRow("synthetic-large-view-archived", scope, "PERSON", "Archived source", null, "archived", 1, null) +
            DomainObjectRow("synthetic-large-view-deleted", scope, "PERSON", "Deleted source", null, "active", 1, "2026-11-04T12:00:00Z"))
        domain.createTable(scope, "synthetic-large-person-view", "Synthetic people", "linked_view",
            listOf(ColumnSpec("source", "Source", ColumnType.TEXT)), viewSpec = LinkedViewSpec(DomainKind.PERSON))

        val snapshot = domain.tables(scope).single()
        assertEquals(601, snapshot.linkedTotalCount)
        val finalPage = domain.linkedViewPage(scope, snapshot.table.tableId, "", ascending = true, offset = 600, limit = 25)
        assertEquals(601, finalPage.totalCount)
        assertEquals(601, finalPage.matchingCount)
        assertEquals(600, finalPage.offset)
        assertEquals(listOf("synthetic-large-view-600"), finalPage.rows.map { it.objectId })
        assertEquals(null, finalPage.nextOffset)

        val descending = domain.linkedViewPage(scope, snapshot.table.tableId, "Group A", ascending = false, offset = 25, limit = 25)
        assertEquals(600, descending.matchingCount)
        assertEquals("synthetic-large-view-574", descending.rows.first().objectId)
        assertEquals("synthetic-large-view-550", descending.rows.last().objectId)

        val azSearch = domain.linkedViewPage(scope, snapshot.table.tableId, "əli", ascending = true, offset = 0, limit = 25)
        assertEquals(1, azSearch.matchingCount)
        assertEquals(listOf("synthetic-large-view-600"), azSearch.rows.map { it.objectId })
        assertEquals("Əli QƏRAR", azSearch.rows.single().body)

        val tools = LocalMemoryTools(domain, ProtectedMemoryRepository(db)).handlers()
        val listResult = tools.single { it.descriptor.name == "list_tables" }.read(
            AgentModelToolCall("large-list", "list_tables", 1, emptyMap()),
            AgentTurnRequest("large-view-turn", scope, "synthetic-owner", null, "az-AZ", "list tables"))
        @Suppress("UNCHECKED_CAST")
        val listedTable = (listResult.modelData["tables"] as List<Map<String, Any?>>).single()
        assertEquals(601, listedTable["row_count"])
        val agentSearch = tools.single { it.descriptor.name == "read_table_rows" }.read(
            AgentModelToolCall("large-search", "read_table_rows", 1,
                mapOf("table_id" to snapshot.table.tableId, "query" to "əli", "limit" to 5)),
            AgentTurnRequest("large-view-turn", scope, "synthetic-owner", null, "az-AZ", "search table"))
        assertEquals(1, agentSearch.modelData["matching_row_count"])
        assertEquals(false, agentSearch.modelData["source_set_truncated"])
        @Suppress("UNCHECKED_CAST")
        val agentRows = agentSearch.modelData["rows"] as List<Map<String, Any?>>
        assertEquals("synthetic-large-view-600", agentRows.single()["source_id"])
    }

    @Test fun agentCanReadLinkedViewPagesAndReportsWhetherSourcesAreTruncated() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-agent-linked-page-scope"
        repeat(30) { index ->
            val id = "synthetic-agent-view-%03d".format(index)
            domain.createObject(scope, id, DomainKind.PERSON, "Person %03d".format(index), "Group A", null,
                "2026-11-04T10:00:00Z")
        }
        domain.createTable(scope, "synthetic-agent-linked-page", "Synthetic people", "linked_view",
            listOf(ColumnSpec("source", "Source", ColumnType.TEXT)), viewSpec = LinkedViewSpec(DomainKind.PERSON))
        val tools = LocalMemoryTools(domain, ProtectedMemoryRepository(databaseRule.database)).handlers()
        val handler = tools.single { it.descriptor.name == "read_table_rows" }
        val turn = AgentTurnRequest("synthetic-linked-page-turn", scope, "synthetic-owner", null, "az-AZ", "read people")
        val first = handler.read(AgentModelToolCall("page-1", "read_table_rows", 1,
            mapOf("table_id" to "synthetic-agent-linked-page", "offset" to 0, "limit" to 5)), turn)
        assertEquals(ToolObservationStatus.APPLIED, first.status)
        assertEquals(5, first.modelData["returned"])
        assertEquals(5, first.modelData["next_offset"])
        assertEquals(false, first.modelData["source_set_truncated"])
        assertEquals(true, first.modelData["truncated"])
        val last = handler.read(AgentModelToolCall("page-2", "read_table_rows", 1,
            mapOf("table_id" to "synthetic-agent-linked-page", "query" to "person 02", "offset" to 5, "limit" to 10)), turn)
        assertEquals(5, last.modelData["returned"])
        assertEquals(null, last.modelData["next_offset"])
        val invalid = handler.read(AgentModelToolCall("page-invalid", "read_table_rows", 1,
            mapOf("table_id" to "synthetic-agent-linked-page", "limit" to 51)), turn)
        assertEquals(ToolObservationStatus.REJECTED, invalid.status)
        val invalidOffset = handler.read(AgentModelToolCall("page-offset-invalid", "read_table_rows", 1,
            mapOf("table_id" to "synthetic-agent-linked-page", "offset" to 100_001)), turn)
        assertEquals(ToolObservationStatus.REJECTED, invalidOffset.status)
    }

    @Test fun localMemoryRetrievalFindsExactAzerbaijaniRussianAndMixedButDoesNotClaimParaphraseRecall() = runBlocking {
        val db = databaseRule.database
        val scope = "00000000-0000-4000-8000-000000000155"
        db.protectedStoreDao().putTaskEvents(listOf(
            TaskEventRow(taskId = "az-bmw-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "az-bmw-created", type = "created", previousVersion = null,
                title = "BMW təmir görüşü cümə", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "ru-bmw-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "ru-bmw-created", type = "created", previousVersion = null,
                title = "BMW ремонт встреча пятница", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = "en-bmw-task", scopeId = scope, actorId = "synthetic-owner",
                commandId = "en-bmw-created", type = "created", previousVersion = null,
                title = "BMW repair appointment Friday", completed = null, dueLocal = null, dueZone = null,
                remind = null, personLabel = null, projectLabel = null),
        ))
        val handler = LocalMemoryTools(ProtectedDomainRepository(db), ProtectedMemoryRepository(db)).handlers()
            .single { it.descriptor.name == "search_memory" }
        val turn = AgentTurnRequest("synthetic-retrieval-turn", scope, "synthetic-retrieval-session",
            null, "az-AZ", "Synthetic local retrieval baseline")

        fun taskIds(result: ToolCommitResult): Set<String> {
            assertEquals(ToolObservationStatus.APPLIED, result.status)
            assertEquals("ranked_lexical", result.modelData["match_mode"])
            return (result.modelData["activity"] as List<*>)
                .mapNotNull { (it as Map<*, *>).takeIf { row -> row["kind"] == "task" }?.get("task_id") as? String }
                .toSet()
        }

        val az = handler.read(AgentModelToolCall("az-retrieval", "search_memory", 1,
            mapOf("query" to "BMW təmir görüşü")), turn)
        val ru = handler.read(AgentModelToolCall("ru-retrieval", "search_memory", 1,
            mapOf("query" to "BMW ремонт встреча")), turn)
        val mixed = handler.read(AgentModelToolCall("mixed-retrieval", "search_memory", 1,
            mapOf("query" to "BMW təmir appointment")), turn)
        val paraphrase = handler.read(AgentModelToolCall("paraphrase-retrieval", "search_memory", 1,
            mapOf("query" to "car servicing meeting")), turn)

        assertTrue("AZ exact lexical source missing", "az-bmw-task" in taskIds(az))
        assertTrue("RU exact lexical source missing", "ru-bmw-task" in taskIds(ru))
        assertTrue("mixed AZ/EN exact-term source missing", "az-bmw-task" in taskIds(mixed))
        assertTrue("mixed AZ/EN exact-term source missing", "en-bmw-task" in taskIds(mixed))
        assertTrue("lexical retrieval must not imply paraphrase recall", taskIds(paraphrase).isEmpty())
    }

    @Test fun syntheticRetrievalCorpusReportsMetricsForThirtyAZRuAndMixedQueries() = runBlocking {
        data class Record(val id: String, val title: String, val queries: List<String>)
        // All synthetic task IDs are judged for each query; every ID outside this set is non-relevant.
        data class LabeledQuery(val text: String, val relevantIds: Set<String>)
        data class Observation(val relevantIds: Set<String>, val actual: List<String>)
        val corpus = mapOf(
            "az" to listOf(
                Record("az-01", "BMW təmir görüşü cümə", listOf("BMW təmir görüşü", "bmw təmir görüşə", "BMW təmir görüşü cümə")),
                Record("az-02", "müqavilə bank imzası", listOf("müqavilə bank imzası", "müqaviləni bank imzala", "bank müqavilə imza")),
                Record("az-03", "mənzil kirayə depozit", listOf("mənzil kirayə depozit", "mənzilin kirayəsi depozit", "kirayə mənzil depoziti")),
                Record("az-04", "Əli ilə nahar görüşü", listOf("Əli ilə nahar görüşü", "Əlinin nahar görüşü", "eli nahar gorus")),
                Record("az-05", "həkim qəbulu çərşənbə", listOf("həkim qəbulu çərşənbə", "həkimin qəbuluna çərşənbə", "hekim qebul çərşənbə")),
                Record("az-06", "çatdırılma ehtiyat hissə", listOf("çatdırılma ehtiyat hissə", "çatdırılmanın ehtiyat hissələri", "ehtiyat hissələrin çatdırılması")),
                Record("az-07", "sığorta ödəniş tarixi", listOf("sığorta ödəniş tarixi", "sığortanın ödənişləri", "sigorta odeme tarixi")),
                Record("az-08", "layihə təqdimat planı", listOf("layihə təqdimat planı", "layihənin təqdimatını planla", "layihe teqdimati plan")),
                Record("az-09", "məktəb valideyn görüşü", listOf("məktəb valideyn görüşü", "məktəbdə valideynlərlə görüş", "mekteb valideyn gorus")),
                Record("az-10", "səfər Bakı bilet", listOf("səfər Bakı bilet", "Bakı səfərinin bileti", "sefer bakı bilet")),
            ),
            "ru" to listOf(
                Record("ru-01", "BMW ремонт встреча пятница", listOf("BMW ремонт встреча", "BMW ремонта встреча", "встреча по BMW ремонту")),
                Record("ru-02", "договор банк подпись", listOf("договор банк подпись", "подписать договор в банке", "банковский договор подпись")),
                Record("ru-03", "квартира аренда депозит", listOf("квартира аренда депозит", "депозит за квартиру аренды", "арендный депозит квартиры")),
                Record("ru-04", "Али обед встреча", listOf("Али обед встреча", "встреча с Али на обеде", "обеденная встреча Али")),
                Record("ru-05", "врач прием среда", listOf("врач прием среда", "запись к врачу на среду", "средний прием врача")),
                Record("ru-06", "доставка запасные детали", listOf("доставка запасные детали", "доставить запасные детали", "запчасти доставка")),
                Record("ru-07", "страховка дата платежа", listOf("страховка дата платежа", "платежи по страховке", "дата оплаты страховки")),
                Record("ru-08", "проект план презентации", listOf("проект план презентации", "планировать презентацию проекта", "презентационный план проекта")),
                Record("ru-09", "школа встреча родителей", listOf("школа встреча родителей", "родительское собрание в школе", "встреча родителей школа")),
                Record("ru-10", "поездка Баку билет", listOf("поездка Баку билет", "билет для поездки в Баку", "бакинская поездка билет")),
            ),
            "mixed" to listOf(
                Record("mx-01", "BMW təmir appointment Friday", listOf("BMW təmir appointment", "BMW təmir appointment Friday", "appointment BMW təmir Friday")),
                Record("mx-02", "müqavilə bank meeting", listOf("müqavilə bank meeting", "bankda müqavilə meeting", "mugavile bank meeting")),
                Record("mx-03", "mənzil rent depozit", listOf("mənzil rent depozit", "rent mənzilin depoziti", "menzil rent deposit")),
                Record("mx-04", "Əli lunch görüşü", listOf("Əli lunch görüşü", "Əlinin lunch görüşü", "eli lunch gorus")),
                Record("mx-05", "həkim appointment Wednesday", listOf("həkim appointment Wednesday", "həkimə appointment", "hekim appointment Wednesday")),
                Record("mx-06", "ehtiyat hissə delivery", listOf("ehtiyat hissə delivery", "hissələrin delivery", "ehtiyat part delivery")),
                Record("mx-07", "sığorta payment date", listOf("sığorta payment date", "sığortanın payment", "sigorta payment date")),
                Record("mx-08", "layihə presentation plan", listOf("layihə presentation plan", "layihənin presentation", "layihe presentation plan")),
                Record("mx-09", "məktəb parent meeting", listOf("məktəb parent meeting", "məktəbdə parent görüşü", "mekteb parent meeting")),
                Record("mx-10", "səfər Bakı ticket", listOf("səfər Bakı ticket", "Bakı səfərinin ticket", "sefer bakı ticket")),
            ),
        )
        val scope = "00000000-0000-4000-8000-000000000156"
        val events = corpus.flatMap { (language, records) -> records.map { record ->
            TaskEventRow(taskId = record.id, scopeId = scope, actorId = "synthetic-owner",
                commandId = "$language-${record.id}-created", type = "created", previousVersion = null,
                title = record.title, completed = null, dueLocal = null, dueZone = null, remind = null,
                personLabel = null, projectLabel = null)
        } } + TaskEventRow(taskId = "outside-scope-decoy", scopeId = "other-scope", actorId = "synthetic-owner",
            commandId = "outside-scope-decoy-created", type = "created", previousVersion = null,
            title = "BMW təmir görüşü", completed = null, dueLocal = null, dueZone = null, remind = null,
            personLabel = null, projectLabel = null)
        databaseRule.database.protectedStoreDao().putTaskEvents(events)
        val handler = LocalMemoryTools(ProtectedDomainRepository(databaseRule.database),
            ProtectedMemoryRepository(databaseRule.database)).handlers().single { it.descriptor.name == "search_memory" }
        val turn = AgentTurnRequest("retrieval-evaluation-turn", scope, "retrieval-evaluation-session", null,
            "az-AZ", "Synthetic retrieval evaluation")
        val additionalQueries = mapOf(
            "az" to listOf(
                LabeledQuery("görüşlər", setOf("az-01", "az-04", "az-09")),
                LabeledQuery("BMW ödəniş", emptySet()),
            ),
            "ru" to listOf(
                LabeledQuery("встречи", setOf("ru-01", "ru-04", "ru-09")),
                LabeledQuery("BMW оплата", emptySet()),
            ),
            "mixed" to listOf(
                LabeledQuery("meeting", setOf("mx-04", "mx-09")),
                LabeledQuery("BMW payment", emptySet()),
            ),
        )
        val relevantUniverse = corpus.values.flatten().map { it.id }.toSet()
        for ((language, records) in corpus) {
            val labeledQueries = records.flatMap { record ->
                record.queries.map { query -> LabeledQuery(query, setOf(record.id)) }
            } + additionalQueries.getValue(language)
            val observations = labeledQueries.mapIndexed { index, query ->
                val result = handler.read(AgentModelToolCall("retrieval-eval-$language-$index", "search_memory", 1,
                    mapOf("query" to query.text)), turn)
                assertEquals(ToolObservationStatus.APPLIED, result.status)
                assertEquals("ranked_lexical", result.modelData["match_mode"])
                val taskHits = (result.modelData["activity"] as List<*>).mapNotNull { item ->
                    (item as? Map<*, *>)?.takeIf { it["kind"] == "task" }
                }
                assertTrue("cross-scope row leaked for '${query.text}'", taskHits.none { it["task_id"] == "outside-scope-decoy" })
                assertTrue("source/revision missing for '${query.text}'", taskHits.all {
                    it["source_id"] == it["task_id"] && it["source_revision_id"] == "${it["task_id"]}:1"
                })
                val ids = taskHits.map { it["task_id"] as String }
                assertEquals("duplicate task evidence for '${query.text}'", ids.size, ids.distinct().size)
                assertTrue("unknown task source returned for '${query.text}'", ids.all { it in relevantUniverse })
                Observation(query.relevantIds, ids)
            }
            assertEquals("each language has 30 lexical variants, a multi-source query and a near-miss", 32, observations.size)
            val positiveObservations = observations.filter { it.relevantIds.isNotEmpty() }
            val negativeObservations = observations.filter { it.relevantIds.isEmpty() }
            val recallAt5 = positiveObservations.sumOf { observation ->
                observation.actual.take(5).count { it in observation.relevantIds }.toDouble() /
                    observation.relevantIds.size
            } / positiveObservations.size
            val precisionAt5 = observations.sumOf { observation ->
                observation.actual.take(5).count { it in observation.relevantIds }.toDouble() / 5.0
            } / observations.size
            val precisionAt1 = observations.count { observation ->
                observation.actual.firstOrNull() in observation.relevantIds
            }.toDouble() / observations.size
            val precisionAtReturned = observations.sumOf { observation ->
                if (observation.actual.isEmpty()) 0.0 else
                    observation.actual.count { it in observation.relevantIds }.toDouble() / observation.actual.size
            } / observations.size
            val meanReturned = observations.sumOf { it.actual.size }.toDouble() / observations.size
            val mrr = observations.sumOf { item ->
                item.actual.indexOfFirst { it in item.relevantIds }.takeIf { it >= 0 }?.let { 1.0 / (it + 1) } ?: 0.0
            } / observations.size
            val falsePositiveIds = observations.sumOf { item -> item.actual.count { it !in item.relevantIds } }
            val negativeQueryFalsePositiveCount = negativeObservations.count { it.actual.isNotEmpty() }
            val negativeQueryFalsePositiveIds = negativeObservations.sumOf { it.actual.size }
            val missingRelevantAt5 = observations.sumOf { item ->
                item.relevantIds.count { relevant -> relevant !in item.actual.take(5) }
            }
            println("M20_04_JUDGED_METRICS language=$language queries=${observations.size} positiveQueries=${positiveObservations.size} negativeQueries=${negativeObservations.size} Recall@5=$recallAt5 Precision@5=$precisionAt5 Precision@1=$precisionAt1 Precision@returned=$precisionAtReturned meanReturned=$meanReturned MRR=$mrr falsePositiveIds=$falsePositiveIds missingRelevantAt5=$missingRelevantAt5 negativeQueriesWithFalsePositives=$negativeQueryFalsePositiveCount negativeQueryFalsePositiveIds=$negativeQueryFalsePositiveIds")
        }
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

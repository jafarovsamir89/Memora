package ai.memora.app.agent

import ai.memora.app.UiAgentDatabaseRule
import ai.memora.app.ai.DirectGeminiApi
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.RoomAgentRunJournal
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Gemini -> production agent runtime/tools -> scenario-specific state/readback in isolated Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiLiveScenarioCampaignTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test
    fun allOneThousandAzerbaijaniTurnsPassThroughGeminiAndConnectedAppFlows() = runBlocking {
        assumeTrue("Set MEMORA_RUN_LIVE_GEMINI=1 to permit real external Gemini requests",
            System.getenv("MEMORA_RUN_LIVE_GEMINI") == "1")
        check(DirectGeminiApi.isConfigured) { "local_debug_gemini_key_missing" }

        val corpusFile = findRepoFile("docs/voice-evaluation/az-gemini-live-scenarios-v1.jsonl")
        val scenarios = corpusFile.readLines(Charsets.UTF_8).filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
        validateCorpus(scenarios)
        val totalStories = scenarios.map { it.string("story_id") }.distinct().size
        val startStory = System.getenv("MEMORA_LIVE_GEMINI_START_STORY")?.toIntOrNull() ?: 1
        require(startStory in 1..totalStories) { "live_start_story_out_of_range" }
        val remainingTurns = (totalStories - startStory + 1) * 10
        val requestedTurns = System.getenv("MEMORA_LIVE_GEMINI_MAX_TURNS")?.toIntOrNull()?.also {
            require(it in 10..remainingTurns && it % 10 == 0) { "live_turn_limit_must_include_complete_stories" }
        } ?: remainingTurns
        val runScenarios = scenarios.drop((startStory - 1) * 10).take(requestedTurns)
        val continueOnFailure = System.getenv("MEMORA_LIVE_GEMINI_CONTINUE") == "1"

        val runId = UUID.randomUUID().toString()
        val reportFile = liveReportFile(runId)
        reportFile.parentFile?.mkdirs()
        System.err.println("gemini_e2e_report path=${reportFile.absolutePath} start_story=$startStory turns=$requestedTurns")
        val repository = ProtectedDomainRepository(databaseRule.database)
        val conversations = ProtectedConversationRepository(databaseRule.database)
        val memory = ProtectedMemoryRepository(databaseRule.database)
        val journal = RoomAgentRunJournal(databaseRule.database)
        val policy = ConsentAgentRuntimePolicy(cloudConsent = { true }, memoryConsent = { true })
        var completed = 0
        var failedScenarios = 0

        reportFile.bufferedWriter(Charsets.UTF_8).use { report ->
            val stories = runScenarios.groupBy { it.string("story_id") }.values.sortedBy { it.first().long("story_index") }
            stories.forEachIndexed { storyOffset, rows ->
                val storyId = rows.first().string("story_id")
                val scope = stableUuid("$runId:$storyId:scope")
                val actor = stableUuid("$runId:$storyId:actor")
                val storyRows = rows.sortedBy { it.long("turn") }

                storyRows.forEach scenarioLoop@{ scenario ->
                    val turnId = UUID.randomUUID().toString()
                    val history = conversations.recentContext(scope, 12).mapNotNull { row ->
                        row.message.takeLast(4_000).takeIf(String::isNotBlank)
                            ?.let { AgentDialogueMessage(row.role, it) }
                    }
                    val expectedTools = scenario.getValue("expected_tools").jsonArray.map { it.jsonPrimitive.content }.toSet()
                    val turnIndex = scenario.long("turn").toInt()
                    // search_memory returns exact, source-backed task and calendar rows through the unified activity index.
                    val requiredTools = if (turnIndex == 10)
                        expectedTools - setOf("list_tasks", "list_calendar_events") else expectedTools
                    val gateway = GeminiAgentModelGateway()
                    var modelRounds = 0
                    var providerFailureCode: String? = null
                    val countedGateway = object : AgentModelGateway {
                        override suspend fun complete(request: AgentModelRequest): AgentModelReply {
                            if (modelRounds > 0) delay(GEMINI_INTRA_TURN_PACING_MS)
                            modelRounds++
                            return try {
                                gateway.complete(request)
                            } catch (failure: AgentGatewayFailure) {
                                providerFailureCode = failure.safeCode
                                throw failure
                            }
                        }
                    }
                    val started = System.nanoTime()
                    val result = try {
                        AgentOrchestrator(
                            countedGateway,
                            DefaultAgentToolRegistry(LocalMemoryTools(repository, memory, conversations = conversations).handlers()),
                            policy,
                            journal,
                        ).run(AgentTurnRequest(
                            turnId = turnId,
                            scopeId = scope,
                            actorId = actor,
                            sourceRevisionId = null,
                            locale = "az-AZ",
                            text = scenario.string("utterance"),
                            channel = AgentInputChannel.TEXT,
                            conversationHistory = history,
                            responsePreferences = AgentResponsePreferences("az", "friendly", "balanced"),
                        ))
                    } catch (error: Exception) {
                        val runtimeCode = (error as? AgentGatewayFailure)?.safeCode
                            ?: "runtime_exception_${error.javaClass.simpleName}"
                        appendReport(report, scenario, "failed", runtimeCode,
                            emptyList(), modelRounds, System.nanoTime() - started,
                            providerFailureCode = providerFailureCode)
                        failedScenarios++
                        if (isTerminalProviderFailure(providerFailureCode)) {
                            throw IllegalStateException("gemini_e2e_aborted_${providerFailureCode}_after_$completed", error)
                        }
                        if (!continueOnFailure) throw IllegalStateException("${scenario.string("scenario_id")}_$runtimeCode", error)
                        val runtimePacingNanos = modelRounds * GEMINI_REQUEST_PACING_MS * 1_000_000L
                        val runtimePacingRemainingNanos = runtimePacingNanos - (System.nanoTime() - started)
                        if (runtimePacingRemainingNanos > 0 && completed + 1 < requestedTurns) {
                            delay((runtimePacingRemainingNanos + 999_999L) / 1_000_000L)
                        }
                        completed++
                        if (completed % 10 == 0) {
                            System.err.println("gemini_e2e_progress turns=$completed stories=${storyOffset + 1}/100 failures=$failedScenarios")
                        }
                        return@scenarioLoop
                    }

                    val applied = result.toolResults.filter { it.status == ToolObservationStatus.APPLIED }
                        .map { it.name }.toSet()
                    val appliedWrites = applied.intersect(AGENT_PERSISTED_WRITE_TOOLS)
                    val unexpectedWrites = appliedWrites - expectedTools
                    val durationNanos = System.nanoTime() - started
                    // The app stores every returned COMPLETED/PARTIAL turn, even when an
                    // independent quality assertion later fails. Keep following turns on
                    // the same realistic conversation history instead of hiding failures.
                    if (result.status in setOf(AgentRunResultStatus.COMPLETED, AgentRunResultStatus.PARTIAL)) {
                        result.answer?.takeIf(String::isNotBlank)?.let { answer ->
                            conversations.appendTurn(scope, turnId, scenario.string("utterance"), answer,
                                System.currentTimeMillis())
                        }
                    }
                    var failureCode: String? = when {
                        result.status != AgentRunResultStatus.COMPLETED -> "turn_${result.status.name.lowercase()}_${safeCode(result.code)}"
                        result.answer.isNullOrBlank() -> "empty_final_answer"
                        result.answer.orEmpty().any { it in '\u0400'..'\u04ff' } -> "answer_contains_cyrillic"
                        !applied.containsAll(requiredTools) -> "expected_tool_missing"
                        unexpectedWrites.isNotEmpty() -> "unexpected_write_applied"
                        result.toolResults.any { it.name in AGENT_PERSISTED_WRITE_TOOLS &&
                            it.status in setOf(ToolObservationStatus.REJECTED, ToolObservationStatus.CONFLICT, ToolObservationStatus.FAILED) } -> "write_not_applied_cleanly"
                        else -> null
                    }

                    if (failureCode == null) {
                        failureCode = runCatching {
                            verifyScenarioState(turnIndex, storyRows, repository, conversations, memory, scope, result)
                        }.exceptionOrNull()?.let(::safeAssertionCode)
                    }

                    appendReport(report, scenario, if (failureCode == null) "passed" else "failed",
                        failureCode ?: "none", applied.sorted(), modelRounds, durationNanos,
                        result.status.name, result.code, result.toolResults.map { observation ->
                            "${observation.name}:${observation.status.name.lowercase()}:${safeCode(observation.code)}"
                        }.sorted(), providerFailureCode)
                    if (failureCode != null) {
                        failedScenarios++
                        if (isTerminalProviderFailure(providerFailureCode)) {
                            throw IllegalStateException("gemini_e2e_aborted_${providerFailureCode}_after_$completed")
                        }
                        if (!continueOnFailure) throw IllegalStateException("${scenario.string("scenario_id")}_$failureCode")
                    }

                    val pacingNanos = modelRounds * GEMINI_REQUEST_PACING_MS * 1_000_000L
                    val pacingRemainingNanos = pacingNanos - (System.nanoTime() - started)
                    if (pacingRemainingNanos > 0 && completed + 1 < requestedTurns) {
                        delay((pacingRemainingNanos + 999_999L) / 1_000_000L)
                    }
                    completed++
                    if (completed % 10 == 0) {
                        System.err.println("gemini_e2e_progress turns=$completed stories=${storyOffset + 1}/100 failures=$failedScenarios")
                    }
                }
            }
        }
        check(completed == requestedTurns) { "gemini_e2e_incomplete_$completed" }
        System.err.println("gemini_e2e_complete turns=$completed failures=$failedScenarios")
        check(failedScenarios == 0) { "gemini_e2e_failures_$failedScenarios" }
    }

    private suspend fun verifyScenarioState(turn: Int, storyRows: List<JsonObject>, repository: ProtectedDomainRepository,
        conversations: ProtectedConversationRepository, memory: ProtectedMemoryRepository, scope: String,
        result: AgentRunResult) {
        val name = storyRows.first().slots().string("person_name")
        val marker = storyRows.first().string("story_id")
        val originalReceipt = storyRows[0].slots().long("amount_minor")
        val originalDebt = storyRows[2].slots().long("debt_minor")
        val correctedReceipt = storyRows[3].slots().long("corrected_receipt_minor")
        val remaining = storyRows[4].slots().long("remaining_minor")
        val expectedTaskDue = LocalDateTime.parse(storyRows[7].slots().string("due_local"))
        val expectedEventStart = LocalDateTime.parse(storyRows[8].slots().string("start_local"))
        val active = repository.allFinanceEvents(scope).filterNot { repository.isFinanceEventVoided(scope, it.eventId) }
        val adjustments = repository.financeAdjustments(scope)

        when (turn) {
            1 -> {
                val receipts = active.filter { it.kind == "receipt" }
                check(receipts.size == 1) { "receipt_count_${receipts.size}_wrong" }
                val receipt = receipts.singleOrNull() ?: error("receipt_readback_missing")
                check(receipt.minorUnits == originalReceipt) { "receipt_amount_wrong" }
                check(receipt.currency == "AZN") { "receipt_currency_wrong" }
                check(receipt.personId.isNotBlank()) { "receipt_person_link_missing" }
                val people = repository.financePeople(scope)
                check(people.size == 1) { "finance_person_count_${people.size}_wrong" }
                check(people.singleOrNull()?.displayName?.contains(name, ignoreCase = true) == true) {
                    "finance_person_name_wrong"
                }
                check(active.none { it.kind == "debt" }) { "unexpected_debt_created" }
            }
            2 -> {
                val receipts = active.filter { it.kind == "receipt" }
                val annotations = adjustments.filter { it.kind == "annotation" }
                check(receipts.size == 1 && receipts.single().minorUnits == originalReceipt &&
                    active.none { it.kind == "debt" } && annotations.size == 1 &&
                    annotations.single().reversesEventId == receipts.single().eventId &&
                    annotations.single().minorUnits == 0L) { "annotation_created_duplicate_or_changed_amount" }
            }
            3 -> verifyFinanceState(repository, scope, originalReceipt, originalDebt,
                originalDebt - originalReceipt, originalReceipt, originalReceipt, "debt_link_readback_failed")
            4 -> verifyFinanceState(repository, scope, correctedReceipt, originalDebt,
                remaining, originalReceipt, correctedReceipt, "correction_readback_failed")
            5 -> {
                verifyFinanceState(repository, scope, correctedReceipt, originalDebt, remaining,
                    originalReceipt, correctedReceipt, "balance_state_readback_failed")
                val search = result.toolResults.singleOrNull { it.name == "search_money" && it.status == ToolObservationStatus.APPLIED }
                    ?: error("balance_query_did_not_use_ledger")
                val summary = search.modelData["financial_summary"] as? Map<*, *> ?: error("balance_evidence_missing")
                check(summary["outstanding_debt_minor_units"] == remaining) { "balance_evidence_wrong" }
                check(result.answer.orEmpty().contains((remaining / 100).toString())) { "balance_answer_missing_amount" }
            }
            6 -> {
                val episode = conversations.searchEpisodes(scope, name, limit = 5).singleOrNull()
                    ?: error("episode_save_readback_failed")
                val requiredValues = storyRows[5].slots().getValue("required_amounts_minor").jsonArray
                    .map { (it.jsonPrimitive.long / 100).toString() }
                val missingValues = requiredValues.filterNot(episode.summary::contains)
                val unreadableSources = episode.sourceTurnIds.count { conversations.sourceUserTurn(scope, it) == null }
                check(episode.sourceTurnIds.size >= 2 && episode.revision == 1L &&
                    episode.summary.contains(name, ignoreCase = true) && missingValues.isEmpty() && unreadableSources == 0) {
                    "episode_provenance_or_amounts_wrong_sources=${episode.sourceTurnIds.size}_missing=${missingValues.size}_unreadable=$unreadableSources"
                }
            }
            7 -> {
                val search = result.toolResults.singleOrNull { it.name == "search_memory" && it.status == ToolObservationStatus.APPLIED }
                    ?: error("episode_query_did_not_use_memory")
                val episodes = search.modelData["episodes"] as? List<*> ?: error("episode_evidence_missing")
                check(episodes.any { it is Map<*, *> && it["derived_not_canonical"] == true &&
                    it["summary"].toString().contains((remaining / 100).toString()) }) { "episode_search_evidence_wrong" }
                check(result.answer.orEmpty().contains((remaining / 100).toString())) { "episode_answer_missing_balance" }
            }
            8 -> {
                val task = memory.tasks().tasks.filter { it.scopeId == scope }.singleOrNull()
                    ?: error("task_count_readback_wrong")
                val dueMatches = task.dueLocal?.let {
                    runCatching { LocalDateTime.parse(it) == expectedTaskDue }.getOrDefault(false)
                } == true
                check(task.title.contains(name, ignoreCase = true) && dueMatches && task.dueZone == "Asia/Baku" &&
                    task.remind && task.personLabel?.contains(name, ignoreCase = true) == true) { "task_fields_readback_wrong" }
            }
            9 -> {
                val event = repository.allCalendarEvents(scope).singleOrNull() ?: error("calendar_event_count_wrong")
                val startMatches = runCatching { LocalDateTime.parse(event.startLocal) == expectedEventStart }.getOrDefault(false)
                check(event.title.contains("borc", ignoreCase = true) && startMatches && event.zoneId == "Asia/Baku" &&
                    event.timePrecision == "minute" && event.status == "active") { "calendar_event_fields_readback_wrong" }
            }
            10 -> {
                verifyFinanceState(repository, scope, correctedReceipt, originalDebt, remaining,
                    originalReceipt, correctedReceipt, "cross_domain_finance_state_wrong")
                val answer = result.answer.orEmpty()
                check(answer.contains(name, ignoreCase = true) &&
                    answer.contains((remaining / 100).toString()) &&
                    answer.contains(expectedTaskDue.toLocalTime().toString()) &&
                    answer.contains(expectedEventStart.toLocalTime().toString())) {
                    "cross_domain_answer_missing_requested_facts"
                }
                val applied = result.toolResults.filter { it.status == ToolObservationStatus.APPLIED }.associateBy { it.name }
                val finance = applied["search_money"]?.modelData?.get("financial_summary") as? Map<*, *>
                    ?: error("cross_domain_ledger_evidence_missing")
                check(finance["outstanding_debt_minor_units"] == remaining) { "cross_domain_ledger_evidence_wrong" }
                val episodes = applied["search_memory"]?.modelData?.get("episodes") as? List<*>
                    ?: error("cross_domain_memory_evidence_missing")
                check(episodes.any { it is Map<*, *> && it["derived_not_canonical"] == true &&
                    it["summary"].toString().contains((remaining / 100).toString()) }) { "cross_domain_episode_evidence_wrong" }
                val taskRows = applied["list_tasks"]?.modelData?.get("tasks") as? List<*> ?: emptyList<Any>()
                val memoryActivity = applied["search_memory"]?.modelData?.get("activity") as? List<*>
                    ?: error("cross_domain_memory_activity_missing")
                val taskEvidenceFound = taskRows.any { row -> row is Map<*, *> &&
                    row["title"].toString().contains(name, ignoreCase = true) &&
                    row["due_local"].toString().startsWith(expectedTaskDue.toLocalDate().toString()) } ||
                    memoryActivity.any { row -> row is Map<*, *> && row["kind"] == "task" &&
                        row["title"].toString().contains(name, ignoreCase = true) &&
                        row["due_local"].toString().startsWith(expectedTaskDue.toLocalDate().toString()) }
                check(taskEvidenceFound) {
                    "cross_domain_task_evidence_wrong"
                }
                val calendarRows = applied["list_calendar_events"]?.modelData?.get("events") as? List<*> ?: emptyList<Any>()
                val calendarEvidenceFound = calendarRows.any { row -> row is Map<*, *> && runCatching {
                    LocalDateTime.parse(row["start_local"].toString()) == expectedEventStart
                }.getOrDefault(false) } || memoryActivity.any { row -> row is Map<*, *> &&
                    row["kind"] == "calendar_event" && runCatching {
                        LocalDateTime.parse(row["start_local"].toString()) == expectedEventStart
                    }.getOrDefault(false) }
                check(calendarEvidenceFound) { "cross_domain_calendar_evidence_wrong" }
                check(result.toolResults.none { it.name in AGENT_PERSISTED_WRITE_TOOLS &&
                    it.status == ToolObservationStatus.APPLIED }) { "cross_domain_query_unexpectedly_wrote" }
            }
            else -> error("scenario_turn_out_of_range")
        }

        // Every story owns its own scope. An earlier story must never leak a financial or task record here.
        check(repository.financePeople(scope).size <= 1 && memory.tasks().tasks.count { it.scopeId == scope } <= 1 &&
            repository.allCalendarEvents(scope).size <= 1) { "scope_state_cardinality_wrong" }
        check(marker.matches(Regex("S-[0-9]{3}"))) { "scenario_story_marker_invalid" }
    }

    private suspend fun verifyFinanceState(repository: ProtectedDomainRepository, scope: String,
        expectedReceiptMinor: Long, expectedDebtMinor: Long, expectedRemainingMinor: Long,
        expectedSettlementRowMinor: Long, expectedAppliedSettlementMinor: Long, code: String) {
        val people = repository.financePeople(scope)
        check(people.size == 1) { code }
        val history = repository.financeHistory(scope, people.single().displayName) ?: error(code)
        val active = history.events.filter { it.eventId !in history.voidedEventIds }
        val receipts = active.filter { it.kind == "receipt" }
        val debts = active.filter { it.kind == "debt" }
        val settlements = repository.financeAdjustments(scope).filter { it.kind == "settlement" }
        check(receipts.size == 1 && receipts.single().minorUnits == expectedReceiptMinor &&
            debts.size == 1 && debts.single().minorUnits == expectedDebtMinor && settlements.size == 1 &&
            settlements.single().minorUnits == expectedSettlementRowMinor &&
            settlements.single().obligationId == debts.single().eventId &&
            settlements.single().reversesEventId == receipts.single().eventId &&
            repository.appliedToDebtMinor(scope, receipts.single().eventId) == expectedAppliedSettlementMinor &&
            history.remainingDebtMinorByEvent[debts.single().eventId] == expectedRemainingMinor) { code }
    }

    private fun validateCorpus(rows: List<JsonObject>) {
        check(rows.size == 1_000) { "az_live_corpus_size_${rows.size}" }
        check(rows.map { it.string("scenario_id") }.toSet().size == 1_000) { "az_live_scenario_ids_not_unique" }
        check(rows.map { it.string("utterance") }.toSet().size == 1_000) { "az_live_scenario_text_not_unique" }
        check(rows.all { it.string("locale") == "az-AZ" && it.boolean("synthetic") && it["audio_asset"] == kotlinx.serialization.json.JsonNull }) {
            "az_live_corpus_language_or_audio_contract_wrong"
        }
        val stories = rows.groupBy { it.string("story_id") }
        check(stories.size == 100 && stories.values.all { story -> story.map { it.long("turn") }.sorted() == (1L..10L).toList() }) {
            "az_live_story_structure_wrong"
        }
        check(rows.all { it.string("utterance").none { char -> char in '\u0400'..'\u04ff' } }) {
            "az_live_corpus_contains_cyrillic"
        }
    }

    private fun appendReport(writer: java.io.BufferedWriter, scenario: JsonObject, status: String, code: String,
        tools: List<String>, modelRounds: Int, durationNanos: Long, resultStatus: String? = null,
        resultCode: String? = null, toolOutcomes: List<String> = emptyList(), providerFailureCode: String? = null) {
        val row = JsonObject(mapOf(
            "campaign_revision" to JsonPrimitive(CAMPAIGN_REVISION),
            "scenario_id" to JsonPrimitive(scenario.string("scenario_id")),
            "story_id" to JsonPrimitive(scenario.string("story_id")),
            "turn" to JsonPrimitive(scenario.long("turn")),
            "status" to JsonPrimitive(status),
            "code" to JsonPrimitive(safeCode(code)),
            "applied_tools" to JsonArray(tools.map(::JsonPrimitive)),
            "result_status" to JsonPrimitive(resultStatus ?: "unavailable"),
            "result_code" to JsonPrimitive(resultCode?.let(::safeCode) ?: "none"),
            "tool_outcomes" to JsonArray(toolOutcomes.map(::JsonPrimitive)),
            "provider_failure" to JsonPrimitive(providerFailureCode?.let(::safeCode) ?: "none"),
            "model_rounds" to JsonPrimitive(modelRounds),
            "duration_ms" to JsonPrimitive(durationNanos / 1_000_000),
        ))
        writer.append(row.toString()).append('\n')
        writer.flush()
    }

    private fun safeAssertionCode(error: Throwable): String {
        val message = error.message.orEmpty()
        Regex("episode_provenance_or_amounts_wrong_sources=(\\d+)_missing=(\\d+)_unreadable=(\\d+)")
            .find(message)?.let { match ->
                return "episode_sources_${match.groupValues[1]}_missing_${match.groupValues[2]}_unreadable_${match.groupValues[3]}"
            }
        val explicitCode = Regex("[a-zA-Z][a-zA-Z0-9_:-]{1,139}").findAll(message)
            .map { it.value }
            .firstOrNull { token -> token.contains(Regex("_(failed|wrong|missing|invalid|unavailable|ambiguous|empty)$")) }
        if (explicitCode != null) return safeCode(explicitCode)
        val appFrame = error.stackTrace.firstOrNull { frame ->
            frame.className.startsWith("ai.memora.") && !frame.className.contains("GeminiLiveScenarioCampaignTest")
        }
        if (appFrame != null) {
            val owner = appFrame.className.substringAfterLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
            val method = appFrame.methodName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            return "readback_${owner}_${method}_line${appFrame.lineNumber}"
        }
        val safeFrames = error.stackTrace.asSequence()
            .filterNot { it.className.contains("GeminiLiveScenarioCampaignTest") }
            .take(3)
            .map { frame ->
                val owner = frame.className.substringAfterLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_")
                val method = frame.methodName.replace(Regex("[^a-zA-Z0-9_]"), "_")
                "${owner}_${method}_${frame.lineNumber}"
            }
            .toList()
        if (safeFrames.isNotEmpty()) return "readback_${safeFrames.joinToString("_")}".take(140)
        val type = error.javaClass.simpleName.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(80)
        return "readback_exception_${type.ifBlank { "unknown" }}"
    }

    private fun safeCode(value: String?): String = value?.takeIf { it.matches(Regex("[a-zA-Z0-9_:-]{1,140}")) }
        ?: "scenario_failed"

    private fun isTerminalProviderFailure(code: String?): Boolean = code in setOf(
        "model_rate_limited", "model_authentication_failed", "model_permission_denied", "model_unavailable",
        "model_service_unavailable", "model_server_failure", "model_network_unavailable",
    )

    private fun findRepoFile(relative: String): File = requireNotNull(
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isFile),
    ) { "${relative.substringAfterLast('/')}_not_found" }

    private fun liveReportFile(runId: String): File {
        val moduleRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("android_module_root_not_found")
        return File(moduleRoot, "app/build/live-gemini-e2e/$runId.jsonl")
    }

    private companion object {
        const val CAMPAIGN_REVISION = 2
        const val GEMINI_REQUEST_PACING_MS = 12_000L
        const val GEMINI_INTRA_TURN_PACING_MS = 5_000L
    }

    private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()
    private fun JsonObject.slots(): JsonObject = getValue("expected_slots").jsonObject
}
